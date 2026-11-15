package com.aboezzat.vpn.core

/**
 * Resolves a network connection's Android UID to the route policy selected
 * for the package(s) that own that UID.
 *
 * Android routes VPN traffic by UID, not by package. Multiple packages can
 * share one UID, so conflicting explicit policies cannot be enforced
 * independently and are reported as a conflict instead of choosing one
 * package arbitrarily.
 */
fun interface UidPackageLookup {
    fun packagesForUid(uid: Int): List<String>
}

enum class AppRouteResolutionReason {
    EXPLICIT_POLICY,
    DEFAULT_POLICY,
    UNKNOWN_UID,
    CONFLICTING_SHARED_UID_POLICIES
}

data class AppRouteResolution(
    val uid: Int,
    val packageNames: List<String>,
    val mode: RouteMode,
    val reason: AppRouteResolutionReason
)

class AppRoutePolicyResolver(
    private val packageLookup: UidPackageLookup,
    private val defaultMode: RouteMode = RouteMode.VPN_ONLY
) {
    fun resolve(uid: Int, routes: Map<String, RouteMode>): AppRouteResolution {
        if (uid <= 0) {
            return AppRouteResolution(
                uid = uid,
                packageNames = emptyList(),
                mode = defaultMode,
                reason = AppRouteResolutionReason.UNKNOWN_UID
            )
        }

        val packages = packageLookup.packagesForUid(uid)
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        if (packages.isEmpty()) {
            return AppRouteResolution(
                uid = uid,
                packageNames = emptyList(),
                mode = defaultMode,
                reason = AppRouteResolutionReason.UNKNOWN_UID
            )
        }

        val explicitModes = packages.mapNotNull(routes::get).distinct()
        return when (explicitModes.size) {
            0 -> AppRouteResolution(
                uid, packages, defaultMode, AppRouteResolutionReason.DEFAULT_POLICY
            )
            1 -> AppRouteResolution(
                uid, packages, explicitModes.single(), AppRouteResolutionReason.EXPLICIT_POLICY
            )
            else -> AppRouteResolution(
                uid, packages, defaultMode,
                AppRouteResolutionReason.CONFLICTING_SHARED_UID_POLICIES
            )
        }
    }
}
