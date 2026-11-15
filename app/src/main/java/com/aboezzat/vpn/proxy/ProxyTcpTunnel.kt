package com.aboezzat.vpn.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Opens a TCP tunnel through an upstream HTTP CONNECT or SOCKS5 proxy.
 *
 * The returned socket belongs to the caller and must be closed by the caller.
 * This class does not create a VPN/TUN interface and does not forward packets.
 */
object ProxyTcpTunnel {
    private const val DEFAULT_CONNECT_TIMEOUT_MS = 7_000
    private const val DEFAULT_READ_TIMEOUT_MS = 7_000
    private const val MAX_HTTP_HEADER_BYTES = 16_384
    private const val DESTINATION_PORT_MAX = 65_535

    @Throws(IOException::class)
    fun open(
        profile: ProxyProfile,
        destinationHost: String,
        destinationPort: Int,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        protectSocket: ((Socket) -> Boolean)? = null
    ): Socket {
        profile.validate()?.let { throw IllegalArgumentException(it) }
        require(destinationHost.isNotBlank() && !destinationHost.any(Char::isWhitespace)) {
            "A valid destination hostname or IP is required."
        }
        require(destinationPort in 1..DESTINATION_PORT_MAX) {
            "Destination port must be between 1 and 65535."
        }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0) {
            "Socket timeouts must be positive."
        }

        val socket = Socket()
        try {
            if (protectSocket != null && !protectSocket(socket)) {
                throw IOException("Could not protect the upstream socket from VPN routing.")
            }
            socket.connect(InetSocketAddress(profile.host, profile.port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs

            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            when (profile.protocol) {
                ProxyProtocol.HTTP_CONNECT -> establishHttpConnect(
                    profile, destinationHost, destinationPort, input, output
                )
                ProxyProtocol.SOCKS5 -> establishSocks5(
                    profile, destinationHost, destinationPort, input, output
                )
            }
            // The timeout is only for connection setup/handshake. A live tunnel
            // must remain usable while idle, like a normal TCP socket.
            socket.soTimeout = 0
            return socket
        } catch (error: Exception) {
            try {
                socket.close()
            } catch (_: IOException) {
                // Preserve the original connection or handshake failure.
            }
            throw error
        }
    }

    private fun establishHttpConnect(
        profile: ProxyProfile,
        host: String,
        port: Int,
        input: InputStream,
        output: OutputStream
    ) {
        val authority = formatAuthority(host, port)
        val authHeader = if (!profile.username.isNullOrEmpty()) {
            val credentials = "${profile.username}:${profile.password.orEmpty()}"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
            "Proxy-Authorization: Basic $encoded\r\n"
        } else {
            ""
        }
        val request = "CONNECT $authority HTTP/1.1\r\n" +
            "Host: $authority\r\n" +
            authHeader +
            "Proxy-Connection: Keep-Alive\r\n\r\n"
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        val response = readHttpHeaders(input)
        val statusLine = response.substringBefore("\r\n")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        when (statusCode) {
            200 -> Unit
            407 -> throw SecurityException("Proxy authentication required or credentials rejected (HTTP 407).")
            null -> throw IOException("Invalid HTTP proxy response.")
            else -> throw IOException("HTTP proxy rejected CONNECT with status $statusCode.")
        }
    }

    private fun establishSocks5(
        profile: ProxyProfile,
        host: String,
        port: Int,
        input: InputStream,
        output: OutputStream
    ) {
        val hasCredentials = !profile.username.isNullOrEmpty()
        output.write(
            if (hasCredentials) byteArrayOf(0x05, 0x02, 0x00, 0x02)
            else byteArrayOf(0x05, 0x01, 0x00)
        )
        output.flush()

        val version = readByte(input)
        val method = readByte(input)
        if (version != 5) throw IOException("Server is not a SOCKS5 proxy.")
        when (method) {
            0 -> Unit
            2 -> {
                if (!hasCredentials) {
                    throw SecurityException("SOCKS5 server requires a username and password.")
                }
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
                val authVersion = readByte(input)
                val authStatus = readByte(input)
                if (authVersion != 1 || authStatus != 0) {
                    throw SecurityException("SOCKS5 authentication failed.")
                }
            }
            0xFF -> throw SecurityException("SOCKS5 server rejected all authentication methods.")
            else -> throw IOException("Unsupported SOCKS5 authentication method.")
        }

        val address = encodeSocksAddress(host, profile.remoteDns)
        output.write(byteArrayOf(0x05, 0x01, 0x00) + address +
            byteArrayOf((port ushr 8).toByte(), port.toByte()))
        output.flush()

        val responseVersion = readByte(input)
        val reply = readByte(input)
        readByte(input) // Reserved
        val addressType = readByte(input)
        if (responseVersion != 5) throw IOException("Invalid SOCKS5 response.")
        if (reply != 0) throw IOException("SOCKS5 CONNECT failed (reply code $reply).")
        val boundAddressLength = when (addressType) {
            1 -> 4
            4 -> 16
            3 -> readByte(input)
            else -> throw IOException("Unknown SOCKS5 address type.")
        }
        skipExactly(input, boundAddressLength + 2)
    }

    private fun encodeSocksAddress(host: String, remoteDns: Boolean): ByteArray {
        if (remoteDns) {
            val bytes = host.toByteArray(StandardCharsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= 255 && bytes.all { it.toInt() in 33..126 }) {
                "Remote DNS requires an ASCII hostname of 1–255 bytes."
            }
            return byteArrayOf(0x03, bytes.size.toByte()) + bytes
        }

        val address = InetAddress.getByName(host).address
        return if (address.size == 4) byteArrayOf(0x01) + address
        else byteArrayOf(0x04) + address
    }

    private fun formatAuthority(host: String, port: Int): String {
        val normalizedHost = if (host.startsWith("[") && host.endsWith("]")) {
            host
        } else if (host.contains(':')) {
            "[$host]"
        } else {
            host
        }
        return "$normalizedHost:$port"
    }

    private fun readHttpHeaders(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        val terminator = byteArrayOf(13, 10, 13, 10)
        var matched = 0
        while (bytes.size < MAX_HTTP_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) break
            bytes.add(next.toByte())
            if (next == (terminator[matched].toInt() and 0xff)) {
                matched++
                if (matched == terminator.size) break
            } else {
                matched = if (next == 13) 1 else 0
            }
        }
        if (matched != terminator.size) throw IOException("Incomplete HTTP proxy response.")
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw IOException("Unexpected end of proxy handshake.")
        return value
    }

    private fun skipExactly(input: InputStream, count: Int) {
        repeat(count) { readByte(input) }
    }
}
