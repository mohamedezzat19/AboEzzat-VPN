package com.aboezzat.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppRoutePolicyResolverTest {

    @Test
    fun selectedPackageUsesItsExplicitRoute() {
        val resolver = AppRoutePolicyResolver(UidPackageLookup { listOf("app.one") })
        val result = resolver.resolve(10123, mapOf("app.one" to RouteMode.PROXY_ONLY))

        assertEquals(RouteMode.PROXY_ONLY, result.mode)
        assertEquals(AppRouteResolutionReason.EXPLICIT_POLICY, result.reason)
        assertEquals(listOf("app.one"), result.packageNames)
    }

    @Test
    fun unconfiguredPackageUsesVpnOnlyDefault() {
        val resolver = AppRoutePolicyResolver(UidPackageLookup { listOf("app.default") })
        val result = resolver.resolve(10124, emptyMap())

        assertEquals(RouteMode.VPN_ONLY, result.mode)
        assertEquals(AppRouteResolutionReason.DEFAULT_POLICY, result.reason)
    }

    @Test
    fun unknownUidUsesDefaultWithoutInventingPackage() {
        val resolver = AppRoutePolicyResolver(UidPackageLookup { emptyList() })
        val result = resolver.resolve(0, mapOf("app.one" to RouteMode.DIRECT))

        assertEquals(RouteMode.VPN_ONLY, result.mode)
        assertEquals(emptyList<String>(), result.packageNames)
        assertEquals(AppRouteResolutionReason.UNKNOWN_UID, result.reason)
    }

    @Test
    fun sharedUidWithOneExplicitPolicyUsesThatUidPolicy() {
        val resolver = AppRoutePolicyResolver(
            UidPackageLookup { listOf("app.two", "app.one", "app.one") }
        )
        val result = resolver.resolve(10125, mapOf("app.one" to RouteMode.DIRECT))

        assertEquals(listOf("app.one", "app.two"), result.packageNames)
        assertEquals(RouteMode.DIRECT, result.mode)
        assertEquals(AppRouteResolutionReason.EXPLICIT_POLICY, result.reason)
    }

    @Test
    fun sharedUidWithConflictingPoliciesFallsBackAndReportsConflict() {
        val resolver = AppRoutePolicyResolver(
            UidPackageLookup { listOf("app.one", "app.two") }
        )
        val result = resolver.resolve(
            10126,
            mapOf(
                "app.one" to RouteMode.DIRECT,
                "app.two" to RouteMode.PROXY_ONLY
            )
        )

        assertEquals(RouteMode.VPN_ONLY, result.mode)
        assertEquals(
            AppRouteResolutionReason.CONFLICTING_SHARED_UID_POLICIES,
            result.reason
        )
    }

    @Test
    fun customDefaultIsHonoredForUnknownUid() {
        val resolver = AppRoutePolicyResolver(
            UidPackageLookup { emptyList() },
            defaultMode = RouteMode.DIRECT
        )
        assertEquals(RouteMode.DIRECT, resolver.resolve(10127, emptyMap()).mode)
    }
}
