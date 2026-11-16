package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5UdpAssociateTesterTest {
    @Test
    fun reportsUdpAssociateAcceptedAndRelayEndpoint() {
        val server = ServerSocket(0)
        val worker = thread(start = true) {
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                assertEquals(listOf(5, 1, 0), readExactly(input, 3).map { it.toInt() and 0xff })
                output.write(byteArrayOf(5, 0))
                output.flush()

                assertEquals(
                    listOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0),
                    readExactly(input, 10).map { it.toInt() and 0xff }
                )
                output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0x13, 0x89))
                output.flush()
            }
        }

        try {
            val result = Socks5UdpAssociateTester.test(
                ProxyProfile("UDP test", "127.0.0.1", server.localPort, ProxyProtocol.SOCKS5)
            )
            worker.join(3000)
            assertTrue(result.message, result.supported)
            assertEquals("127.0.0.1", result.relayAddress)
            assertEquals(5001, result.relayPort)
            assertTrue(result.message.contains("not yet verified"))
        } finally {
            server.close()
            worker.join(1000)
        }
    }

    @Test
    fun clearlyReportsHttpConnectCannotCarryUdp() {
        val result = Socks5UdpAssociateTester.test(
            ProxyProfile("HTTP", "127.0.0.1", 8080, ProxyProtocol.HTTP_CONNECT)
        )
        assertFalse(result.supported)
        assertTrue(result.message.contains("TCP only"))
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        for (index in 0 until count) {
            val value = input.read()
            if (value < 0) throw AssertionError("Unexpected end of SOCKS5 request")
            result[index] = value.toByte()
        }
        return result
    }
}
