package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Checks whether the upstream SOCKS5 server accepts UDP ASSOCIATE.
 *
 * This is deliberately separate from the TCP CONNECT test. A successful reply
 * proves only that the proxy accepted an association request; it does not prove
 * that UDP datagrams can make a complete round trip through the relay.
 */
data class ProxyUdpTestResult(
    val supported: Boolean,
    val message: String,
    val relayAddress: String? = null,
    val relayPort: Int? = null
)

object Socks5UdpAssociateTester {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    fun test(
        profile: ProxyProfile,
        protectSocket: ((Socket) -> Boolean)? = null
    ): ProxyUdpTestResult {
        profile.validate()?.let { return ProxyUdpTestResult(false, it) }
        if (profile.protocol != ProxyProtocol.SOCKS5) {
            return ProxyUdpTestResult(
                false,
                "UDP is not available through HTTP CONNECT. This upstream mode carries TCP only."
            )
        }

        return try {
            Socket().use { socket ->
                if (protectSocket != null && !protectSocket(socket)) {
                    throw IOException("Could not protect the UDP diagnostic socket from VPN routing.")
                }
                socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                negotiateAuthentication(profile, input, output)

                // Request an unspecified client endpoint; the server chooses the
                // UDP relay endpoint in its reply (RFC 1928, CMD=0x03).
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                output.flush()

                val version = input.read()
                val reply = input.read()
                input.read() // RSV
                val addressType = input.read()
                if (version != 5) throw IOException("Invalid SOCKS5 UDP ASSOCIATE response.")
                if (reply != 0) {
                    throw IOException("SOCKS5 UDP ASSOCIATE rejected (reply code $reply).")
                }

                val relayHost = when (addressType) {
                    1 -> InetAddress.getByAddress(readExactly(input, 4)).hostAddress
                    4 -> InetAddress.getByAddress(readExactly(input, 16)).hostAddress
                    3 -> {
                        val length = input.read()
                        if (length !in 1..255) throw IOException("Invalid SOCKS5 relay domain.")
                        String(readExactly(input, length), StandardCharsets.US_ASCII)
                    }
                    else -> throw IOException("Unknown SOCKS5 relay address type.")
                }
                val portBytes = readExactly(input, 2)
                val relayPort = ((portBytes[0].toInt() and 0xff) shl 8) or
                    (portBytes[1].toInt() and 0xff)

                ProxyUdpTestResult(
                    true,
                    "SOCKS5 UDP ASSOCIATE accepted by the server. UDP datagram round-trip is not yet verified.",
                    relayHost,
                    relayPort
                )
            }
        } catch (error: java.net.SocketTimeoutException) {
            ProxyUdpTestResult(false, "SOCKS5 UDP ASSOCIATE timed out.")
        } catch (error: Exception) {
            ProxyUdpTestResult(
                false,
                error.message?.take(140) ?: "SOCKS5 UDP ASSOCIATE test failed."
            )
        }
    }

    private fun negotiateAuthentication(
        profile: ProxyProfile,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val hasCredentials = !profile.username.isNullOrEmpty()
        output.write(
            if (hasCredentials) byteArrayOf(0x05, 0x02, 0x00, 0x02)
            else byteArrayOf(0x05, 0x01, 0x00)
        )
        output.flush()

        val version = input.read()
        val method = input.read()
        if (version != 5) throw IOException("Server is not a SOCKS5 proxy.")
        when (method) {
            0 -> Unit
            2 -> {
                if (!hasCredentials) throw IOException("SOCKS5 server requires username and password.")
                val username = profile.username!!.toByteArray(StandardCharsets.UTF_8)
                val password = profile.password.orEmpty().toByteArray(StandardCharsets.UTF_8)
                require(username.size in 1..255 && password.size in 1..255) {
                    "SOCKS5 credentials must be 1–255 bytes."
                }
                output.write(byteArrayOf(0x01, username.size.toByte()))
                output.write(username)
                output.write(password.size)
                output.write(password)
                output.flush()
                if (input.read() != 1 || input.read() != 0) {
                    throw IOException("SOCKS5 authentication failed.")
                }
            }
            0xff -> throw IOException("SOCKS5 server rejected all authentication methods.")
            else -> throw IOException("Unsupported SOCKS5 authentication method.")
        }
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        for (index in 0 until count) {
            val value = input.read()
            if (value < 0) throw IOException("Incomplete SOCKS5 UDP ASSOCIATE response.")
            result[index] = value.toByte()
        }
        return result
    }
}
