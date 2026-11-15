package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DnsUdpTcpBridgeTest {
    @Test
    fun receivesLocalUdpQueryAndReturnsDnsOverTcpProxyResponse() {
        ServerSocket(0).use { proxyServer ->
            val executor = Executors.newSingleThreadExecutor()
            val proxyTask = executor.submit {
                proxyServer.accept().use { socket ->
                    val input = BufferedInputStream(socket.getInputStream())
                    val output = BufferedOutputStream(socket.getOutputStream())
                    val headers = readHeaders(input)
                    check(headers.startsWith("CONNECT resolver.test:53 "))
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    output.flush()

                    val dataIn = DataInputStream(input)
                    val queryLength = dataIn.readUnsignedShort()
                    val query = ByteArray(queryLength)
                    dataIn.readFully(query)
                    val response = dnsResponse(query)
                    val dataOut = DataOutputStream(output)
                    dataOut.writeShort(response.size)
                    dataOut.write(response)
                    dataOut.flush()
                }
            }

            val profile = ProxyProfile(
                name = "bridge test",
                host = "127.0.0.1",
                port = proxyServer.localPort,
                protocol = ProxyProtocol.HTTP_CONNECT
            )
            val bridge = DnsUdpTcpBridge()
            bridge.use {
                val local = bridge.start(profile, "resolver.test")
                assertEqualsLoopback(local.address.hostAddress)
                DatagramSocket().use { client ->
                    client.soTimeout = 4_000
                    val query = dnsQuery()
                    client.send(DatagramPacket(query, query.size, local))
                    val buffer = ByteArray(2_048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    client.receive(packet)
                    assertArrayEquals(dnsResponse(query), packet.data.copyOf(packet.length))
                }
                proxyTask.get(3, TimeUnit.SECONDS)
                assertTrue(bridge.stats().received >= 1)
                assertTrue(bridge.stats().forwarded >= 1)
                assertTrue(bridge.isRunning)
            }
            assertFalse(bridge.isRunning)
            val restarted = bridge.start(profile, "resolver.test")
            assertEqualsLoopback(restarted.address.hostAddress)
            assertTrue(bridge.isRunning)
            bridge.close()
            assertFalse(bridge.isRunning)
            executor.shutdownNow()
        }
    }

    private fun assertEqualsLoopback(value: String) {
        org.junit.Assert.assertEquals("127.0.0.1", value)
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            check(value >= 0)
            bytes += value.toByte()
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> 4
                value == 13 -> 1
                else -> 0
            }
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun dnsQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
        'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01
    )

    private fun dnsResponse(query: ByteArray): ByteArray =
        query.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }
}
