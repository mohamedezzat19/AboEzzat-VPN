package com.aboezzat.vpn.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Ipv4UdpDnsPacketLoopTest {
    @Test
    fun skipsInvalidDnsPacketAndContinuesWithNextPacket() {
        val query = dnsQuery()
        val validPacket = makePacket(query)
        val invalidPacket = validPacket.copyOf().also { it[0] = 0x65 }
        val source = FakeSource(listOf(invalidPacket, validPacket))
        val written = mutableListOf<ByteArray>()
        var resolverCalls = 0
        val processor = Ipv4UdpDnsPacketProcessor(resolver = DnsPacketResolver { dns ->
            resolverCalls++
            dns.copyOf().also {
                it[2] = 0x81.toByte()
                it[3] = 0x80.toByte()
            }
        })
        val loop = Ipv4UdpDnsPacketLoop(
            source = source,
            sink = DnsPacketSink { written += it },
            processor = processor
        )

        loop.run()

        assertEquals(1, resolverCalls)
        assertEquals(1L, loop.processedPackets)
        assertEquals(1L, loop.failedPackets)
        assertEquals(1, written.size)
        assertTrue(source.closed)
        assertEquals(53, u16(written.single(), 20))
        assertEquals(53000, u16(written.single(), 22))
    }

    @Test
    fun rejectsSecondRunAttempt() {
        val source = FakeSource(emptyList())
        val loop = Ipv4UdpDnsPacketLoop(
            source = source,
            sink = DnsPacketSink { },
            processor = Ipv4UdpDnsPacketProcessor(resolver = DnsPacketResolver { it })
        )
        loop.run()
        try {
            loop.run()
            fail("Second run must be rejected")
        } catch (_: IllegalStateException) {
            // Expected: a packet source has exactly one owning reader.
        }
        assertTrue(source.closed)
    }

    @Test
    fun stopClosesSourceAndRunExitsAfterCurrentRead() {
        val source = FakeSource(emptyList())
        val loop = Ipv4UdpDnsPacketLoop(
            source = source,
            sink = DnsPacketSink { throw AssertionError("No packet expected") },
            processor = Ipv4UdpDnsPacketProcessor { it }
        )

        loop.stop()
        loop.run()

        assertTrue(source.closed)
        assertEquals(0L, loop.processedPackets)
    }

    private class FakeSource(packets: List<ByteArray>) : DnsPacketSource {
        private val queue = ArrayDeque(packets)
        var closed = false
            private set

        override fun readPacket(): ByteArray? = if (closed) null else queue.removeFirstOrNull()
        override fun close() { closed = true }
    }

    private fun makePacket(dns: ByteArray): ByteArray {
        val packet = ByteArray(20 + 8 + dns.size)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        putU16(packet, 4, 0x1234)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        byteArrayOf(192.toByte(), 0, 2, 10).copyInto(packet, 12)
        byteArrayOf(192.toByte(), 0, 2, 53).copyInto(packet, 16)
        putU16(packet, 20, 53000)
        putU16(packet, 22, 53)
        putU16(packet, 24, 8 + dns.size)
        dns.copyInto(packet, 28)
        putU16(packet, 10, checksum(packet, 20))
        return packet
    }

    private fun checksum(bytes: ByteArray, length: Int): Int {
        var sum = 0L
        for (i in 0 until length step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or
                (bytes[i + 1].toInt() and 0xff)
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun dnsQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
        'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01
    )
}
