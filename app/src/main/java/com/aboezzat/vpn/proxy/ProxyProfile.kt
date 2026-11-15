package com.aboezzat.vpn.proxy

enum class ProxyProtocol {
    HTTP_CONNECT,
    SOCKS5
}

data class ProxyProfile(
    val name: String,
    val host: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val username: String? = null,
    val password: String? = null,
    val remoteDns: Boolean = true
) {
    fun validate(): String? = when {
        name.isBlank() -> "Profile name is required"
        host.isBlank() -> "Server address is required"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        (username == null) != (password == null) ->
            "Username and password must be provided together"
        else -> null
    }
}
