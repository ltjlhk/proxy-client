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
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TrojanVpnService - Android VpnService that implements Trojan protocol proxy.
 *
 * Trojan protocol:
 * 1. Establish TLS connection to server
 * 2. Send: SHA224(password) + CRLF + target_addr_type + target_addr + target_port + CRLF + payload
 * 3. Receive: CRLF + payload (response from target)
 *
 * This service:
 * - Creates a TUN interface via Android VpnService
 * - Reads IP packets from TUN
 * - Parses TCP/UDP packets to extract destination address/port
 * - Forwards through Trojan protocol to the remote server
 * - Writes responses back to TUN
 */
class TrojanVpnService : VpnService() {

    companion object {
        const val TAG = "TrojanVpnService"
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_DNS1 = "8.8.8.8"
        const val VPN_DNS2 = "8.8.4.4"
        const val NOTIFICATION_CHANNEL_ID = "trojan_vpn_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.proxyclient.proxy_client.CONNECT"
        const val ACTION_DISCONNECT = "com.proxyclient.proxy_client.DISCONNECT"

        var isRunning = false
            private set

        // Trojan protocol constants
        private const val TROJAN_CR_LF = "\r\n"
        private const val TROJAN_CRLF = "\r\n".toByteArray()
        private const val ATYPE_IPV4: Byte = 0x01
        private const val ATYPE_DOMAIN: Byte = 0x03
        private const val ATYPE_IPV6: Byte = 0x04
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val isActive = AtomicBoolean(false)
    private val totalUpload = AtomicLong(0)
    private val totalDownload = AtomicLong(0)
    private var lastUpload = AtomicLong(0)
    private var lastDownload = AtomicLong(0)
    private var trafficThread: Thread? = null

    // Server configuration
    private var serverHost: String = "47.80.241.156"
    private var serverPort: Int = 8443
    private var trojanPassword: String = "proxy123456"
    private var tlsSni: String = "proxy.local"

    // Pre-computed password hash (SHA224)
    private var passwordHash: ByteArray = ByteArray(0)

    // TLS context that trusts all certificates (for self-signed)
    private var sslContext: SSLContext? = null

    // Connection pool for reuse
    private val connectionPool = ConcurrentHashMap<String, TrojanConnection>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initSslContext()
    }

