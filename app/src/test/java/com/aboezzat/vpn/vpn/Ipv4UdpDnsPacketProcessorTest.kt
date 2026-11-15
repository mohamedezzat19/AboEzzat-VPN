package com.aboezzat.vpn.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ipv4UdpDnsPacketProcessorTest {
    @Test
    fun forwardsDnsPayloadAndBuildsReplyPacket() {
        val queryDns = dnsQuery()
        val expectedResponse = queryDns.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }
        var received: ByteArray? = null
        val processor = Ipv4UdpDnsPacketProcessor(
            resolver = DnsPacketResolver { query ->
                received = query
                expectedResponse
            },
            responseTtl = 42
        )

        val reply = processor.process(packet(queryDns))
        assertArrayEquals(queryDns, received)
        assertEquals(42, reply[8].toInt() and 0xff)
        assertEquals(53, u16(reply, 20))
        assertEquals(53000, u16(reply, 22))
        assertArrayEquals(expectedResponse, reply.copyOfRange(28, reply.size))
        assertEquals(0, checksum(reply, 0, 20))
    }

    @Test
    fun rejectsInvalidPacketsBeforeCallingResolver() {
        var called = false
        val processor = Ipv4UdpDnsPacketProcessor(DnsPacketResolver {
            called = true
            it
        })
        val invalid = packet(dnsQuery()).also { it[8] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            processor.process(invalid)
        }
        assertEquals(false, called)
    }

    private fun packet(dns: ByteArray): ByteArray {
        val result = ByteArray(20 + 8 + dns.size)
        result[0] = 0x45
        putU16(result, 2, result.size)
        putU16(result, 4, 0x2468)
        putU16(result, 6, 0x4000)
        result[8] = 64
        result[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(result, 12)
        byteArrayOf(10, 0, 0, 1).copyInto(result, 16)
        putU16(result, 20, 53000)
        putU16(result, 22, 53)
        putU16(result, 24, 8 + dns.size)
        dns.copyInto(result, 28)
        putU16(result, 10, checksum(result, 0, 20))
        return result
    }

    private fun dnsQuery() = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
        0x03, 0x63, 0x6f, 0x6d, 0x00, 0x00, 0x01, 0x00, 0x01
    )

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < length) {
            sum += ((bytes[offset + i].toInt() and 0xff) shl 8) or
                (bytes[offset + i + 1].toInt() and 0xff)
            i += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
