package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.core.AppRoutePolicyResolver
import com.aboezzat.vpn.core.AppRouteResolution
import com.aboezzat.vpn.core.RouteMode

fun interface ConnectionOwnerUidProvider {
    fun resolve(tuple: NetworkConnectionTuple): ConnectionOwnerUidResult
}

sealed interface ConnectionRouteDecision {
    data class Resolved(
        val ownerUid: Int,
        val policy: AppRouteResolution
    ) : ConnectionRouteDecision

    data class Unresolved(
        val ownerLookup: ConnectionOwnerUidResult
    ) : ConnectionRouteDecision
}

/**
 * Joins Android connection attribution with the package-to-route policy map.
 * Unknown UIDs are never guessed: the caller receives Unresolved and must
 * apply its explicit fail-closed handling rather than silently misroute.
 */
class ConnectionRouteDecisionEngine(
    private val ownerUidProvider: ConnectionOwnerUidProvider,
    private val policyResolver: AppRoutePolicyResolver
) {
    fun decide(
        tuple: NetworkConnectionTuple,
        routes: Map<String, RouteMode>
    ): ConnectionRouteDecision {
        return when (val owner = ownerUidProvider.resolve(tuple)) {
            is ConnectionOwnerUidResult.Found -> {
                if (owner.uid <= 0) {
                    ConnectionRouteDecision.Unresolved(
                        ConnectionOwnerUidResult.ConnectionNotFound
                    )
                } else {
                    ConnectionRouteDecision.Resolved(
                        ownerUid = owner.uid,
                        policy = policyResolver.resolve(owner.uid, routes)
                    )
                }
            }
            else -> ConnectionRouteDecision.Unresolved(owner)
        }
    }
}