    private fun initSslContext() {
        try {
            // Create a TrustManager that accepts all certificates (insecure mode for self-signed)
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init SSL context", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                serverHost = intent.getStringExtra("serverHost") ?: serverHost
                serverPort = intent.getIntExtra("serverPort", serverPort)
                trojanPassword = intent.getStringExtra("trojanPassword") ?: trojanPassword
                tlsSni = intent.getStringExtra("tlsSni") ?: tlsSni
                startVpn()
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
            else -> {
                if (!isRunning) startVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            // Pre-compute SHA224 hash of password
            passwordHash = sha224(trojanPassword)

            val builder = Builder()
                .setSession("Trojan Proxy")
                .addAddress(VPN_ADDRESS, 24)
                .addDnsServer(VPN_DNS1)
                .addDnsServer(VPN_DNS2)
                .addRoute(VPN_ROUTE, 0)
                .setMtu(1500)
                .allowFamily(android.system.OsConstants.AF_INET)
                .allowFamily(android.system.OsConstants.AF_INET6)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning = true
                isActive.set(true)
                startForeground(NOTIFICATION_ID, createNotification())

                // Start VPN packet processing thread
                vpnThread = Thread({
                    processVpnPackets()
                }, "VPN-Processor").apply {
                    isDaemon = true
                    start()
                }

                // Start traffic reporting thread
                trafficThread = Thread({
                    reportTraffic()
                }, "Traffic-Reporter").apply {
                    isDaemon = true
                    start()
                }

                sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_CONNECTED"))
                Log.i(TAG, "Trojan VPN started - server: $serverHost:$serverPort")
            } else {
                sendErrorBroadcast("Failed to establish VPN interface")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Trojan VPN", e)
            sendErrorBroadcast(e.message ?: "Failed to start VPN")
            stopVpn()
        }
    }

    private fun stopVpn() {
        isActive.set(false)
        isRunning = false

        // Close all pooled connections
        connectionPool.values.forEach { it.close() }
        connectionPool.clear()

        vpnThread?.interrupt()
        vpnThread = null

        trafficThread?.interrupt()
        trafficThread = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_DISCONNECTED"))
        Log.i(TAG, "Trojan VPN stopped")
    }

    /**
     * Main VPN packet processing loop.
     * Reads raw IP packets from TUN interface, parses them,
     * and forwards through Trojan protocol.
     */
    private fun processVpnPackets() {
        vpnInterface?.let { vpn ->
            val vpnInput = FileInputStream(vpn.fileDescriptor)
            val vpnOutput = FileOutputStream(vpn.fileDescriptor)
            val buffer = ByteArray(32767)

            while (isActive.get()) {
                try {
                    val length = vpnInput.read(buffer)
                    if (length <= 0) continue

                    val packet = buffer.copyOfRange(0, length)

                    // Parse IP version
                    if (packet.isEmpty()) continue
                    val version = (packet[0].toInt() shr 4) and 0x0F

                    when (version) {
                        4 -> handleIPv4Packet(packet, vpnOutput)
                        6 -> handleIPv6Packet(packet, vpnOutput)
                    }
                } catch (e: Exception) {
                    if (isActive.get()) {
                        Log.e(TAG, "Error processing VPN packet", e)
                    }
                }
            }
        }
    }

    /**
     * Handle IPv4 packet - extract TCP/UDP destination and forward via Trojan.
     */
    private fun handleIPv4Packet(packet: ByteArray, vpnOutput: FileOutputStream) {
        if (packet.size < 20) return

        val protocol = packet[9].toInt() and 0xFF
        // Source IP: bytes 12-15
        // Destination IP: bytes 16-19
        val destIpBytes = packet.copyOfRange(16, 20)
        val destIp = InetAddress.getByAddress(destIpBytes).hostAddress ?: return

        // Header length
        val ihl = (packet[0].toInt() and 0x0F) * 4

        when (protocol) {
            6 -> { // TCP
                if (packet.size < ihl + 4) return
                val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                        (packet[ihl + 3].toInt() and 0xFF)
                val payload = packet.copyOfRange(ihl, packet.size)
                handleTcpConnection(destIp, destPort, payload, packet, vpnOutput)
            }
            17 -> { // UDP
                if (packet.size < ihl + 4) return
                val destPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                        (packet[ihl + 3].toInt() and 0xFF)
                val payload = packet.copyOfRange(ihl, packet.size)
                handleUdpPacket(destIp, destPort, payload, packet, vpnOutput)
            }
        }
    }

    /**
     * Handle IPv6 packet.
     */
    private fun handleIPv6Packet(packet: ByteArray, vpnOutput: FileOutputStream) {
        if (packet.size < 40) return

        val protocol = packet[6].toInt() and 0xFF
        // Destination IP: bytes 24-39
        val destIpBytes = packet.copyOfRange(24, 40)
        val destIp = InetAddress.getByAddress(destIpBytes).hostAddress ?: return

        when (protocol) {
            6 -> { // TCP
                if (packet.size < 54) return
                val destPort = ((packet[44].toInt() and 0xFF) shl 8) or
                        (packet[45].toInt() and 0xFF)
                val payload = packet.copyOfRange(40, packet.size)
                handleTcpConnection(destIp, destPort, payload, packet, vpnOutput)
            }
            17 -> { // UDP
                if (packet.size < 48) return
                val destPort = ((packet[44].toInt() and 0xFF) shl 8) or
                        (packet[45].toInt() and 0xFF)
                val payload = packet.copyOfRange(40, packet.size)
                handleUdpPacket(destIp, destPort, payload, packet, vpnOutput)
            }
        }
    }

    /**
     * Handle TCP connection via Trojan protocol.
     * Opens a TLS connection to the Trojan server, sends the Trojan request,
     * and relays data bidirectionally.
     */
    private fun handleTcpConnection(
        destIp: String,
        destPort: Int,
        tcpPayload: ByteArray,
        originalPacket: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        val connKey = "$destIp:$destPort"

        try {
            // Create new Trojan connection
            val conn = createTrojanConnection(destIp, destPort)

            // Send initial TCP payload through Trojan
            conn.send(tcpPayload)

            // Relay response back to VPN in a separate thread
            Thread({
                try {
                    val responseData = conn.receive()
                    if (responseData.isNotEmpty()) {
                        // Reconstruct IP packet with response data
                        // For simplicity, we write raw response data
                        // In a full implementation, we'd reconstruct proper IP/TCP packets
                        vpnOutput.write(responseData)
                        vpnOutput.flush()
                        totalDownload.addAndGet(responseData.size.toLong())
                    }
                } catch (e: Exception) {
                    if (isActive.get()) {
                        Log.e(TAG, "Error relaying TCP response for $connKey", e)
                    }
                } finally {
                    conn.close()
                }
            }, "TCP-Relay-$connKey").apply {
                isDaemon = true
                start()
            }

            totalUpload.addAndGet(tcpPayload.size.toLong())

        } catch (e: Exception) {
            if (isActive.get()) {
                Log.e(TAG, "TCP connection error for $connKey", e)
            }
        }
    }

    /**
     * Handle UDP packet via Trojan protocol (UDP over TCP).
     */
    private fun handleUdpPacket(
        destIp: String,
        destPort: Int,
        udpPayload: ByteArray,
        originalPacket: ByteArray,
        vpnOutput: FileOutputStream
    ) {
        try {
            val conn = createTrojanConnection(destIp, destPort)
            conn.send(udpPayload)

            Thread({
                try {
                    val responseData = conn.receive()
                    if (responseData.isNotEmpty()) {
                        vpnOutput.write(responseData)
                        vpnOutput.flush()
                        totalDownload.addAndGet(responseData.size.toLong())
                    }
                } catch (e: Exception) {
                    if (isActive.get()) {
                        Log.e(TAG, "Error relaying UDP response", e)
                    }
                } finally {
                    conn.close()
                }
            }, "UDP-Relay").apply {
                isDaemon = true
                start()
            }

            totalUpload.addAndGet(udpPayload.size.toLong())
        } catch (e: Exception) {
            if (isActive.get()) {
                Log.e(TAG, "UDP proxy error for $destIp:$destPort", e)
            }
        }
    }

    /**
     * Create a Trojan protocol connection to the server.
     *
     * Trojan request format:
     * SHA224(password) + CRLF + ATYPE + ADDR + PORT + CRLF + PAYLOAD
     */
    private fun createTrojanConnection(destIp: String, destPort: Int): TrojanConnection {
        val socket = Socket()
        socket.connect(InetSocketAddress(serverHost, serverPort), 15000)
        protect(socket) // Prevent routing loop

        // Wrap with TLS
        val tlsSocket = sslContext?.getSocketFactory()
            ?.createSocket(socket, serverHost, serverPort, true)
            ?: throw Exception("SSL context not initialized")

        // Enable all supported protocols
        tlsSocket.enabledProtocols = tlsSocket.supportedProtocols

        // Set SNI
        val sslParams = tlsSocket.sslParameters
        sslParams.serverNames = listOf(
            javax.net.ssl.SNIHostName(tlsSni)
        )
        tlsSocket.sslParameters = sslParams

        tlsSocket.startHandshake()

        val outputStream: OutputStream = tlsSocket.outputStream
        val inputStream: InputStream = tlsSocket.inputStream

        // Build Trojan request header:
        // password_hash(56 bytes) + CRLF(2) + addr_type(1) + addr(variable) + port(2) + CRLF(2)
        val header = ByteBuffer.allocate(64 + destIp.length + 8)

        // Password hash
        header.put(passwordHash)
        // CRLF
        header.put(TROJAN_CRLF)
        // Address type
        header.put(ATYPE_IPV4)

        // Parse destination IP to bytes
        val addrParts = destIp.split(".")
        if (addrParts.size == 4) {
            for (part in addrParts) {
                header.put(part.toInt().toByte())
            }
        }

        // Destination port (big-endian)
        header.put((destPort shr 8).toByte())
        header.put((destPort and 0xFF).toByte())

        // CRLF
        header.put(TROJAN_CRLF)

        // Write header
        val headerBytes = ByteArray(header.position())
        header.flip()
        header.get(headerBytes)
        outputStream.write(headerBytes)
        outputStream.flush()

        return TrojanConnection(tlsSocket, inputStream, outputStream)
    }

    /**
     * Compute SHA-224 hash of the password.
     */
    private fun sha224(password: String): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-224")
        return md.digest(password.toByteArray(Charsets.UTF_8))
    }

