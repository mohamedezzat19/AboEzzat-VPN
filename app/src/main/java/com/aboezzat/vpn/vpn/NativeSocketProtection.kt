package com.aboezzat.vpn.vpn

/**
 * Small fail-closed boundary around Android VpnService.protect(int).
 *
 * Kept independent of Android so invalid descriptors and callback failures
 * can be covered by local unit tests. The native engine must never continue
 * with connect/bind when this returns false.
 */
internal object NativeSocketProtection {
    fun protect(fd: Int, protectFd: (Int) -> Boolean): Boolean {
        if (fd < 0) return false
        return try {
            protectFd(fd)
        } catch (_: RuntimeException) {
            false
        }
    }
}
