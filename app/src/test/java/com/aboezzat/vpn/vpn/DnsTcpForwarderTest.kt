package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DnsTcpForwarderTest {
    @Test
    fun forwardsLengthPrefixedDnsMessageThroughHttpConnectProxy() {
        ServerSocket(0).use { proxyServer ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val proxyTask = executor.submit {
                    proxyServer.accept().use { socket ->
                        val input = BufferedInputStream(socket.getInputStream())
                        val output = BufferedOutputStream(socket.getOutputStream())
                        val request = readHeaders(input)
                        check(request.startsWith("CONNECT resolver.test:53 "))
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        output.flush()

                        val dataIn = DataInputStream(input)
                        val queryLength = dataIn.readUnsignedShort()
                        val query = ByteArray(queryLength)
                        dataIn.readFully(query)
                        val response = query.copyOf().also {
                            it[2] = 0x81.toByte()
                            it[3] = 0x80.toByte()
                        }
                        val dataOut = DataOutputStream(output)
                        dataOut.writeShort(response.size)
                        dataOut.write(response)
                        dataOut.flush()
                    }
                }

                val query = dnsQuery()
                val result = DnsTcpForwarder().forward(
                    profile = ProxyProfile(
                        name = "local test proxy",
                        host = "127.0.0.1",
                        port = proxyServer.localPort,
                        protocol = ProxyProtocol.HTTP_CONNECT
                    ),
                    resolverHost = "resolver.test",
                    query = query
                )
                assertArrayEquals(query.copyOf().also {
                    it[2] = 0x81.toByte()
                    it[3] = 0x80.toByte()
                }, result)
                proxyTask.get(3, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun rejectsMalformedDnsQueryBeforeOpeningProxySocket() {
        val forwarder = DnsTcpForwarder()
        val profile = ProxyProfile(
            name = "test",
            host = "127.0.0.1",
            port = 1,
            protocol = ProxyProtocol.HTTP_CONNECT
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            forwarder.forward(profile, "127.0.0.1", ByteArray(12))
        }
        assertEquals("DNS query is malformed or uses an unsupported query layout.", error.message)
    }

    @Test
    fun rejectsInvalidConstructorLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            DnsTcpForwarder(maxMessageBytes = 11)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsTcpForwarder(readTimeoutMs = 0)
        }
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
}
