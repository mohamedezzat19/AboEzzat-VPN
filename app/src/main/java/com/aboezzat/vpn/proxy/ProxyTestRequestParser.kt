package com.aboezzat.vpn.proxy

/**
 * Converts untrusted service-start extras into a validated proxy profile.
 * Kept Android-free so malformed requests can be covered by local unit tests.
 */
object ProxyTestRequestParser {
    fun parse(
        name: String?,
        host: String?,
        port: Int,
        protocol: String?,
        username: String?,
        password: String?,
        remoteDns: Boolean
    ): ProxyProfile? {
        val parsedProtocol = runCatching {
            ProxyProtocol.valueOf(protocol ?: ProxyProtocol.HTTP_CONNECT.name)
        }.getOrNull() ?: return null

        val profile = ProxyProfile(
            name = name.orEmpty(),
            host = host.orEmpty(),
            port = port,
            protocol = parsedProtocol,
            username = username,
            password = password,
            remoteDns = remoteDns
        )
        return profile.takeIf { it.validate() == null }
    }
}
