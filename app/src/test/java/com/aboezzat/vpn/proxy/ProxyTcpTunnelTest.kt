package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTcpTunnelTest {
    @Test
    fun opensHttpConnectTunnelAndReturnsUsableSocket() {
        val server = ServerSocket(0)
        val requestValid = AtomicBoolean(false)
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = thread(start = true) {
            try {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val input = BufferedInputStream(peer.getInputStream())
                    val request = readHttpHeaders(input)
                    requestValid.set(
                        request.startsWith("CONNECT example.com:443 HTTP/1.1\r\n") &&
                            request.contains("Proxy-Authorization: Basic dXNlcjpwYXNz\r\n")
                    )
                    val output = BufferedOutputStream(peer.getOutputStream())
                    output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                    val payload = ByteArray(4)
                    input.readFully(payload)
                    assertEquals("ping", String(payload, Charsets.US_ASCII))
                    output.write("pong".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        try {
            val protectedBeforeConnect = AtomicBoolean(false)
            val tunnel = ProxyTcpTunnel.open(
                profile = ProxyProfile(
                    "HTTP test", "127.0.0.1", server.localPort, ProxyProtocol.HTTP_CONNECT,
                    username = "user", password = "pass"
                ),
                destinationHost = "example.com",
                destinationPort = 443,
                protectSocket = { socket ->
                    protectedBeforeConnect.set(!socket.isConnected)
                    true
                }
            )
            tunnel.use { socket ->
                socket.getOutputStream().write("ping".toByteArray(Charsets.US_ASCII))
                val response = ByteArray(4)
                socket.getInputStream().readFully(response)
                assertEquals("pong", String(response, Charsets.US_ASCII))
            }
            worker.join(3000)
            assertTrue("protect() callback must run before connect", protectedBeforeConnect.get())
            assertTrue("HTTP CONNECT request must include destination and auth", requestValid.get())
            serverFailure.get()?.let { throw AssertionError("Mock HTTP proxy failed", it) }
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun opensSocks5AuthenticatedRemoteDnsTunnel() {
        val server = ServerSocket(0)
        val requestValid = AtomicBoolean(false)
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = thread(start = true) {
            try {
                server.accept().use { peer ->
                    peer.soTimeout = 3000
                    val input = BufferedInputStream(peer.getInputStream())
                    val output = BufferedOutputStream(peer.getOutputStream())

                    assertEquals(listOf(5, 2, 0, 2), readExactly(input, 4).map { it.toInt() and 0xff })
                    output.write(byteArrayOf(5, 2))
                    output.flush()

                    val authVersion = input.read()
                    val usernameLength = input.read()
                    val username = String(readExactly(input, usernameLength), Charsets.UTF_8)
                    val passwordLength = input.read()
                    val password = String(readExactly(input, passwordLength), Charsets.UTF_8)
                    assertTrue(authVersion == 1 && username == "user" && password == "pass")
                    output.write(byteArrayOf(1, 0))
                    output.flush()

                    val header = readExactly(input, 4)
                    assertEquals(5, header[0].toInt() and 0xff)
                    assertEquals(1, header[1].toInt() and 0xff)
                    assertEquals(3, header[3].toInt() and 0xff)
                    val hostLength = input.read()
                    val host = String(readExactly(input, hostLength), Charsets.US_ASCII)
                    val port = readExactly(input, 2).map { it.toInt() and 0xff }
                    requestValid.set(host == "example.com" && port == listOf(1, 187))

                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                    output.flush()
                    val payload = ByteArray(4)
                    input.readFully(payload)
                    assertEquals("ping", String(payload, Charsets.US_ASCII))
                    output.write("pong".toByteArray(Charsets.US_ASCII))
                    output.flush()
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            }
        }

        try {
            val tunnel = ProxyTcpTunnel.open(
                profile = ProxyProfile(
                    "SOCKS test", "127.0.0.1", server.localPort, ProxyProtocol.SOCKS5,
                    username = "user", password = "pass", remoteDns = true
                ),
                destinationHost = "example.com",
                destinationPort = 443
            )
            tunnel.use { socket ->
                socket.getOutputStream().write("ping".toByteArray(Charsets.US_ASCII))
                val response = ByteArray(4)
                socket.getInputStream().readFully(response)
                assertEquals("pong", String(response, Charsets.US_ASCII))
            }
            worker.join(3000)
            assertTrue("SOCKS5 CONNECT must use remote hostname and port", requestValid.get())
            serverFailure.get()?.let { throw AssertionError("Mock SOCKS5 proxy failed", it) }
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun closesSocketWhenProtectionIsRejected() {
        val server = ServerSocket(0)
        server.soTimeout = 250
        try {
            try {
                ProxyTcpTunnel.open(
                    ProxyProfile("HTTP test", "127.0.0.1", server.localPort, ProxyProtocol.HTTP_CONNECT),
                    "example.com",
                    443,
                    protectSocket = { false }
                )
                throw AssertionError("Expected protection failure")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains("protect"))
            }
            try {
                server.accept().close()
                throw AssertionError("Proxy socket must not connect if protect() fails")
            } catch (_: java.net.SocketTimeoutException) {
                // Expected: connection was rejected before connect().
            }
        } finally {
            server.close()
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): String {
        val result = StringBuilder()
        var matched = 0
        val ending = "\r\n\r\n"
        while (matched < ending.length) {
            val value = input.read()
            if (value < 0) throw AssertionError("Incomplete HTTP CONNECT request")
            val char = value.toChar()
            result.append(char)
            matched = if (char == ending[matched]) matched + 1
            else if (char == '\r') 1 else 0
        }
        return result.toString()
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return bytes
    }

    private fun java.io.InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = read(bytes, offset, bytes.size - offset)
            if (count < 0) throw AssertionError("Unexpected end of mock proxy stream")
            offset += count
        }
    }

}
