package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTcpTunnelManagerTest {
    @Test
    fun enforcesLimitAndReleasesTunnelResources() {
        val server = ServerSocket(0)
        val accepted = CountDownLatch(1)
        val worker = thread(start = true) {
            server.accept().use { peer ->
                peer.soTimeout = 3000
                val input = BufferedInputStream(peer.getInputStream())
                readHttpHeaders(input)
                val output = BufferedOutputStream(peer.getOutputStream())
                output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                accepted.countDown()
                try {
                    while (input.read() >= 0) Unit
                } catch (_: IOException) {
                    // Client closed the managed tunnel.
                }
            }
        }
        val manager = ProxyTcpTunnelManager(maxConcurrentTunnels = 1)
        try {
            val socket = manager.openTunnel(
                ProxyProfile("test", "127.0.0.1", server.localPort, ProxyProtocol.HTTP_CONNECT),
                "example.com",
                443
            )
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            assertEquals(1, manager.activeCount)

            try {
                manager.openTunnel(
                    ProxyProfile("test", "127.0.0.1", server.localPort, ProxyProtocol.HTTP_CONNECT),
                    "example.org",
                    443
                )
                throw AssertionError("Expected concurrent tunnel limit to reject second tunnel")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("limit"))
            }

            assertTrue(manager.release(socket))
            assertTrue(socket.isClosed)
            assertEquals(0, manager.activeCount)
            assertFalse(manager.release(socket))
        } finally {
            manager.shutdown()
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun shutdownRejectsNewTunnels() {
        val manager = ProxyTcpTunnelManager()
        manager.shutdown()
        assertTrue(manager.isShutdown)
        try {
            manager.openTunnel(
                ProxyProfile("test", "127.0.0.1", 1, ProxyProtocol.HTTP_CONNECT),
                "example.com",
                443
            )
            throw AssertionError("Expected shutdown manager to reject new tunnel")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("shut down"))
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream) {
        var matched = 0
        val ending = "\r\n\r\n"
        while (matched < ending.length) {
            val value = input.read()
            if (value < 0) throw AssertionError("Incomplete HTTP CONNECT request")
            val char = value.toChar()
            matched = if (char == ending[matched]) matched + 1
            else if (char == '\r') 1 else 0
        }
    }
}