    /**
     * Periodically report traffic stats to Flutter via broadcast.
     */
    private fun reportTraffic() {
        while (isActive.get()) {
            try {
                Thread.sleep(2000)
                if (!isActive.get()) break

                val currentUpload = totalUpload.get()
                val currentDownload = totalDownload.get()
                val uploadSpeed = currentUpload - lastUpload.getAndSet(currentUpload)
                val downloadSpeed = currentDownload - lastDownload.getAndSet(currentDownload)

                sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_TRAFFIC").apply {
                    putExtra("upload", currentUpload)
                    putExtra("download", currentDownload)
                    putExtra("uploadSpeed", uploadSpeed)
                    putExtra("downloadSpeed", downloadSpeed)
                })
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting traffic", e)
            }
        }
    }

    private fun sendErrorBroadcast(message: String) {
        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_ERROR").apply {
            putExtra("errorMessage", message)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Trojan VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trojan Proxy VPN connection"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, TrojanVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Proxy Client")
            .setContentText("Trojan VPN connected - $serverHost:$serverPort")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Disconnect",
                disconnectPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    /**
     * Wrapper class for a Trojan protocol connection.
     */
    class TrojanConnection(
        private val socket: Socket,
        private val inputStream: InputStream,
        private val outputStream: OutputStream
    ) {
        private val isActive = AtomicBoolean(true)

        fun send(data: ByteArray) {
            if (!isActive.get()) throw Exception("Connection closed")
            outputStream.write(data)
            outputStream.flush()
        }

        fun receive(): ByteArray {
            if (!isActive.get()) return ByteArray(0)
            val buffer = ByteArray(32767)
            val length = inputStream.read(buffer)
            return if (length > 0) buffer.copyOfRange(0, length) else ByteArray(0)
        }

        fun close() {
            isActive.set(false)
            try { outputStream.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
