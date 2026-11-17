package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Socks5UdpAssociateTesterTest {
    @Test
    fun verifiesUdpDatagramRoundTripThroughRelay() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { relay ->
            relay.soTimeout = 3000
            ServerSocket(0).use { server ->
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
                        val address = byteArrayOf(0, 0, 0, 0)
                        val port = relay.localPort
                        output.write(byteArrayOf(5, 0, 0, 1) + address +
                            byteArrayOf((port ushr 8).toByte(), port.toByte()))
                        output.flush()

                        val requestBytes = ByteArray(2048)
                        val request = DatagramPacket(requestBytes, requestBytes.size)
                        relay.receive(request)
                        val packet = request.data.copyOf(request.length)
                        // SOCKS UDP header is 10 bytes for an IPv4 destination.
                        assertTrue(packet.size > 22)
                        val dnsQuery = packet.copyOfRange(10, packet.size)
                        dnsQuery[2] = (dnsQuery[2].toInt() or 0x80).toByte()
                        val response = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + dnsQuery
                        relay.send(DatagramPacket(response, response.size, request.address, request.port))
                    }
                }

                val result = Socks5UdpAssociateTester.test(
                    ProxyProfile("UDP test", "127.0.0.1", server.localPort, ProxyProtocol.SOCKS5)
                )
                worker.join(4000)
                assertTrue(result.message, result.supported)
                assertEquals("127.0.0.1", result.relayAddress)
                assertEquals(relay.localPort, result.relayPort)
                assertTrue(result.message.contains("round-trip verified"))
            }
        }
    }

    @Test
    fun unspecifiedRelayAddressUsesTheConnectedProxyAddress() {
        val proxy = InetAddress.getByName("10.100.93.82")
        assertEquals(
            proxy,
            Socks5UdpAssociateTester.normalizeRelayAddress(
                proxy,
                InetAddress.getByName("0.0.0.0")
            )
        )
        assertEquals(
            proxy,
            Socks5UdpAssociateTester.normalizeRelayAddress(
                proxy,
                InetAddress.getByName("::")
            )
        )
    }

    @Test
    fun loopbackRelayIsOnlyRewrittenForRemoteProxy() {
        val remoteProxy = InetAddress.getByName("10.100.93.82")
        val loopback = InetAddress.getByName("127.0.0.1")
        assertEquals(
            remoteProxy,
            Socks5UdpAssociateTester.normalizeRelayAddress(remoteProxy, loopback)
        )
        assertEquals(
            loopback,
            Socks5UdpAssociateTester.normalizeRelayAddress(loopback, loopback)
        )
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
