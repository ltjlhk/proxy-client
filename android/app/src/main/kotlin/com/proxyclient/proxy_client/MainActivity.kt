package com.proxyclient.proxy_client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val VPN_CHANNEL = "com.proxyclient.proxy_client/vpn"
    }

    private var vpnEventSink: EventChannel.EventSink? = null
    private lateinit var vpnStatusReceiver: BroadcastReceiver
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>

    // Store connection params for use after permission granted
    private var pendingHost = "47.80.241.156"
    private var pendingPort = 8443
    private var pendingPassword = "proxy123456"
    private var pendingSni = "proxy.local"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the VPN permission result launcher
        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnService(pendingHost, pendingPort, pendingPassword, pendingSni)
            } else {
                vpnEventSink?.success(mapOf(
                    "type" to "error",
                    "message" to "VPN permission denied by user"
                ))
            }
        }

        vpnStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.proxyclient.proxy_client.VPN_CONNECTED" -> {
                        vpnEventSink?.success(mapOf(
                            "type" to "connected",
                            "message" to "VPN connected successfully"
                        ))
                    }
                    "com.proxyclient.proxy_client.VPN_DISCONNECTED" -> {
                        vpnEventSink?.success(mapOf(
                            "type" to "disconnected",
                            "message" to "VPN disconnected"
                        ))
                    }
                    "com.proxyclient.proxy_client.VPN_ERROR" -> {
                        val errorMsg = intent.getStringExtra("errorMessage") ?: "Unknown error"
                        vpnEventSink?.success(mapOf(
                            "type" to "error",
                            "message" to errorMsg
                        ))
                    }
                    "com.proxyclient.proxy_client.VPN_TRAFFIC" -> {
                        vpnEventSink?.success(mapOf(
                            "type" to "traffic",
                            "upload" to (intent.getLongExtra("upload", 0)),
                            "download" to (intent.getLongExtra("download", 0)),
                            "uploadSpeed" to (intent.getLongExtra("uploadSpeed", 0)),
                            "downloadSpeed" to (intent.getLongExtra("downloadSpeed", 0))
                        ))
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.proxyclient.proxy_client.VPN_CONNECTED")
            addAction("com.proxyclient.proxy_client.VPN_DISCONNECTED")
            addAction("com.proxyclient.proxy_client.VPN_ERROR")
            addAction("com.proxyclient.proxy_client.VPN_TRAFFIC")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStatusReceiver, filter)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    vpnEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    vpnEventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "connect" -> {
                        val host = call.argument<String>("host") ?: "47.80.241.156"
                        val port = call.argument<Int>("port") ?: 8443
                        val password = call.argument<String>("password") ?: "proxy123456"
                        val sni = call.argument<String>("sni") ?: "proxy.local"
                        pendingHost = host
                        pendingPort = port
                        pendingPassword = password
                        pendingSni = sni
                        connectVpn(host, port, password, sni)
                        result.success(true)
                    }
                    "disconnect" -> {
                        disconnectVpn()
                        result.success(true)
                    }
                    "isVpnRunning" -> {
                        result.success(TrojanVpnService.isRunning)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun connectVpn(host: String, port: Int, password: String, sni: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // Need user permission - launch the VPN permission dialog
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already have permission - start directly
            startVpnService(host, port, password, sni)
        }
    }

    private fun disconnectVpn() {
        val intent = Intent(this, TrojanVpnService::class.java).apply {
            action = TrojanVpnService.ACTION_DISCONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startVpnService(host: String, port: Int, password: String, sni: String) {
        val intent = Intent(this, TrojanVpnService::class.java).apply {
            action = TrojanVpnService.ACTION_CONNECT
            putExtra("serverHost", host)
            putExtra("serverPort", port)
            putExtra("trojanPassword", password)
            putExtra("tlsSni", sni)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(vpnStatusReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
        super.onDestroy()
    }
}
