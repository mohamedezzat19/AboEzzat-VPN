package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol

/**
 * Builds the exact CLI consumed by the vendored tun2proxy 0.8.7 parser.
 * Credentials are percent-encoded before entering the proxy URL and every
 * argument is shell-quoted for shlex::split on the Rust side.
 */
internal object Tun2ProxyCommandBuilder {
    fun build(profile: ProxyProfile, tunFd: Int, mtu: Int = 1500): String? {
        if (profile.validate() != null || tunFd < 0 || mtu !in 576..65535) return null

        val scheme = when (profile.protocol) {
            ProxyProtocol.HTTP_CONNECT -> "http"
            ProxyProtocol.SOCKS5 -> "socks5"
        }
        val host = profile.host
            .removePrefix("[")
            .removeSuffix("]")
            .let { value -> if (value.contains(':')) "[$value]" else value }

        val auth = if (profile.username != null && profile.password != null) {
            "${percentEncode(profile.username)}:${percentEncode(profile.password)}@"
        } else {
            ""
        }
        val proxyUrl = "$scheme://$auth$host:${profile.port}"

        val dnsMode = if (profile.remoteDns) "virtual" else "over-tcp"
        val arguments = mutableListOf(
            "tun2proxy-bin",
            "--tun-fd", tunFd.toString(),
            "--close-fd-on-drop", "false",
            "--proxy", proxyUrl,
            "--dns", dnsMode,
            "--ipv6-enabled",
            "--mtu", mtu.toString(),
            // Advertise an MSS safe for both IPv4 and IPv6 (MTU - 60).
            // Without this, the userspace TCP stack omits MSS and peers may
            // fall back to a much smaller default segment size.
            "--tcp-mss", (mtu - 60).toString(),
            "--verbosity", "info"
        )

        // The native engine intentionally drops non-DNS UDP unless opted in.
        // Android Proxy Server's SOCKS5 UDP ASSOCIATE path is tested separately,
        // so enable real UDP forwarding for SOCKS5 profiles (QUIC, messaging,
        // speed tests, etc.). HTTP CONNECT has no UDP transport and stays TCP-only.
        if (profile.protocol == ProxyProtocol.SOCKS5) {
            arguments += "--udp-enabled"
            // Keep UDP/QUIC flows alive across short idle gaps common in
            // messaging apps and browser sessions. The native default is 10s.
            arguments += listOf("--udp-timeout", "30")
        }

        return arguments.joinToString(" ") { shellQuote(it) }
    }

    private fun percentEncode(value: String): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder()
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val valueByte = byte.toInt() and 0xff
            val safe = valueByte in 0x41..0x5a ||
                valueByte in 0x61..0x7a ||
                valueByte in 0x30..0x39 ||
                valueByte == 0x2d || valueByte == 0x2e ||
                valueByte == 0x5f || valueByte == 0x7e
            if (safe) {
                out.append(valueByte.toChar())
            } else {
                out.append('%')
                out.append(hex[valueByte ushr 4])
                out.append(hex[valueByte and 0x0f])
            }
        }
        return out.toString()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
