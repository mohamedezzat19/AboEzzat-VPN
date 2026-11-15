package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyTcpRelayTest {
    @Test
    fun opensProxyTunnelRelaysDataAndReleasesManagedSocket() {
        val proxyServer = ServerSocket(0)
        val clientServer = ServerSocket(0)
        val proxyAccepted = CountDownLatch(1)
        val proxyWorker = thread(start = true) {
            proxyServer.accept().use { proxy ->
                proxy.soTimeout = 3000
                val input = BufferedInputStream(proxy.getInputStream())
                readHeaders(input)
                proxy.getOutputStream().write(
                    "HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII)
                )
                proxy.getOutputStream().flush()
                proxyAccepted.countDown()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    proxy.getOutputStream().write(buffer, 0, count)
                    proxy.getOutputStream().flush()
                }
            }
        }
        val clientPeer = Socket("127.0.0.1", clientServer.localPort)
        val relayClient = clientServer.accept()
        val manager = ProxyTcpTunnelManager(2)
        val executor = Executors.newFixedThreadPool(2)
        val relay = ProxyTcpRelay(manager, executor)

        try {
            val session = relay.open(
                clientSocket = relayClient,
                profile = ProxyProfile(
                    "local-test",
                    "127.0.0.1",
                    proxyServer.localPort,
                    ProxyProtocol.HTTP_CONNECT
                ),
                destinationHost = "example.com",
                destinationPort = 443
            )
            assertTrue(proxyAccepted.await(2, TimeUnit.SECONDS))
            clientPeer.getOutputStream().write("hello".toByteArray())
            val echoed = clientPeer.getInputStream().readNBytes(5)
            assertEquals("hello", String(echoed))
            clientPeer.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (manager.activeCount != 0 && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertEquals(0, manager.activeCount)
            assertTrue(session.isClosed)
        } finally {
            clientPeer.close()
            manager.shutdown()
            executor.shutdownNow()
            proxyServer.close()
            clientServer.close()
            proxyWorker.join(1000)
        }
    }

    private fun readHeaders(input: BufferedInputStream) {
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
