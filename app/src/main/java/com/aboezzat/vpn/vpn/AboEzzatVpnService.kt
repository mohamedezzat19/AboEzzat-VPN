package com.aboezzat.vpn.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.ResultReceiver
import com.aboezzat.vpn.proxy.ProxyConnectionTester
import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import java.net.Socket
import kotlin.concurrent.thread

/**
 * VPN service entry point reserved for the real TUN + packet-forwarding engine.
 *
 * It deliberately does not call Builder.establish() yet. Establishing a TUN
 * interface without a functioning packet loop would black-hole device traffic.
 */
class AboEzzatVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_TEST_UPSTREAM_PROXY) return START_NOT_STICKY

        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val profile = runCatching {
            ProxyProfile(
                name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
                port = intent.getIntExtra(EXTRA_PORT, 0),
                protocol = ProxyProtocol.valueOf(
                    intent.getStringExtra(EXTRA_PROTOCOL) ?: ProxyProtocol.HTTP_CONNECT.name
                ),
                username = intent.getStringExtra(EXTRA_USERNAME),
                password = intent.getStringExtra(EXTRA_PASSWORD),
                remoteDns = intent.getBooleanExtra(EXTRA_REMOTE_DNS, true)
            )
        }.getOrNull()

        if (profile == null) {
            receiver?.send(RESULT_FAILED, Bundle().apply {
                putString(EXTRA_MESSAGE, "Invalid proxy profile.")
                putLong(EXTRA_LATENCY, 0L)
            })
            stopSelf(startId)
            return START_NOT_STICKY
        }

        thread(name = "upstream-proxy-preflight") {
            val result = ProxyConnectionTester.test(profile, ::protectUpstreamSocket)
            receiver?.send(
                if (result.success) RESULT_OK else RESULT_FAILED,
                Bundle().apply {
                    putBoolean(EXTRA_SUCCESS, result.success)
                    putString(EXTRA_MESSAGE, result.message)
                    putLong(EXTRA_LATENCY, result.latencyMs)
                }
            )
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Protects an outbound socket before connect() so it does not re-enter
     * this app's TUN interface once a real VPN tunnel is active.
     */
    fun protectUpstreamSocket(socket: Socket): Boolean = protect(socket)

    companion object {
        const val ACTION_TEST_UPSTREAM_PROXY = "com.aboezzat.vpn.action.TEST_UPSTREAM_PROXY"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val EXTRA_NAME = "profile_name"
        const val EXTRA_HOST = "profile_host"
        const val EXTRA_PORT = "profile_port"
        const val EXTRA_PROTOCOL = "profile_protocol"
        const val EXTRA_USERNAME = "profile_username"
        const val EXTRA_PASSWORD = "profile_password"
        const val EXTRA_REMOTE_DNS = "profile_remote_dns"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LATENCY = "latency_ms"
        const val RESULT_OK = 1
        const val RESULT_FAILED = 0
    }
}
