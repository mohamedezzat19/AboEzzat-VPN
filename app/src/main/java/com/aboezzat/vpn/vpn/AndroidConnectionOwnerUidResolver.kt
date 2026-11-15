package com.aboezzat.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import java.net.InetSocketAddress

enum class IpTransportProtocol {
    TCP,
    UDP
}

data class NetworkConnectionTuple(
    val protocol: IpTransportProtocol,
    val local: InetSocketAddress,
    val remote: InetSocketAddress
)

sealed interface ConnectionOwnerUidResult {
    data class Found(val uid: Int) : ConnectionOwnerUidResult
    data object UnsupportedAndroidVersion : ConnectionOwnerUidResult
    data object ConnectionNotFound : ConnectionOwnerUidResult
    data object VpnNotActiveOrNotAuthorized : ConnectionOwnerUidResult
    data object InvalidConnectionTuple : ConnectionOwnerUidResult
    data class LookupFailed(val errorType: String) : ConnectionOwnerUidResult
}

/**
 * Resolves the UID owning an observed TCP/UDP connection.
 *
 * Android only exposes this API from API 29 and only to the active VpnService
 * for connections covered by that VPN. It must not be used as a guess when
 * the platform cannot provide an owner UID.
 */
class AndroidConnectionOwnerUidResolver(context: Context) : ConnectionOwnerUidProvider {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun resolve(tuple: NetworkConnectionTuple): ConnectionOwnerUidResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ConnectionOwnerUidResult.UnsupportedAndroidVersion
        }
        if (tuple.local.isUnresolved || tuple.remote.isUnresolved ||
            tuple.local.port !in 1..65535 || tuple.remote.port !in 1..65535
        ) {
            return ConnectionOwnerUidResult.InvalidConnectionTuple
        }

        val manager = connectivityManager
            ?: return ConnectionOwnerUidResult.LookupFailed("ConnectivityManagerUnavailable")

        val protocol = when (tuple.protocol) {
            IpTransportProtocol.TCP -> OsConstants.IPPROTO_TCP
            IpTransportProtocol.UDP -> OsConstants.IPPROTO_UDP
        }

        return try {
            val uid = manager.getConnectionOwnerUid(protocol, tuple.local, tuple.remote)
            if (uid == Process.INVALID_UID) {
                ConnectionOwnerUidResult.ConnectionNotFound
            } else {
                ConnectionOwnerUidResult.Found(uid)
            }
        } catch (_: SecurityException) {
            ConnectionOwnerUidResult.VpnNotActiveOrNotAuthorized
        } catch (_: IllegalArgumentException) {
            ConnectionOwnerUidResult.InvalidConnectionTuple
        } catch (error: Exception) {
            ConnectionOwnerUidResult.LookupFailed(error.javaClass.simpleName)
        }
    }
}
