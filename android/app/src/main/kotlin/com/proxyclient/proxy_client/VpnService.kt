package com.proxyclient.proxy_client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TrojanVpnService - 本地 SOCKS5 代理服务器 + Trojan 协议转发。
 *
 * 架构：
 * 1. 在本地启动 SOCKS5 代理服务器（默认端口 7890）
 * 2. 接收客户端的 SOCKS5 连接请求
 * 3. 通过 Trojan 协议（TLS + 密码认证）将流量转发到远程服务器
 * 4. 使用 VpnService.protect() 保护 Trojan 连接，防止回环
 *
 * 优点：
 * - 不需要处理 IP 包和 TCP 状态机
 * - SOCKS5 协议简单可靠
 * - 不依赖外部二进制（sing-box）
 *
 * 使用方式：
 * 连接后需要在 WiFi 设置中配置 HTTP 代理: 127.0.0.1:7890
 */
class TrojanVpnService : VpnService() {

    companion object {
        const val TAG = "TrojanVpnService"
        const val NOTIFICATION_CHANNEL_ID = "trojan_vpn_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.proxyclient.proxy_client.CONNECT"
        const val ACTION_DISCONNECT = "com.proxyclient.proxy_client.DISCONNECT"

        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serverSocket: ServerSocket? = null
    private var proxyThread: Thread? = null
    private val isActive = java.util.concurrent.atomic.AtomicBoolean(false)

    // Server configuration (defaults, overridden by intent extras)
    private var serverHost = "47.80.241.156"
    private var serverPort = 8443
    private var trojanPassword = "proxy123456"
    private var tlsSni = "proxy.local"
    private var localSocksPort = 7890

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                serverHost = intent.getStringExtra("serverHost") ?: serverHost
                serverPort = intent.getIntExtra("serverPort", serverPort)
                trojanPassword = intent.getStringExtra("trojanPassword") ?: trojanPassword
                tlsSni = intent.getStringExtra("tlsSni") ?: tlsSni
                startProxy()
            }
            ACTION_DISCONNECT -> stopProxy()
        }
        return START_STICKY
    }

    /**
     * Start the SOCKS5 proxy server.
     *
     * We still create a minimal VpnService TUN interface so that:
     * 1. The service can run as a foreground service (required on Android 8+)
     * 2. We can use protect() to prevent routing loops on Trojan connections
     * 3. The VPN key icon appears to inform the user
     */
    private fun startProxy() {
        if (isRunning) return

        try {
            // Create a minimal VPN interface for protect() support and foreground service
            val builder = Builder()
                .setSession("Trojan SOCKS5 Proxy")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()

            isRunning = true
            isActive.set(true)
            startForeground(NOTIFICATION_ID, createNotification("Starting SOCKS5 proxy..."))

            // Launch SOCKS5 server in a background thread
            proxyThread = Thread({ runSocks5Server() }, "SOCKS5-Server").start()

            Log.i(TAG, "SOCKS5 proxy starting on port $localSocksPort, Trojan server: $serverHost:$serverPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            sendErrorBroadcast(e.message ?: "Failed to start proxy")
            stopProxy()
        }
    }

    /**
     * Run the SOCKS5 proxy server main loop.
     * Accepts client connections and spawns a thread for each.
     */
    private fun runSocks5Server() {
        try {
            serverSocket = ServerSocket(localSocksPort)
            updateNotification("SOCKS5 proxy running on 127.0.0.1:$localSocksPort")

            // Notify Flutter that the proxy is ready
            sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_CONNECTED"))

            while (isActive.get()) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    Thread { handleSocks5Client(clientSocket) }.start()
                } catch (e: Exception) {
                    if (isActive.get()) {
                        Log.e(TAG, "Accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 server error", e)
            if (isActive.get()) {
                sendErrorBroadcast(e.message ?: "SOCKS5 server error")
                stopProxy()
            }
        }
    }

    /**
     * Handle a single SOCKS5 client connection.
     *
     * SOCKS5 protocol flow:
     * 1. Client sends greeting: VER(1) NMETHODS(1) METHODS(N)
     * 2. Server responds: VER(1) METHOD(1) -- we choose 0x00 (no auth)
     * 3. Client sends request: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR(var) DST.PORT(2)
     * 4. Server responds: VER(1) REP(1) RSV(1) ATYP(1) BND.ADDR(var) BND.PORT(2)
     * 5. Bidirectional data relay begins
     */
    private fun handleSocks5Client(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 30000 // 30 second timeout
            val input: InputStream = clientSocket.getInputStream()
            val output: OutputStream = clientSocket.getOutputStream()

            // --- SOCKS5 Handshake Phase ---

            // Read greeting: version + nmethods + methods
            val version = input.read()
            if (version != 0x05) {
                Log.w(TAG, "Invalid SOCKS version: $version, closing")
                clientSocket.close()
                return
            }

            val nMethods = input.read()
            if (nMethods <= 0 || nMethods > 255) {
                clientSocket.close()
                return
            }

            // Read and discard method list (we always use NO AUTH)
            val methods = ByteArray(nMethods)
            readFully(input, methods)

            // Respond: VER=5, METHOD=0x00 (NO AUTHENTICATION REQUIRED)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // --- SOCKS5 Request Phase ---

            // Read request header
            val reqVersion = input.read() // VER, should be 0x05
            val cmd = input.read()         // CMD: 0x01=CONNECT, 0x03=UDP ASSOCIATE
            val rsv = input.read()        // RSV, reserved
            val addrType = input.read()    // ATYP: 0x01=IPv4, 0x03=Domain, 0x04=IPv6

            if (reqVersion != 0x05) {
                clientSocket.close()
                return
            }

            // Parse target address
            var targetHost: String
            when (addrType) {
                0x01 -> {
                    // IPv4: 4 bytes
                    val addr = ByteArray(4)
                    readFully(input, addr)
                    targetHost = addr.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> {
                    // Domain: 1 byte length + domain bytes
                    val domainLen = input.read()
                    if (domainLen <= 0 || domainLen > 255) {
                        sendSocks5Error(output, 0x04) // Host unreachable
                        clientSocket.close()
                        return
                    }
                    val domain = ByteArray(domainLen)
                    readFully(input, domain)
                    targetHost = String(domain, Charsets.UTF_8)
                }
                0x04 -> {
                    // IPv6: 16 bytes
                    val addr = ByteArray(16)
                    readFully(input, addr)
                    targetHost = java.net.InetAddress.getByAddress(addr).hostAddress
                }
                else -> {
                    sendSocks5Error(output, 0x08) // Address type not supported
                    clientSocket.close()
                    return
                }
            }

            // Read target port (2 bytes, big-endian)
            val portHigh = input.read()
            val portLow = input.read()
            if (portHigh < 0 || portLow < 0) {
                clientSocket.close()
                return
            }
            val targetPort = (portHigh shl 8) or portLow

            Log.d(TAG, "SOCKS5 CONNECT request: $targetHost:$targetPort (cmd=$cmd)")

            if (cmd == 0x01) {
                // CONNECT command - establish Trojan connection to target
                handleSocks5Connect(clientSocket, input, output, targetHost, targetPort)
            } else if (cmd == 0x03) {
                // UDP ASSOCIATE - not supported
                sendSocks5Error(output, 0x07) // Command not supported
                clientSocket.close()
            } else {
                sendSocks5Error(output, 0x07) // Command not supported
                clientSocket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handle SOCKS5 client error", e)
            try {
                clientSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handle SOCKS5 CONNECT command: establish Trojan tunnel and relay data.
     */
    private fun handleSocks5Connect(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        targetHost: String,
        targetPort: Int
    ) {
        val trojanSocket = establishTrojanConnection(targetHost, targetPort)

        if (trojanSocket != null) {
            try {
                // Send SOCKS5 success response
                // VER(5) REP(0x00=success) RSV(0) ATYP(1=IPv4) BND.ADDR(0.0.0.0) BND.PORT(0)
                clientOutput.write(byteArrayOf(
                    0x05, 0x00, 0x00, 0x01,
                    0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00
                ))
                clientOutput.flush()

                val trojanInput: InputStream = trojanSocket.getInputStream()
                val trojanOutput: OutputStream = trojanSocket.getOutputStream()

                // Bidirectional relay: client <-> trojan server
                // Use two threads for full-duplex forwarding

                val relayActive = java.util.concurrent.atomic.AtomicBoolean(true)

                // Thread 1: client -> trojan
                val clientToTrojan = Thread {
                    try {
                        val buf = ByteArray(8192)
                        while (relayActive.get()) {
                            val n = clientInput.read(buf)
                            if (n <= 0) break
                            trojanOutput.write(buf, 0, n)
                            trojanOutput.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        relayActive.set(false)
                        try { trojanSocket.close() } catch (_: Exception) {}
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }

                // Thread 2: trojan -> client
                val trojanToClient = Thread {
                    try {
                        val buf = ByteArray(8192)
                        while (relayActive.get()) {
                            val n = trojanInput.read(buf)
                            if (n <= 0) break
                            clientOutput.write(buf, 0, n)
                            clientOutput.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        relayActive.set(false)
                        try { trojanSocket.close() } catch (_: Exception) {}
                        try { clientSocket.close() } catch (_: Exception) {}
                    }
                }

                clientToTrojan.start()
                trojanToClient.start()

                // Wait for either direction to finish
                clientToTrojan.join()
                // Give the other thread a moment to finish
                Thread.sleep(500)
                relayActive.set(false)

            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 relay error: $targetHost:$targetPort", e)
                try { trojanSocket.close() } catch (_: Exception) {}
                try { clientSocket.close() } catch (_: Exception) {}
            }
        } else {
            // Connection failed
            try {
                sendSocks5Error(clientOutput, 0x01) // General SOCKS server failure
                clientSocket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Establish a Trojan protocol connection to the remote server.
     *
     * Trojan protocol:
     * 1. TLS handshake with SNI
     * 2. Send: password + CRLF + target_address + CRLF
     * 3. Subsequent data is raw payload (bidirectional)
     *
     * Target address format (SOCKS5-like):
     * - ATYP 0x03 (domain): 1 byte len + domain bytes
     * - Port: 2 bytes big-endian
     */
    private fun establishTrojanConnection(targetHost: String, targetPort: Int): SSLSocket? {
        return try {
            // Create SSL context that trusts all certificates (self-signed server)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                null,
                arrayOf(TrustAllX509TrustManager()),
                SecureRandom()
            )

            // Create raw TCP socket
            val rawSocket = Socket()
            rawSocket.soTimeout = 15000 // 15 second connect timeout

            // Protect this socket from VPN routing to prevent loopback
            protect(rawSocket)

            rawSocket.connect(InetSocketAddress(serverHost, serverPort), 15000)
            rawSocket.soTimeout = 0 // Reset to blocking mode for data transfer

            // Wrap with TLS
            val tlsSocket = sslContext.socketFactory.createSocket(
                rawSocket, serverHost, serverPort, true
            ) as SSLSocket

            // Set SNI hostname
            val sslParams = tlsSocket.sslParameters
            sslParams.serverNames = listOf(SNIHostName(tlsSni))
            tlsSocket.sslParameters = sslParams

            // Perform TLS handshake
            tlsSocket.startHandshake()

            // Send Trojan protocol header
            val out = tlsSocket.outputStream

            // password + CRLF
            val passwordBytes = trojanPassword.toByteArray(Charsets.UTF_8)
            out.write(passwordBytes)
            out.write(0x0D) // CR
            out.write(0x0A) // LF

            // Target address: ATYP(0x03=domain) + length + domain + port(2 bytes) + CRLF
            out.write(0x03) // ATYP: domain name
            val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
            out.write(hostBytes.size) // domain length
            out.write(hostBytes) // domain
            out.write((targetPort shr 8) and 0xFF) // port high byte
            out.write(targetPort and 0xFF) // port low byte
            out.write(0x0D) // CR
            out.write(0x0A) // LF
            out.flush()

            Log.d(TAG, "Trojan connection established: $targetHost:$targetPort via $serverHost:$serverPort")
            tlsSocket
        } catch (e: Exception) {
            Log.e(TAG, "Trojan connection failed: $targetHost:$targetPort", e)
            null
        }
    }

    /**
     * Send a SOCKS5 error response to the client.
     */
    private fun sendSocks5Error(output: OutputStream, repCode: Byte) {
        try {
            output.write(byteArrayOf(
                0x05,       // VER
                repCode,    // REP (error code)
                0x00,       // RSV
                0x01,       // ATYP: IPv4
                0x00, 0x00, 0x00, 0x00,  // BND.ADDR: 0.0.0.0
                0x00, 0x00  // BND.PORT: 0
            ))
            output.flush()
        } catch (_: Exception) {}
    }

    /**
     * Read exactly `length` bytes from the input stream.
     * Throws IOException if the stream ends before all bytes are read.
     */
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n <= 0) throw java.io.IOException("Unexpected end of stream")
            offset += n
        }
    }

    /**
     * Stop the proxy server and clean up all resources.
     */
    private fun stopProxy() {
        isActive.set(false)
        isRunning = false

        // Close server socket (will unblock accept())
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_DISCONNECTED"))
        Log.i(TAG, "SOCKS5 proxy stopped")
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
                "Trojan SOCKS5 Proxy",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SOCKS5 proxy service with Trojan protocol forwarding"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
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
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                disconnectPendingIntent
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    /**
     * Trust-all X509TrustManager for self-signed TLS certificates.
     * WARNING: This accepts all certificates. In production, pin the server certificate.
     */
    private class TrustAllX509TrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
}
