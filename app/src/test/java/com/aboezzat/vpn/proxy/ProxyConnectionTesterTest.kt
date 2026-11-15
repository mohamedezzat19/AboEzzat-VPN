package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyConnectionTesterTest {
    @Test
    fun protectsUpstreamSocketBeforeConnectingAndCompletesHttpConnectHandshake() {
        val server = ServerSocket(0)
        val requestReceived = AtomicBoolean(false)
        val serverThread = thread(start = true) {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = BufferedInputStream(socket.getInputStream())
                val request = StringBuilder()
                var matched = 0
                val ending = "\\r\\n\\r\\n"
                while (matched < ending.length) {
                    val value = input.read()
                    if (value < 0) break
                    val char = value.toChar()
                    request.append(char)
                    matched = if (char == ending[matched]) matched + 1
                    else if (char == '\\r') 1 else 0
                }
                requestReceived.set(request.toString().startsWith("CONNECT example.com:443"))
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write("HTTP/1.1 200 Connection established\\r\\n\\r\\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }
        }

        try {
            val profile = ProxyProfile(
                name = "Local test",
                host = "127.0.0.1",
                port = server.localPort,
                protocol = ProxyProtocol.HTTP_CONNECT
            )
            val protectedBeforeConnect = AtomicBoolean(false)
            val result = ProxyConnectionTester.test(profile) { socket ->
                protectedBeforeConnect.set(!socket.isConnected)
                true
            }

            serverThread.join(4000)
            assertTrue("Protection callback must run before socket.connect()", protectedBeforeConnect.get())
            assertTrue("HTTP CONNECT request should reach the local proxy", requestReceived.get())
            assertTrue(result.message, result.success)
            assertTrue(result.message.contains("TLS was not tested"))
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun refusesToConnectWhenSocketProtectionFails() {
        val server = ServerSocket(0)
        server.soTimeout = 250
        try {
            val profile = ProxyProfile(
                name = "Local test",
                host = "127.0.0.1",
                port = server.localPort,
                protocol = ProxyProtocol.HTTP_CONNECT
            )
            val result = ProxyConnectionTester.test(profile) { false }

            assertFalse(result.success)
            assertTrue(result.message.contains("Could not protect"))
            try {
                server.accept().close()
                throw AssertionError("Socket must not connect after protection fails")
            } catch (_: SocketTimeoutException) {
                // Expected: the tester must abort before connecting.
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun completesSocks5NoAuthenticationHandshakeWithRemoteDns() {
        val server = ServerSocket(0)
        val requestWasRemoteDns = AtomicBoolean(false)
        val serverThread = thread(start = true) {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                assertEquals(listOf(5, 1, 0), readExactly(input, 3).map { it.toInt() and 0xff })
                output.write(byteArrayOf(5, 0))
                output.flush()

                val header = readExactly(input, 4)
                assertEquals(5, header[0].toInt() and 0xff)
                assertEquals(1, header[1].toInt() and 0xff)
                val addressType = header[3].toInt() and 0xff
                if (addressType == 3) {
                    val length = input.read()
                    val host = String(readExactly(input, length), Charsets.US_ASCII)
                    requestWasRemoteDns.set(host == "example.com")
                    readExactly(input, 2)
                } else {
                    skipSocksAddressAndPort(input, addressType)
                }
                writeSocksSuccess(output)
            }
        }

        try {
            val result = ProxyConnectionTester.test(
                ProxyProfile("SOCKS no auth", "127.0.0.1", server.localPort, ProxyProtocol.SOCKS5)
            )
            serverThread.join(4000)
            assertTrue(result.message, result.success)
            assertTrue("SOCKS5 request should send the hostname for remote DNS", requestWasRemoteDns.get())
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun completesSocks5UsernamePasswordAuthentication() {
        val server = ServerSocket(0)
        val credentialsAccepted = AtomicBoolean(false)
        val serverThread = thread(start = true) {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())

                assertEquals(listOf(5, 2, 0, 2), readExactly(input, 4).map { it.toInt() and 0xff })
                output.write(byteArrayOf(5, 2))
                output.flush()

                val authVersion = input.read()
                val usernameLength = input.read()
                val username = String(readExactly(input, usernameLength), Charsets.UTF_8)
                val passwordLength = input.read()
                val password = String(readExactly(input, passwordLength), Charsets.UTF_8)
                credentialsAccepted.set(authVersion == 1 && username == "proxy-user" && password == "proxy-pass")
                output.write(byteArrayOf(1, 0))
                output.flush()

                val header = readExactly(input, 4)
                skipSocksAddressAndPort(input, header[3].toInt() and 0xff)
                writeSocksSuccess(output)
            }
        }

        try {
            val result = ProxyConnectionTester.test(
                ProxyProfile(
                    "SOCKS auth", "127.0.0.1", server.localPort, ProxyProtocol.SOCKS5,
                    username = "proxy-user", password = "proxy-pass"
                )
            )
            serverThread.join(4000)
            assertTrue(result.message, result.success)
            assertTrue("SOCKS5 credentials should be sent using RFC 1929", credentialsAccepted.get())
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test
    fun reportsHttpProxyAuthenticationRequired() {
        val server = ServerSocket(0)
        val serverThread = thread(start = true) {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = BufferedInputStream(socket.getInputStream())
                readUntilHttpHeadersEnd(input)
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write("HTTP/1.1 407 Proxy Authentication Required\\r\\nContent-Length: 0\\r\\n\\r\\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            }
        }

        try {
            val result = ProxyConnectionTester.test(
                ProxyProfile("HTTP auth", "127.0.0.1", server.localPort, ProxyProtocol.HTTP_CONNECT)
            )
            serverThread.join(4000)
            assertFalse(result.success)
            assertTrue(result.message.contains("407"))
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        for (index in 0 until count) {
            val value = input.read()
            if (value < 0) throw AssertionError("Unexpected end of proxy request")
            result[index] = value.toByte()
        }
        return result
    }

    private fun skipSocksAddressAndPort(input: BufferedInputStream, addressType: Int) {
        when (addressType) {
            1 -> readExactly(input, 4)
            4 -> readExactly(input, 16)
            3 -> {
                val length = input.read()
                if (length < 0) throw AssertionError("Missing SOCKS5 domain length")
                readExactly(input, length)
            }
            else -> throw AssertionError("Unknown SOCKS5 address type: $addressType")
        }
        readExactly(input, 2)
    }

    private fun writeSocksSuccess(output: BufferedOutputStream) {
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
        output.flush()
    }

    private fun readUntilHttpHeadersEnd(input: BufferedInputStream) {
        var matched = 0
        val ending = "\\r\\n\\r\\n"
        while (matched < ending.length) {
            val value = input.read()
            if (value < 0) throw AssertionError("Incomplete HTTP request")
            val char = value.toChar()
            matched = if (char == ending[matched]) matched + 1
            else if (char == '\\r') 1 else 0
        }
    }
}
