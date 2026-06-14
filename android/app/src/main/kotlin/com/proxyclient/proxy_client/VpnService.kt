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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * TrojanVpnService - Android VpnService that manages sing-box as an embedded proxy engine.
 *
 * Architecture:
 * 1. VpnService creates a TUN interface to route all traffic
 * 2. sing-box binary is extracted from assets and executed as an external process
 * 3. sing-box is configured with tun inbound + trojan outbound
 * 4. sing-box handles all packet processing, TCP state management, and protocol conversion
 *
 * This replaces the previous approach of manually parsing IP packets in Kotlin,
 * which had fundamental issues (no TCP state machine, no proper IP packet reconstruction,
 * no connection tracking, etc.).
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
    private var singBoxProcess: Process? = null
    private val isActive = java.util.concurrent.atomic.AtomicBoolean(false)

    // Server configuration (defaults, overridden by intent extras)
    private var serverHost = "47.80.241.156"
    private var serverPort = 8443
    private var trojanPassword = "proxy123456"
    private var tlsSni = "proxy.local"

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
                startVpn()
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    /**
     * Start the VPN service:
     * 1. Create TUN interface via VpnService.Builder
     * 2. Extract sing-box binary from assets if needed
     * 3. Generate sing-box JSON configuration
     * 4. Launch sing-box as an external process
     */
    private fun startVpn() {
        if (isRunning) return

        try {
            // Create VPN interface
            // sing-box with tun inbound and auto_route will manage routing,
            // but we still create the VpnService TUN to satisfy Android's VPN framework
            // and to protect sing-box sockets from routing loops.
            val builder = Builder()
                .setSession("Trojan Proxy")
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .setMtu(1500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning = true
                isActive.set(true)
                startForeground(NOTIFICATION_ID, createNotification())

                // Launch sing-box in a background thread
                Thread({ runSingBox() }, "SingBox-Runner").start()

                sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_CONNECTED"))
                Log.i(TAG, "VPN started, launching sing-box - server: $serverHost:$serverPort")
            } else {
                sendErrorBroadcast("Failed to establish VPN interface")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            sendErrorBroadcast(e.message ?: "Failed to start VPN")
            stopVpn()
        }
    }

    /**
     * Extract sing-box binary from assets, generate config, and execute.
     */
    private fun runSingBox() {
        try {
            // Prepare sing-box directory in app's internal storage
            val singBoxDir = File(filesDir, "sing-box")
            singBoxDir.mkdirs()
            val singBoxBin = File(singBoxDir, "sing-box")

            // Extract binary from assets if not already present
            if (!singBoxBin.exists()) {
                Log.i(TAG, "Extracting sing-box binary from assets...")
                extractAsset("sing-box", singBoxBin)
                singBoxBin.setExecutable(true)
                Log.i(TAG, "sing-box binary extracted: ${singBoxBin.absolutePath}")
            }

            // Generate sing-box configuration file
            val configFile = File(singBoxDir, "config.json")
            val config = generateConfig()
            configFile.writeText(config)
            Log.i(TAG, "sing-box config written to: ${configFile.absolutePath}")

            // Build command: sing-box run -c config.json
            val cmd = arrayOf(
                singBoxBin.absolutePath,
                "run",
                "-c", configFile.absolutePath
            )

            Log.i(TAG, "Launching sing-box: ${cmd.joinToString(" ")}")

            val processBuilder = ProcessBuilder(*cmd)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment()["HOME"] = singBoxDir.absolutePath

            singBoxProcess = processBuilder.start()

            // Consume stdout/stderr for logging
            val reader = singBoxProcess!!.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "sing-box: $line")
            }

            val exitCode = singBoxProcess!!.waitFor()
            Log.i(TAG, "sing-box exited with code: $exitCode")

            if (isActive.get() && exitCode != 0) {
                sendErrorBroadcast("sing-box exited with code $exitCode")
                stopVpn()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run sing-box", e)
            if (isActive.get()) {
                sendErrorBroadcast(e.message ?: "Failed to run sing-box")
                stopVpn()
            }
        }
    }

    /**
     * Extract a file from the APK assets to the given destination File.
     */
    private fun extractAsset(assetName: String, destFile: File) {
        val inputStream: InputStream = assets.open(assetName)
        val outputStream = FileOutputStream(destFile)
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
        outputStream.close()
        inputStream.close()
    }

    /**
     * Generate sing-box JSON configuration.
     *
     * Uses:
     * - tun inbound: creates its own TUN interface, auto_route for system routing
     * - trojan outbound: connects to the remote Trojan server
     * - direct outbound: for bypass rules if needed
     */
    private fun generateConfig(): String {
        return """{
    "log": {
        "level": "warn",
        "timestamp": true
    },
    "inbounds": [
        {
            "type": "tun",
            "tag": "tun-in",
            "inet4_address": "172.19.0.1/30",
            "auto_route": true,
            "strict_route": true,
            "stack": "system",
            "sniff": true,
            "sniff_override_destination": false
        }
    ],
    "outbounds": [
        {
            "type": "trojan",
            "tag": "trojan-out",
            "server": "$serverHost",
            "server_port": $serverPort,
            "password": "$trojanPassword",
            "tls": {
                "enabled": true,
                "server_name": "$tlsSni",
                "insecure": true
            }
        },
        {
            "type": "direct",
            "tag": "direct"
        },
        {
            "type": "block",
            "tag": "block"
        },
        {
            "type": "dns",
            "tag": "dns-out"
        }
    ],
    "route": {
        "auto_detect_interface": true,
        "rules": [
            {
                "protocol": "dns",
                "outbound": "dns-out"
            }
        ],
        "final": "trojan-out"
    },
    "dns": {
        "servers": [
            {
                "tag": "remote",
                "address": "8.8.8.8",
                "detour": "trojan-out"
            },
            {
                "tag": "local",
                "address": "223.5.5.5",
                "detour": "direct"
            }
        ],
        "rules": [
            {
                "outbound": "any",
                "server": "local"
            }
        ],
        "final": "remote",
        "strategy": "prefer_ipv4"
    }
}"""
    }

    /**
     * Stop the VPN service and clean up all resources.
     */
    private fun stopVpn() {
        isActive.set(false)
        isRunning = false

        // Terminate sing-box process
        singBoxProcess?.destroy()
        singBoxProcess = null

        // Close VPN interface
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        sendBroadcast(Intent("com.proxyclient.proxy_client.VPN_DISCONNECTED"))
        Log.i(TAG, "VPN stopped")
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
                description = "Trojan Proxy VPN connection via sing-box"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
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
            .setContentText("Trojan VPN (sing-box) - $serverHost:$serverPort")
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
}
