package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
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
                val ending = "\r\n\r\n"
                while (matched < ending.length) {
                    val value = input.read()
                    if (value < 0) break
                    val char = value.toChar()
                    request.append(char)
                    matched = if (char == ending[matched]) matched + 1
                    else if (char == '\r') 1 else 0
                }
                requestReceived.set(request.toString().startsWith("CONNECT example.com:443"))
                val output = BufferedOutputStream(socket.getOutputStream())
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
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
}
