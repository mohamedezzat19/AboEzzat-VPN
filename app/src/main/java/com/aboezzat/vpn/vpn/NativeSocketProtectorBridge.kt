package com.aboezzat.vpn.vpn

import androidx.annotation.Keep

/**
 * JNI bridge for the future patched native tunnel engine.
 *
 * Loading this library only installs the callback bridge; it does not create
 * a TUN or start a VPN. The engine may call abz_protect_socket_fd only while
 * this service is bound.
 */
@Keep
internal object NativeSocketProtectorBridge {
    init {
        System.loadLibrary("abz_protector")
    }

    @JvmStatic
    @Keep
    external fun nativeBindService(service: AboEzzatVpnService): Boolean

    @JvmStatic
    @Keep
    external fun nativeUnbindService()

    @JvmStatic
    @Keep
    external fun nativeProtectSocket(fd: Int): Boolean

    /**
     * Blocking native run entry point. Call only from a dedicated worker
     * thread; never from the Android main thread.
     */
    @JvmStatic
    @Keep
    external fun nativeArmEngineStart(): Boolean

    @JvmStatic
    @Keep
    external fun nativeCancelEngineStart()

    @JvmStatic
    @Keep
    external fun nativeRunEngine(
        cliArgs: String,
        tunMtu: Int,
        packetInformation: Boolean
    ): Int

    /** Requests cancellation; caller must still wait for nativeRunEngine to return. */
    @JvmStatic
    @Keep
    external fun nativeStopEngine(): Int
}
