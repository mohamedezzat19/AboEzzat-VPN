package com.aboezzat.vpn.core

/**
 * Desired route for an app. These are policy definitions only;
 * enforcement is intentionally not enabled until the packet engine exists.
 */
enum class RouteMode {
    VPN_ONLY,
    PROXY_ONLY,
    PROXY_THEN_VPN,
    DIRECT
}

data class AppRoutePolicy(
    val packageName: String,
    val mode: RouteMode
)
