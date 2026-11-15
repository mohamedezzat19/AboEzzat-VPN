package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.core.AppRoutePolicyResolver
import com.aboezzat.vpn.core.AppRouteResolutionReason
import com.aboezzat.vpn.core.RouteMode
import com.aboezzat.vpn.core.UidPackageLookup
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRouteDecisionEngineTest {
    private val tuple = NetworkConnectionTuple(
        IpTransportProtocol.TCP,
        InetSocketAddress(InetAddress.getByName("10.0.0.2"), 43120),
        InetSocketAddress(InetAddress.getByName("203.0.113.10"), 443)
    )

    @Test
    fun resolvesUidThenAppliesExplicitPackageRoute() {
        val engine = ConnectionRouteDecisionEngine(
            ConnectionOwnerUidProvider { ConnectionOwnerUidResult.Found(10123) },
            AppRoutePolicyResolver(UidPackageLookup { listOf("com.example.app") })
        )

        val decision = engine.decide(
            tuple,
            mapOf("com.example.app" to RouteMode.PROXY_ONLY)
        )

        assertTrue(decision is ConnectionRouteDecision.Resolved)
        decision as ConnectionRouteDecision.Resolved
        assertEquals(10123, decision.ownerUid)
        assertEquals(RouteMode.PROXY_ONLY, decision.policy.mode)
        assertEquals(AppRouteResolutionReason.EXPLICIT_POLICY, decision.policy.reason)
    }

    @Test
    fun missingConnectionOwnerIsNotGuessedOrRouted() {
        val engine = ConnectionRouteDecisionEngine(
            ConnectionOwnerUidProvider { ConnectionOwnerUidResult.ConnectionNotFound },
            AppRoutePolicyResolver(UidPackageLookup { listOf("com.example.app") })
        )

        val decision = engine.decide(
            tuple,
            mapOf("com.example.app" to RouteMode.DIRECT)
        )

        assertTrue(decision is ConnectionRouteDecision.Unresolved)
        decision as ConnectionRouteDecision.Unresolved
        assertEquals(ConnectionOwnerUidResult.ConnectionNotFound, decision.ownerLookup)
    }

    @Test
    fun unsupportedAndroidVersionRemainsExplicitlyUnsupported() {
        val engine = ConnectionRouteDecisionEngine(
            ConnectionOwnerUidProvider { ConnectionOwnerUidResult.UnsupportedAndroidVersion },
            AppRoutePolicyResolver(UidPackageLookup { emptyList() })
        )

        val decision = engine.decide(tuple, emptyMap())

        assertTrue(decision is ConnectionRouteDecision.Unresolved)
        decision as ConnectionRouteDecision.Unresolved
        assertEquals(
            ConnectionOwnerUidResult.UnsupportedAndroidVersion,
            decision.ownerLookup
        )
    }

    @Test
    fun invalidUidReturnedByProviderIsRejected() {
        val engine = ConnectionRouteDecisionEngine(
            ConnectionOwnerUidProvider { ConnectionOwnerUidResult.Found(-1) },
            AppRoutePolicyResolver(UidPackageLookup { listOf("com.example.app") })
        )

        val decision = engine.decide(tuple, emptyMap())

        assertTrue(decision is ConnectionRouteDecision.Unresolved)
        decision as ConnectionRouteDecision.Unresolved
        assertEquals(ConnectionOwnerUidResult.ConnectionNotFound, decision.ownerLookup)
    }
}
