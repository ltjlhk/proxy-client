package com.proxyclient.proxy_client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TrojanVpnService : VpnService() {

    companion object {
        private const val TAG = "TrojanVpn"
        private const val CHANNEL_ID = "trojan_vpn_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_CONNECT = "com.proxyclient.proxy_client.CONNECT"
        const val ACTION_DISCONNECT = "com.proxyclient.proxy_client.DISCONNECT"

        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var serverHost = "47.80.241.156"
    private var serverPort = 8443
    private var password = "proxy123456"
    private var sni = "proxy.local"

    // TCP connection tracking: key = "srcIP:srcPort-dstIP:dstPort"
    private val tcpConnections = ConcurrentHashMap<String, TcpTunnel>()

    // Sequence counters for TCP
    private val seqCounter = AtomicInteger(1000000)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                serverHost = intent.getStringExtra("serverHost") ?: serverHost
                serverPort = intent.getIntExtra("serverPort", serverPort)
                password = intent.getStringExtra("trojanPassword") ?: password
                sni = intent.getStringExtra("tlsSni") ?: sni
                startVpn()
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return
        try {
            val builder = Builder()
                .setSession("Trojan Proxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                sendError("Cannot establish VPN interface")
                return
            }

            isRunning = true
            running.set(true)
            startForeground(NOTIF_ID, createNotification("Connecting to $serverHost:$serverPort..."))

            // Start packet processing thread
            Thread { processPackets() }.start()

            // Delayed connected notification
            Thread {
                Thread.sleep(1500)
                if (running.get()) {
                    sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_CONNECTED"))
                    updateNotification("Connected - $serverHost:$serverPort")
                }
            }.start()

            Log.i(TAG, "VPN started, Trojan server: $serverHost:$serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            sendError(e.message ?: "Unknown error")
            stopVpn()
        }
    }

    private fun processPackets() {
        val vpnIn = FileInputStream(vpnInterface!!.fileDescriptor)
        val vpnOut = FileOutputStream(vpnInterface!!.fileDescriptor)
        val buffer = ByteArray(32767)

        while (running.get()) {
            try {
                val length = vpnIn.read(buffer)
                if (length <= 0) continue

                val packet = buffer.copyOfRange(0, length)
                val version = (packet[0].toInt() shr 4) and 0x0F

                if (version == 4) {
                    handleIPv4(packet, vpnOut)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Packet read error: ${e.message}")
            }
        }
    }

    private fun handleIPv4(packet: ByteArray, vpnOut: FileOutputStream) {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val protocol = packet[9].toInt() and 0xFF

        val srcIP = intToIp(packet, 12)
        val dstIP = intToIp(packet, 16)

        when (protocol) {
            6 -> handleTCP(packet, ihl, totalLen, srcIP, dstIP, vpnOut)
            17 -> handleUDP(packet, ihl, totalLen, srcIP, dstIP, vpnOut)
        }
    }

    private fun handleTCP(packet: ByteArray, ipHdrLen: Int, totalLen: Int, srcIP: String, dstIP: String, vpnOut: FileOutputStream) {
        val tcpOff = ipHdrLen
        val srcPort = ((packet[tcpOff].toInt() and 0xFF) shl 8) or (packet[tcpOff + 1].toInt() and 0xFF)
        val dstPort = ((packet[tcpOff + 2].toInt() and 0xFF) shl 8) or (packet[tcpOff + 3].toInt() and 0xFF)
        val dataOff = ((packet[tcpOff + 12].toInt() shr 4) and 0x0F) * 4
        val flags = packet[tcpOff + 13].toInt() and 0xFF
        val seq = ((packet[tcpOff + 4].toInt() and 0xFF) shl 24) or
                ((packet[tcpOff + 5].toInt() and 0xFF) shl 16) or
                ((packet[tcpOff + 6].toInt() and 0xFF) shl 8) or
                (packet[tcpOff + 7].toInt() and 0xFF)
        val ackNum = ((packet[tcpOff + 8].toInt() and 0xFF) shl 24) or
                ((packet[tcpOff + 9].toInt() and 0xFF) shl 16) or
                ((packet[tcpOff + 10].toInt() and 0xFF) shl 8) or
                (packet[tcpOff + 11].toInt() and 0xFF)
        val window = ((packet[tcpOff + 14].toInt() and 0xFF) shl 8) or (packet[tcpOff + 15].toInt() and 0xFF)

        val syn = (flags and 0x02) != 0
        val ack = (flags and 0x10) != 0
        val fin = (flags and 0x01) != 0
        val rst = (flags and 0x04) != 0
        val psh = (flags and 0x08) != 0

        val key = "$srcIP:$srcPort-$dstIP:$dstPort"

        // Handle RST or FIN
        if (rst || fin) {
            tcpConnections[key]?.close()
            tcpConnections.remove(key)
            return
        }

        // SYN (new connection)
        if (syn && !ack) {
            try {
                val tunnel = TcpTunnel(serverHost, serverPort, password, sni, dstIP, dstPort, this)
                tcpConnections[key] = tunnel

                // Send SYN-ACK to client
                val mySeq = seqCounter.incrementAndGet()
                val synAckPacket = buildTcpPacket(
                    srcIP = dstIP, dstIP = srcIP,
                    srcPort = dstPort, dstPort = srcPort,
                    seq = mySeq, ack = seq + 1,
                    syn = true, ack = true,
                    window = 65535
                )
                vpnOut.write(synAckPacket)
                vpnOut.flush()
                tunnel.clientAck = seq + 1
                tunnel.serverSeq = mySeq + 1
            } catch (e: Exception) {
                Log.e(TAG, "TCP connect failed: $dstIP:$dstPort - ${e.message}")
                // Send RST
                val rstPacket = buildTcpPacket(
                    srcIP = dstIP, dstIP = srcIP,
                    srcPort = dstPort, dstPort = srcPort,
                    seq = 0, ack = seq + 1,
                    rst = true
                )
                vpnOut.write(rstPacket)
                vpnOut.flush()
            }
            return
        }

        // ACK with data (existing connection)
        val tunnel = tcpConnections[key]
        if (tunnel != null && ack) {
            val payloadLen = totalLen - ipHdrLen - dataOff
            if (payloadLen > 0) {
                val payload = packet.copyOfRange(ipHdrLen + dataOff, totalLen)
                tunnel.clientAck = ackNum
                tunnel.sendData(payload)

                // Read response and send back
                Thread {
                    try {
                        val response = tunnel.readResponse(3000)
                        if (response != null && response.isNotEmpty()) {
                            val respPacket = buildTcpPacket(
                                srcIP = dstIP, dstIP = srcIP,
                                srcPort = dstPort, dstPort = srcPort,
                                seq = tunnel.serverSeq,
                                ack = tunnel.clientAck + payloadLen,
                                psh = true,
                                payload = response
                            )
                            synchronized(vpnOut) {
                                vpnOut.write(respPacket)
                                vpnOut.flush()
                            }
                            tunnel.serverSeq += response.size
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Response read error: ${e.message}")
                    }
                }.start()
            }
        }
    }

    private fun handleUDP(packet: ByteArray, ipHdrLen: Int, totalLen: Int, srcIP: String, dstIP: String, vpnOut: FileOutputStream) {
        val udpOff = ipHdrLen
        val srcPort = ((packet[udpOff].toInt() and 0xFF) shl 8) or (packet[udpOff + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOff + 2].toInt() and 0xFF) shl 8) or (packet[udpOff + 3].toInt() and 0xFF)
        val udpLen = ((packet[udpOff + 4].toInt() and 0xFF) shl 8) or (packet[udpOff + 5].toInt() and 0xFF)

        // Only handle DNS (port 53)
        if (dstPort != 53) return

        val dnsPayload = packet.copyOfRange(ipHdrLen + 8, totalLen)

        Thread {
            try {
                val tunnel = TcpTunnel(serverHost, serverPort, password, sni, dstIP, dstPort, this)
                val response = tunnel.sendAndReceive(dnsPayload)
                tunnel.close()

                if (response != null && response.isNotEmpty()) {
                    val newUdpLen = 8 + response.size
                    val newIpLen = ipHdrLen + newUdpLen
                    val respPacket = ByteArray(newIpLen)

                    // Copy IP header
                    System.arraycopy(packet, 0, respPacket, 0, ipHdrLen)
                    // Swap IPs
                    System.arraycopy(packet, 12, respPacket, 16, 4)
                    System.arraycopy(packet, 16, respPacket, 12, 4)
                    // Update total length
                    respPacket[2] = ((newIpLen shr 8) and 0xFF).toByte()
                    respPacket[3] = (newIpLen and 0xFF).toByte()
                    // Recalculate IP checksum
                    respPacket[10] = 0
                    respPacket[11] = 0
                    val ipCksum = checksum(respPacket, 0, ipHdrLen)
                    respPacket[10] = ((ipCksum shr 8) and 0xFF).toByte()
                    respPacket[11] = (ipCksum and 0xFF).toByte()

                    // UDP header
                    respPacket[ipHdrLen] = ((dstPort shr 8) and 0xFF).toByte()
                    respPacket[ipHdrLen + 1] = (dstPort and 0xFF).toByte()
                    respPacket[ipHdrLen + 2] = ((srcPort shr 8) and 0xFF).toByte()
                    respPacket[ipHdrLen + 3] = (srcPort and 0xFF).toByte()
                    respPacket[ipHdrLen + 4] = ((newUdpLen shr 8) and 0xFF).toByte()
                    respPacket[ipHdrLen + 5] = (newUdpLen and 0xFF).toByte()
                    respPacket[ipHdrLen + 6] = 0
                    respPacket[ipHdrLen + 7] = 0
                    // UDP checksum
                    val udpCksum = checksum(respPacket, ipHdrLen, newUdpLen)
                    respPacket[ipHdrLen + 6] = ((udpCksum shr 8) and 0xFF).toByte()
                    respPacket[ipHdrLen + 7] = (udpCksum and 0xFF).toByte()

                    // DNS payload
                    System.arraycopy(response, 0, respPacket, ipHdrLen + 8, response.size)

                    synchronized(vpnOut) {
                        vpnOut.write(respPacket)
                        vpnOut.flush()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DNS query failed: ${e.message}")
            }
        }.start()
    }

    private fun buildTcpPacket(
        srcIP: String, dstIP: String,
        srcPort: Int, dstPort: Int,
        seq: Int, ack: Int,
        syn: Boolean = false, ackFlag: Boolean = false,
        fin: Boolean = false, rst: Boolean = false,
        psh: Boolean = false,
        window: Int = 65535,
        payload: ByteArray? = null
    ): ByteArray {
        val ipHdrLen = 20
        val tcpHdrLen = 20
        val payloadLen = payload?.size ?: 0
        val totalLen = ipHdrLen + tcpHdrLen + payloadLen
        val pkt = ByteArray(totalLen)

        // IP header
        pkt[0] = 0x45 // version 4, IHL 5
        pkt[1] = 0x00 // TOS
        pkt[2] = ((totalLen shr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        // ID, flags, fragment offset = 0
        pkt[8] = 64  // TTL
        pkt[9] = 6   // TCP

        val srcBytes = srcIP.split(".").map { it.toInt().toByte() }.toByteArray()
        val dstBytes = dstIP.split(".").map { it.toInt().toByte() }.toByteArray()
        System.arraycopy(srcBytes, 0, pkt, 12, 4)
        System.arraycopy(dstBytes, 0, pkt, 16, 4)

        // IP checksum
        val ipCksum = checksum(pkt, 0, ipHdrLen)
        pkt[10] = ((ipCksum shr 8) and 0xFF).toByte()
        pkt[11] = (ipCksum and 0xFF).toByte()

        // TCP header
        pkt[ipHdrLen] = ((srcPort shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 1] = (srcPort and 0xFF).toByte()
        pkt[ipHdrLen + 2] = ((dstPort shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 3] = (dstPort and 0xFF).toByte()
        pkt[ipHdrLen + 4] = ((seq shr 24) and 0xFF).toByte()
        pkt[ipHdrLen + 5] = ((seq shr 16) and 0xFF).toByte()
        pkt[ipHdrLen + 6] = ((seq shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 7] = (seq and 0xFF).toByte()
        pkt[ipHdrLen + 8] = ((ack shr 24) and 0xFF).toByte()
        pkt[ipHdrLen + 9] = ((ack shr 16) and 0xFF).toByte()
        pkt[ipHdrLen + 10] = ((ack shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 11] = (ack and 0xFF).toByte()
        pkt[ipHdrLen + 12] = ((tcpHdrLen shr 4) shl 4).toByte() // data offset
        var tcpFlags = 0
        if (syn) tcpFlags = tcpFlags or 0x02
        if (ackFlag) tcpFlags = tcpFlags or 0x10
        if (fin) tcpFlags = tcpFlags or 0x01
        if (rst) tcpFlags = tcpFlags or 0x04
        if (psh) tcpFlags = tcpFlags or 0x08
        pkt[ipHdrLen + 13] = tcpFlags.toByte()
        pkt[ipHdrLen + 14] = ((window shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 15] = (window and 0xFF).toByte()

        // TCP checksum (with pseudo header)
        if (payload != null) {
            System.arraycopy(payload, 0, pkt, ipHdrLen + tcpHdrLen, payloadLen)
        }
        val tcpCksum = tcpChecksum(pkt, srcBytes, dstBytes, ipHdrLen, tcpHdrLen + payloadLen)
        pkt[ipHdrLen + 16] = ((tcpCksum shr 8) and 0xFF).toByte()
        pkt[ipHdrLen + 17] = (tcpCksum and 0xFF).toByte()

        return pkt
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 != 0) {
            sum += (data[length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(pkt: ByteArray, srcIP: ByteArray, dstIP: ByteArray, tcpOffset: Int, tcpLen: Int): Int {
        // Pseudo header: srcIP(4) + dstIP(4) + zero(1) + protocol(1) + tcpLen(2)
        val pseudo = ByteArray(12 + tcpLen)
        System.arraycopy(srcIP, 0, pseudo, 0, 4)
        System.arraycopy(dstIP, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = 6 // TCP
        pseudo[10] = ((tcpLen shr 8) and 0xFF).toByte()
        pseudo[11] = (tcpLen and 0xFF).toByte()
        System.arraycopy(pkt, tcpOffset, pseudo, 12, tcpLen)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun intToIp(pkt: ByteArray, offset: Int): String {
        return "${pkt[offset].toInt() and 0xFF}.${pkt[offset + 1].toInt() and 0xFF}.${pkt[offset + 2].toInt() and 0xFF}.${pkt[offset + 3].toInt() and 0xFF}"
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_DISCONNECTED"))
    }

    private fun sendError(msg: String) {
        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_ERROR").apply {
            putExtra("errorMessage", msg)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Trojan VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun createNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Proxy Client")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, createNotification(text))
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    /**
     * TCP tunnel: establishes a Trojan connection to the target through the proxy server.
     */
    class TcpTunnel(
        private val proxyHost: String,
        private val proxyPort: Int,
        private val password: String,
        private val sni: String,
        private val targetHost: String,
        private val targetPort: Int,
        private val vpnService: VpnService
    ) {
        var clientAck = 0
        var serverSeq = 0
        private var socket: SSLSocket? = null
        private val buffer = java.io.ByteArrayOutputStream()

        fun sendData(data: ByteArray) {
            try {
                if (socket == null || socket!!.isClosed) {
                    connect()
                }
                socket!!.outputStream.write(data)
                socket!!.outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Send data error: ${e.message}")
            }
        }

        fun readResponse(timeoutMs: Int = 5000): ByteArray? {
            return try {
                if (socket == null || socket!!.isClosed) return null
                socket!!.soTimeout = timeoutMs
                val buf = ByteArray(65535)
                val n = socket!!.inputStream.read(buf)
                if (n > 0) buf.copyOfRange(0, n) else null
            } catch (e: Exception) {
                null
            }
        }

        fun sendAndReceive(data: ByteArray): ByteArray? {
            return try {
                if (socket == null || socket!!.isClosed) connect()
                socket!!.outputStream.write(data)
                socket!!.outputStream.flush()
                socket!!.soTimeout = 5000
                val buf = ByteArray(65535)
                val n = socket!!.inputStream.read(buf)
                if (n > 0) buf.copyOfRange(0, n) else null
            } catch (e: Exception) {
                Log.w(TAG, "sendAndReceive error: ${e.message}")
                null
            }
        }

        private fun connect() {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(TrustAllManager), java.security.SecureRandom())

            val rawSocket = Socket()
            vpnService.protect(rawSocket)
            rawSocket.connect(InetSocketAddress(proxyHost, proxyPort), 10000)
            rawSocket.tcpNoDelay = true

            val tls = ctx.socketFactory.createSocket(rawSocket, proxyHost, proxyPort, true) as SSLSocket
            val params = tls.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            tls.sslParameters = params
            tls.startHandshake()

            // Trojan handshake: password\r\n + target_addr\r\n
            val out = tls.outputStream
            out.write(password.toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray())
            // Target in SOCKS5-like format
            out.write(0x03) // ATYPE_DOMAIN
            out.write(targetHost.length)
            out.write(targetHost.toByteArray(Charsets.UTF_8))
            out.write((targetPort shr 8).toByte())
            out.write((targetPort and 0xFF).toByte())
            out.write("\r\n".toByteArray())
            out.flush()

            socket = tls
        }

        fun close() {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }
}
