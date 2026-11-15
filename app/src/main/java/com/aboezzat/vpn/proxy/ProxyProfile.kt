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
        host.isBlank() || host.any(Char::isWhitespace) -> "Enter a valid server IP or hostname"
        port !in 1..65535 -> "Port must be between 1 and 65535"
        (username.isNullOrBlank()) != (password.isNullOrBlank()) ->
            "Username and password must both be provided, or both left empty"
        else -> null
    }
}
