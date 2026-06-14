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
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProxyVpnService : VpnService() {

    companion object {
        const val TAG = "ProxyVpnService"
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_ROUTE = "0.0.0.0"
        const val VPN_DNS = "8.8.8.8"
        const val NOTIFICATION_CHANNEL_ID = "proxy_vpn_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_CONNECT = "com.proxyclient.proxy_client.CONNECT"
        const val ACTION_DISCONNECT = "com.proxyclient.proxy_client.DISCONNECT"

        var isRunning = false
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var executorService: ExecutorService? = null
    private val isActive = AtomicBoolean(false)

    private var proxyHost: String = "47.80.241.156"
    private var proxyPort: Int = 7890
    private var proxyMode: String = "global"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                proxyHost = intent.getStringExtra("proxyHost") ?: proxyHost
                proxyPort = intent.getIntExtra("proxyPort", proxyPort)
                proxyMode = intent.getStringExtra("proxyMode") ?: proxyMode
                startVpn()
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
            else -> {
                if (!isRunning) {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        try {
            val builder = Builder()
                .setSession("Proxy Client")
                .addAddress(VPN_ADDRESS, 24)
                .addDnsServer(VPN_DNS)
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
                startVpnThreads()
                sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_CONNECTED"))
                Log.i(TAG, "VPN started successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        isActive.set(false)
        isRunning = false

        executorService?.shutdownNow()
        executorService = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null

        stopForeground(true)
        stopSelf()

        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_DISCONNECTED"))
        Log.i(TAG, "VPN stopped")
    }

    private fun startVpnThreads() {
        executorService = Executors.newFixedThreadPool(2)

        executorService?.execute {
            handleVpnInput()
        }

        executorService?.execute {
            handleVpnOutput()
        }
    }

    private fun handleVpnInput() {
        vpnInterface?.let { vpn ->
            val inputStream = FileInputStream(vpn.fileDescriptor)
            val buffer = ByteBuffer.allocate(32767)

            while (isActive.get()) {
                try {
                    val length = inputStream.read(buffer.array())
                    if (length > 0) {
                        buffer.limit(length)
                        processPacket(buffer)
                        buffer.clear()
                    }
                } catch (e: Exception) {
                    if (isActive.get()) {
                        Log.e(TAG, "Error reading from VPN", e)
                    }
                }
            }
        }
    }

    private fun handleVpnOutput() {
        vpnInterface?.let { vpn ->
            val outputStream = FileOutputStream(vpn.fileDescriptor)

            while (isActive.get()) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun processPacket(buffer: ByteBuffer) {
        if (buffer.limit() < 20) return

        val version = buffer.get(0).toInt() shr 4 and 0x0F
        if (version != 4 && version != 6) return

        val protocol = if (version == 4) {
            buffer.get(9).toInt() and 0xFF
        } else {
            buffer.get(6).toInt() and 0xFF
        }

        when (protocol) {
            6 -> handleTcpPacket(buffer)
            17 -> handleUdpPacket(buffer)
        }
    }

    private fun handleTcpPacket(buffer: ByteBuffer) {
        // TCP packet handling - route through SOCKS5 proxy
        try {
            val socket = Socket()
            socket.bind(InetSocketAddress(VPN_ADDRESS, 0))
            protect(socket)
            socket.connect(InetSocketAddress(proxyHost, proxyPort), 10000)

            // SOCKS5 handshake
            val handshake = byteArrayOf(0x05, 0x01, 0x00)
            socket.getOutputStream().write(handshake)

            val response = ByteArray(2)
            socket.getInputStream().read(response)

            if (response[0] == 0x05.toByte() && response[1] == 0x00.toByte()) {
                // SOCKS5 connection request
                val request = buildSocks5Request(buffer)
                socket.getOutputStream().write(request)

                val connectResponse = ByteArray(10)
                socket.getInputStream().read(connectResponse)
            }

            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "TCP proxy error", e)
        }
    }

    private fun handleUdpPacket(buffer: ByteBuffer) {
        // UDP packet handling
        try {
            val channel = DatagramChannel.open()
            protect(channel.socket())
            channel.connect(InetSocketAddress(proxyHost, proxyPort))
            channel.close()
        } catch (e: Exception) {
            Log.e(TAG, "UDP proxy error", e)
        }
    }

    private fun buildSocks5Request(buffer: ByteBuffer): ByteArray {
        // Build SOCKS5 CONNECT request
        val request = ByteArray(10)
        request[0] = 0x05 // SOCKS5
        request[1] = 0x01 // CONNECT
        request[2] = 0x00 // Reserved
        request[3] = 0x01 // IPv4

        // Destination IP (placeholder - should extract from packet)
        request[4] = 0x00
        request[5] = 0x00
        request[6] = 0x00
        request[7] = 0x00

        // Destination port (placeholder)
        request[8] = 0x00
        request[9] = 0x50

        return request
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Proxy VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Proxy Client VPN connection"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, ProxyVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Proxy Client")
            .setContentText("VPN 连接已建立")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "断开", disconnectPendingIntent)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
