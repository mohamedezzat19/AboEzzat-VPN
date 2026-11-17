package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.nio.charset.StandardCharsets
import java.util.Base64

data class ProxyTestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long
)

object ProxyConnectionTester {
    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 7000
    private const val TEST_HOST = "example.com"
    private const val TEST_PORT = 443

    fun test(profile: ProxyProfile, protectSocket: ((Socket) -> Boolean)? = null): ProxyTestResult {
        profile.validate()?.let { return ProxyTestResult(false, it, 0) }
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        return try {
            Socket().use { socket ->
                if (protectSocket != null && !protectSocket(socket)) {
                    throw IOException("Could not protect the upstream socket from VPN routing.")
                }
                socket.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                when (profile.protocol) {
                    ProxyProtocol.HTTP_CONNECT -> testHttpConnect(profile, input, output)
                    ProxyProtocol.SOCKS5 -> testSocks5(profile, input, output)
                }

                // A successful proxy handshake is not enough: verify destination TLS
                // and read an actual HTTP response through the established tunnel.
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, TEST_HOST, TEST_PORT, false) as SSLSocket
                ssl.use { tls ->
                    tls.soTimeout = READ_TIMEOUT_MS
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    tls.startHandshake()
                    val request = "GET / HTTP/1.1\r\n" +
                        "Host: $TEST_HOST\r\n" +
                        "User-Agent: AboEzzat-VPN-Diagnostic/1.0\r\n" +
                        "Connection: close\r\n\r\n"
                    tls.outputStream.write(request.toByteArray(StandardCharsets.US_ASCII))
                    tls.outputStream.flush()
                    val response = BufferedInputStream(tls.inputStream)
                    val headers = readHttpHeaders(response)
                    val status = headers.lineSequence().firstOrNull().orEmpty()
                    if (!status.contains(" 200 ")) {
                        throw IOException("TLS connected, but destination returned: $status")
                    }
                    if (response.read() < 0) {
                        throw IOException("TLS connected, but no response body data was received.")
                    }
                }
            }
            ProxyTestResult(
                true,
                "Proxy CONNECT, destination TLS and HTTPS data transfer succeeded.",
                elapsedMs()
            )
        } catch (e: javax.net.ssl.SSLException) {
            ProxyTestResult(false, "Proxy CONNECT succeeded or started, but destination TLS failed: ${e.message?.take(100) ?: "TLS error"}", elapsedMs())
        } catch (e: java.net.SocketTimeoutException) {
            ProxyTestResult(false, "Timed out during proxy connection, TLS or HTTPS data transfer.", elapsedMs())
        } catch (e: java.net.UnknownHostException) {
            ProxyTestResult(false, "Could not resolve the proxy server or destination address.", elapsedMs())
        } catch (e: Exception) {
            ProxyTestResult(false, e.message?.take(140) ?: "Proxy HTTPS test failed.", elapsedMs())
        }
    }

    private fun testHttpConnect(
        profile: ProxyProfile,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val auth = if (!profile.username.isNullOrEmpty()) {
            val raw = "${profile.username}:${profile.password.orEmpty()}"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
            "Proxy-Authorization: Basic $encoded\r\n"
        } else ""
        val request = "CONNECT $TEST_HOST:$TEST_PORT HTTP/1.1\r\n" +
            "Host: $TEST_HOST:$TEST_PORT\r\n" +
            auth +
            "Proxy-Connection: Keep-Alive\r\n\r\n"
        output.write(request.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val response = readHttpHeaders(input)
        val status = response.lineSequence().firstOrNull().orEmpty()
        when {
            status.contains(" 200 ") -> Unit
            status.contains(" 407 ") -> throw SecurityException("Proxy authentication required or credentials rejected (HTTP 407).")
            else -> throw IllegalStateException("HTTP proxy rejected CONNECT: $status")
        }
    }

    private fun testSocks5(
        profile: ProxyProfile,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val hasCredentials = !profile.username.isNullOrEmpty()
        output.write(if (hasCredentials) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val version = input.read()
        val method = input.read()
        if (version != 5) throw IllegalStateException("Server is not a SOCKS5 proxy.")
        when (method) {
            0 -> Unit
            2 -> {
                if (!hasCredentials) throw SecurityException("SOCKS5 server requires a username and password.")
                val user = profile.username!!.toByteArray(StandardCharsets.UTF_8)
                val pass = profile.password.orEmpty().toByteArray(StandardCharsets.UTF_8)
                require(user.size in 1..255 && pass.size in 1..255) { "SOCKS5 credentials must be 1–255 bytes." }
                output.write(byteArrayOf(0x01, user.size.toByte()))
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                output.flush()
                val authVersion = input.read()
                val authStatus = input.read()
                if (authVersion != 1 || authStatus != 0) {
                    throw SecurityException("SOCKS5 authentication failed.")
                }
            }
            0xFF -> throw SecurityException("SOCKS5 server rejected all authentication methods.")
            else -> throw IllegalStateException("Unsupported SOCKS5 authentication method.")
        }

        val address = if (profile.remoteDns) {
            val host = TEST_HOST.toByteArray(StandardCharsets.US_ASCII)
            byteArrayOf(0x03, host.size.toByte()) + host
        } else {
            val ip = InetAddress.getByName(TEST_HOST).address
            if (ip.size == 4) byteArrayOf(0x01) + ip else byteArrayOf(0x04) + ip
        }
        output.write(byteArrayOf(0x05, 0x01, 0x00) + address + byteArrayOf((TEST_PORT shr 8).toByte(), TEST_PORT.toByte()))
        output.flush()

        val responseVersion = input.read()
        val reply = input.read()
        input.read()
        val addressType = input.read()
        if (responseVersion != 5) throw IllegalStateException("Invalid SOCKS5 response.")
        if (reply != 0) throw IllegalStateException("SOCKS5 CONNECT failed (reply code $reply).")
        val addressLength = when (addressType) {
            1 -> 4
            4 -> 16
            3 -> input.read().also { if (it < 0) throw IllegalStateException("Incomplete SOCKS5 response.") }
            else -> throw IllegalStateException("Unknown SOCKS5 address type.")
        }
        repeat(addressLength + 2) {
            if (input.read() < 0) throw IllegalStateException("Incomplete SOCKS5 response.")
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (bytes.size < 16_384) {
            val next = input.read()
            if (next < 0) break
            bytes.add(next.toByte())
            if (next.toByte() == terminator[matched]) {
                matched++
                if (matched == terminator.size) break
            } else {
                matched = if (next == 13) 1 else 0
            }
        }
        if (matched != terminator.size) throw IllegalStateException("Incomplete HTTP proxy response.")
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}
