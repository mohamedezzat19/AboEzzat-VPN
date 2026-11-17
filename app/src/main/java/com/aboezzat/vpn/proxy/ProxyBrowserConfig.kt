package com.aboezzat.vpn.proxy

object ProxyBrowserConfig {
    fun buildProxyUrl(host: String, port: Int, protocol: String): String {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        require(cleanHost.isNotBlank())
        require(port in 1..65535)
        val authorityHost = if (cleanHost.contains(':')) "[$cleanHost]" else cleanHost
        val scheme = when (protocol.uppercase()) {
            "HTTP_CONNECT" -> "http"
            "SOCKS5" -> "socks"
            else -> throw IllegalArgumentException("Unsupported proxy protocol.")
        }
        return "$scheme://$authorityHost:$port"
    }

    fun normalizeUrl(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty())
        val normalized = if (value.contains("://")) value else "https://$value"
        require(normalized.startsWith("http://", true) || normalized.startsWith("https://", true))
        return normalized
    }
}
