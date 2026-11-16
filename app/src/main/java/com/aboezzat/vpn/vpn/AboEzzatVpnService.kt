package com.aboezzat.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import com.aboezzat.vpn.proxy.ProxyConnectionTester
import com.aboezzat.vpn.proxy.ProxyTestRequestParser
import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.Socks5UdpAssociateTester
import com.aboezzat.vpn.data.AppRouteStore
import com.aboezzat.vpn.core.RouteMode
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.DatagramSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns both the Android TUN descriptor and the native tun2proxy run lifetime.
 * The TUN is closed only after the blocking native run function has returned.
 */
class AboEzzatVpnService : VpnService() {

    private val testExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "upstream-proxy-preflight")
    }
    private val engineExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aboezzat-native-tunnel")
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var nativeBridgeBound = false
    @Volatile private var destroyed = false
    @Volatile private var stopRequested = false
    @Volatile private var latestStartId = 0
    @Volatile private var activeReceiver: ResultReceiver? = null

    private lateinit var tunnelRunner: NativeTunnelRunner
    @Volatile private var directBypassCount = 0

    override fun onCreate() {
        super.onCreate()
        nativeBridgeBound = try {
            NativeSocketProtectorBridge.nativeBindService(this)
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        tunnelRunner = NativeTunnelRunner(
            executor = engineExecutor,
            runEngine = NativeSocketProtectorBridge::nativeRunEngine,
            stopEngine = NativeSocketProtectorBridge::nativeStopEngine,
            onFinished = ::onNativeTunnelFinished
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        return when (intent?.action) {
            ACTION_TEST_UPSTREAM_PROXY -> {
                runProxyTest(intent, startId)
                START_NOT_STICKY
            }
            ACTION_TEST_UPSTREAM_UDP -> {
                runUdpProxyTest(intent, startId)
                START_NOT_STICKY
            }
            ACTION_START_TUNNEL -> {
                startTunnel(intent, startId)
                START_NOT_STICKY
            }
            ACTION_STOP_TUNNEL -> {
                stopTunnel(intent, startId)
                START_NOT_STICKY
            }
            else -> {
                if (!tunnelRunner.isActive()) stopSelfResult(startId)
                START_NOT_STICKY
            }
        }
    }

    private fun runProxyTest(intent: Intent, startId: Int) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val profile = parseProfile(intent)

        testExecutor.execute {
            try {
                val result = if (profile == null) {
                    com.aboezzat.vpn.proxy.ProxyTestResult(false, "Invalid proxy profile.", 0L)
                } else {
                    ProxyConnectionTester.test(
                        profile,
                        protectSocket = if (::tunnelRunner.isInitialized && tunnelRunner.isActive()) {
                            ::protectUpstreamSocket
                        } else {
                            null
                        }
                    )
                }
                receiver?.send(
                    if (result.success) RESULT_OK else RESULT_FAILED,
                    Bundle().apply {
                        putBoolean(EXTRA_SUCCESS, result.success)
                        putString(EXTRA_MESSAGE, result.message)
                        putLong(EXTRA_LATENCY, result.latencyMs)
                    }
                )
            } catch (error: Exception) {
                receiver?.send(
                    RESULT_FAILED,
                    Bundle().apply {
                        putBoolean(EXTRA_SUCCESS, false)
                        putString(
                            EXTRA_MESSAGE,
                            error.message?.take(140) ?: "Proxy test could not be completed."
                        )
                        putLong(EXTRA_LATENCY, 0L)
                    }
                )
            } finally {
                if (!tunnelRunner.isActive()) stopSelfResult(startId)
            }
        }
    }

    private fun runUdpProxyTest(intent: Intent, startId: Int) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        val profile = parseProfile(intent)
        testExecutor.execute {
            try {
                val result = if (profile == null) {
                    com.aboezzat.vpn.proxy.ProxyUdpTestResult(false, "Invalid proxy profile.")
                } else {
                    Socks5UdpAssociateTester.test(
                        profile,
                        protectSocket = if (::tunnelRunner.isInitialized && tunnelRunner.isActive()) {
                            ::protectUpstreamSocket
                        } else {
                            null
                        },
                        protectDatagramSocket = if (::tunnelRunner.isInitialized && tunnelRunner.isActive()) {
                            ::protectUpstreamDatagramSocket
                        } else {
                            null
                        }
                    )
                }
                receiver?.send(
                    if (result.supported) RESULT_OK else RESULT_FAILED,
                    Bundle().apply {
                        putBoolean(EXTRA_SUCCESS, result.supported)
                        putString(EXTRA_MESSAGE, result.message)
                        putString(EXTRA_UDP_RELAY_ADDRESS, result.relayAddress)
                        putInt(EXTRA_UDP_RELAY_PORT, result.relayPort ?: 0)
                    }
                )
            } catch (error: Exception) {
                receiver?.send(RESULT_FAILED, Bundle().apply {
                    putBoolean(EXTRA_SUCCESS, false)
                    putString(EXTRA_MESSAGE, error.message ?: "UDP diagnostic failed.")
                })
            } finally {
                if (!tunnelRunner.isActive()) stopSelfResult(startId)
            }
        }
    }

    private fun startTunnel(intent: Intent, startId: Int) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        if (tunnelRunner.isActive()) {
            receiver?.send(RESULT_ALREADY_ACTIVE, Bundle().apply {
                putBoolean(EXTRA_SUCCESS, true)
                putString(EXTRA_MESSAGE, "A tunnel session is already active.")
            })
            return
        }
        if (!nativeBridgeBound) {
            failStart(receiver, startId, "Native tunnel engine could not be loaded.")
            return
        }
        if (VpnService.prepare(this) != null) {
            failStart(receiver, startId, "Android VPN permission is required.")
            return
        }
        try {
            startTunnelForeground()
        } catch (error: Exception) {
            failStart(
                receiver,
                startId,
                error.message ?: "Android did not allow the VPN foreground service to start."
            )
            return
        }
        val profile = parseProfile(intent)
        if (profile == null) {
            failStart(receiver, startId, "Invalid proxy profile.")
            return
        }

        // Resolve the proxy endpoint before creating the TUN route. The native
        // engine receives a numeric address, preventing a DNS bootstrap loop.
        val resolvedHost = resolveProxyHost(profile.host)
        if (resolvedHost == null) {
            failStart(receiver, startId, "Could not resolve the proxy server address.")
            return
        }

        val tun = try {
            establishTunInterface()
        } catch (error: Exception) {
            null
        }
        if (tun == null) {
            failStart(receiver, startId, "Android could not establish the TUN interface.")
            return
        }

        val command = Tun2ProxyCommandBuilder.build(
            profile.copy(host = resolvedHost),
            tunFd = tun.fd,
            mtu = TUN_MTU
        )
        if (command == null) {
            runCatching { tun.close() }
            failStart(receiver, startId, "The selected proxy profile is not valid for the tunnel.")
            return
        }

        val nativeArmed = try {
            NativeSocketProtectorBridge.nativeArmEngineStart()
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        if (!nativeArmed) {
            runCatching { tun.close() }
            failStart(receiver, startId, "The native tunnel engine is already starting or unavailable.")
            return
        }

        activeReceiver = receiver
        stopRequested = false
        val accepted = tunnelRunner.start(
            cliArgs = command,
            mtu = TUN_MTU,
            packetInformation = false,
            closeTunDescriptor = { tun.close() }
        )
        if (!accepted) {
            activeReceiver = null
            tunnelRunning = false
            runCatching { NativeSocketProtectorBridge.nativeCancelEngineStart() }
            runCatching { tun.close() }
            failStart(receiver, startId, "The native tunnel worker is not available.")
            return
        }

        // Only report the tunnel as running after the native worker accepted the session.
        tunnelRunning = true
        receiver?.send(
            RESULT_STARTED,
            Bundle().apply {
                putBoolean(EXTRA_SUCCESS, true)
                putInt(EXTRA_DIRECT_BYPASS_COUNT, directBypassCount)
                putString(EXTRA_MESSAGE, "Tunnel worker started via ${profile.name}. Direct bypass active for $directBypassCount app(s); other saved route modes are not separately enforced yet.")
            }
        )
        updateTunnelNotification("Tunnel active via ${profile.name} · $directBypassCount direct bypass")
    }

    private fun stopTunnel(intent: Intent, startId: Int) {
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RESULT_RECEIVER)
        if (!tunnelRunner.isActive()) {
            receiver?.send(RESULT_STOPPED, Bundle().apply {
                putBoolean(EXTRA_SUCCESS, true)
                putString(EXTRA_MESSAGE, "Tunnel is already stopped.")
            })
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return
        }

        stopRequested = true
        updateTunnelNotification("Stopping the tunnel and releasing resources")
        val accepted = tunnelRunner.stop()
        receiver?.send(
            if (accepted) RESULT_STOPPING else RESULT_STOP_FAILED,
            Bundle().apply {
                putBoolean(EXTRA_SUCCESS, accepted)
                putString(
                    EXTRA_MESSAGE,
                    if (accepted) "Tunnel stop requested. Waiting for native cleanup."
                    else "The native engine did not accept the stop request."
                )
            }
        )
    }

    private fun onNativeTunnelFinished(result: Int) {
        val receiver = activeReceiver
        val wasRequestedStop = stopRequested
        val successfulStop = result == 0 || (result == -13 && wasRequestedStop)
        activeReceiver = null
        stopRequested = false
        tunnelRunning = false

        if (destroyed) unbindNativeBridge()

        mainHandler.post {
            if (!destroyed) {
                val message = when {
                    successfulStop && wasRequestedStop -> "Tunnel stopped and native resources released."
                    successfulStop -> "Tunnel engine exited."
                    else -> "Native tunnel stopped with error code $result."
                }
                receiver?.send(
                    if (successfulStop) RESULT_STOPPED else RESULT_FAILED,
                    Bundle().apply {
                        putBoolean(EXTRA_SUCCESS, successfulStop)
                        putString(EXTRA_MESSAGE, message)
                    }
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(latestStartId)
            }
        }
    }

    private fun failStart(receiver: ResultReceiver?, startId: Int, message: String) {
        receiver?.send(RESULT_FAILED, Bundle().apply {
            putBoolean(EXTRA_SUCCESS, false)
            putString(EXTRA_MESSAGE, message)
        })
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun establishTunInterface(): ParcelFileDescriptor? {
        val routes = AppRouteStore(this).load()
        val directCandidates = routes.filterValues { it == RouteMode.DIRECT }.keys
        val safeDirectPackages = directCandidates.filter { candidatePackage ->
            if (candidatePackage == packageName) return@filter false
            runCatching {
                val uid = packageManager.getApplicationInfo(candidatePackage, 0).uid
                val uidPackages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
                uidPackages.isNotEmpty() && uidPackages.all { routes[it] == RouteMode.DIRECT }
            }.getOrDefault(false)
        }

        val builder = Builder()
            .setSession("AboEzzat VPN")
            .setMtu(TUN_MTU)
            .addAddress(InetAddress.getByName(IPV4_TUN_ADDRESS), 32)
            .addRoute(InetAddress.getByName("0.0.0.0"), 0)
            .addDnsServer(InetAddress.getByName(IPV4_DNS_ADDRESS))
            .addAddress(InetAddress.getByName(IPV6_TUN_ADDRESS), 128)
            .addRoute(InetAddress.getByName("::"), 0)
            .addDnsServer(InetAddress.getByName(IPV6_DNS_ADDRESS))
            .setBlocking(false)

        var applied = 0
        safeDirectPackages.forEach { candidatePackage ->
            runCatching {
                builder.addDisallowedApplication(candidatePackage)
                applied++
            }
        }
        directBypassCount = applied
        return builder.establish()
    }

    private fun resolveProxyHost(host: String): String? {
        val normalized = host.removePrefix("[").removeSuffix("]")
        if (normalized.contains(':') || normalized.matches(Regex("[0-9.]+"))) {
            return normalized
        }

        return try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork
            val addresses = if (network != null) {
                network.getAllByName(normalized)
            } else {
                InetAddress.getAllByName(normalized)
            }
            addresses.firstOrNull { it is Inet4Address }?.hostAddress
                ?: addresses.firstOrNull()?.hostAddress?.substringBefore('%')
        } catch (_: Exception) {
            null
        }
    }

    private fun parseProfile(intent: Intent): ProxyProfile? =
        ProxyTestRequestParser.parse(
            name = intent.getStringExtra(EXTRA_NAME),
            host = intent.getStringExtra(EXTRA_HOST),
            port = intent.getIntExtra(EXTRA_PORT, 0),
            protocol = intent.getStringExtra(EXTRA_PROTOCOL),
            username = intent.getStringExtra(EXTRA_USERNAME),
            password = intent.getStringExtra(EXTRA_PASSWORD),
            remoteDns = intent.getBooleanExtra(EXTRA_REMOTE_DNS, true)
        )

    private fun updateTunnelNotification(message: String) {
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("AboEzzat VPN")
            .setContentText(message)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun startTunnelForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "AboEzzat VPN",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows the active VPN tunnel state."
                }
            )
        }
        val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("AboEzzat VPN")
            .setContentText("Starting the upstream proxy tunnel")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Synchronized
    private fun unbindNativeBridge() {
        if (!nativeBridgeBound) return
        try {
            NativeSocketProtectorBridge.nativeUnbindService()
        } catch (_: UnsatisfiedLinkError) {
            // A missing native library must not block service teardown.
        }
        nativeBridgeBound = false
    }

    override fun onRevoke() {
        if (::tunnelRunner.isInitialized && tunnelRunner.isActive()) {
            stopRequested = true
            tunnelRunner.stop()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        testExecutor.shutdownNow()
        if (::tunnelRunner.isInitialized && tunnelRunner.isActive()) {
            stopRequested = true
            tunnelRunner.stop()
            engineExecutor.shutdown()
            // The native global reference is released by onNativeTunnelFinished
            // after the native call returns and the TUN descriptor is closed.
        } else {
            engineExecutor.shutdown()
            unbindNativeBridge()
        }
        super.onDestroy()
    }

    /** Receive native tunnel byte counters from the Rust engine. */
    @androidx.annotation.Keep
    fun onNativeTrafficStatus(txBytes: Long, rxBytes: Long) {
        activeReceiver?.send(
            RESULT_TRAFFIC_UPDATE,
            Bundle().apply {
                putLong(EXTRA_TX_BYTES, txBytes)
                putLong(EXTRA_RX_BYTES, rxBytes)
            }
        )
    }

    /** Protect outbound diagnostic sockets before connect/send. */
    fun protectUpstreamSocket(socket: Socket): Boolean = protect(socket)

    fun protectUpstreamDatagramSocket(socket: DatagramSocket): Boolean = protect(socket) // Keep diagnostic UDP outside the TUN

    /** Synchronous JNI callback. False is a hard failure for native callers. */
    @androidx.annotation.Keep
    fun protectNativeSocket(fd: Int): Boolean =
        NativeSocketProtection.protect(fd, ::protect)

    companion object {
        @Volatile private var tunnelRunning = false
        fun isTunnelRunning(): Boolean = tunnelRunning

        const val ACTION_TEST_UPSTREAM_PROXY = "com.aboezzat.vpn.action.TEST_UPSTREAM_PROXY"
        const val ACTION_TEST_UPSTREAM_UDP = "com.aboezzat.vpn.action.TEST_UPSTREAM_UDP"
        const val ACTION_START_TUNNEL = "com.aboezzat.vpn.action.START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "com.aboezzat.vpn.action.STOP_TUNNEL"

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
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_UDP_RELAY_ADDRESS = "udp_relay_address"
        const val EXTRA_UDP_RELAY_PORT = "udp_relay_port"
        const val EXTRA_DIRECT_BYPASS_COUNT = "direct_bypass_count"

        const val RESULT_OK = 1
        const val RESULT_FAILED = 0
        const val RESULT_STARTED = 2
        const val RESULT_STOPPING = 3
        const val RESULT_STOPPED = 4
        const val RESULT_STOP_FAILED = 5
        const val RESULT_ALREADY_ACTIVE = 6
        const val RESULT_TRAFFIC_UPDATE = 7

        private const val TUN_MTU = 1500
        private const val IPV4_TUN_ADDRESS = "10.77.0.2"
        private const val IPV4_DNS_ADDRESS = "10.77.0.1"
        private const val IPV6_TUN_ADDRESS = "fd77:ab0e::2"
        private const val IPV6_DNS_ADDRESS = "fd77:ab0e::1"
        private const val NOTIFICATION_CHANNEL = "aboezzat_vpn_tunnel"
        private const val NOTIFICATION_ID = 7301
    }
}
