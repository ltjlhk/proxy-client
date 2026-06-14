package com.proxyclient.proxy_client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        const val VPN_CHANNEL = "com.proxyclient.proxy_client/vpn"
        const val VPN_METHOD_CHANNEL = "com.proxyclient.proxy_client/vpn_method"
        const val VPN_REQUEST_CODE = 1001
    }

    private var vpnEventSink: EventChannel.EventSink? = null
    private lateinit var vpnStatusReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("com.proxyclient.proxy_client.VPN_CONNECTED")
            addAction("com.proxyclient.proxy_client.VPN_DISCONNECTED")
        }
        registerReceiver(vpnStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
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

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "connectVpn" -> {
                        val host = call.argument<String>("host") ?: "47.80.241.156"
                        val port = call.argument<Int>("port") ?: 7890
                        val mode = call.argument<String>("mode") ?: "global"
                        connectVpn(host, port, mode)
                        result.success(true)
                    }
                    "disconnectVpn" -> {
                        disconnectVpn()
                        result.success(true)
                    }
                    "isVpnRunning" -> {
                        result.success(ProxyVpnService.isRunning)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun connectVpn(host: String, port: Int, mode: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            startVpnService(host, port, mode)
        }
    }

    private fun disconnectVpn() {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_DISCONNECT
        }
        startService(intent)
    }

    private fun startVpnService(host: String, port: Int, mode: String) {
        val intent = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_CONNECT
            putExtra("proxyHost", host)
            putExtra("proxyPort", port)
            putExtra("proxyMode", mode)
        }
        startService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService("47.80.241.156", 7890, "global")
            } else {
                vpnEventSink?.success(mapOf(
                    "type" to "error",
                    "message" to "VPN permission denied"
                ))
            }
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
