package com.aboezzat.vpn.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Ipv4ConnectionTupleParserTest {
    @Test
    fun parsesOutboundTcpTuple() {
        val tuple = Ipv4ConnectionTupleParser.parseOutbound(packet(protocol = 6, sourcePort = 43120, destinationPort = 443))
        assertEquals(IpTransportProtocol.TCP, tuple.protocol)
        assertEquals("10.0.0.2", tuple.local.address.hostAddress)
        assertEquals(43120, tuple.local.port)
        assertEquals("203.0.113.10", tuple.remote.address.hostAddress)
        assertEquals(443, tuple.remote.port)
    }

    @Test
    fun parsesOutboundUdpTuple() {
        val tuple = Ipv4ConnectionTupleParser.parseOutbound(packet(protocol = 17, sourcePort = 53000, destinationPort = 53))
        assertEquals(IpTransportProtocol.UDP, tuple.protocol)
        assertEquals(53000, tuple.local.port)
        assertEquals(53, tuple.remote.port)
    }

    @Test
    fun rejectsIpv6AndUnknownProtocols() {
        val ipv6 = packet(17).also { it[0] = 0x60 }
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4ConnectionTupleParser.parseOutbound(ipv6)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4ConnectionTupleParser.parseOutbound(packet(1))
        }
    }

    @Test
    fun rejectsBadIpChecksum() {
        val bytes = packet(17)
        bytes[8] = 63
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4ConnectionTupleParser.parseOutbound(bytes)
        }
    }

    @Test
    fun rejectsFragmentedPackets() {
        val bytes = packet(17)
        put16(bytes, 6, 0x2000)
        fixIpChecksum(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4ConnectionTupleParser.parseOutbound(bytes)
        }
    }

    @Test
    fun rejectsInvalidTcpDataOffset() {
        val bytes = packet(6)
        bytes[32] = 0x40
        fixIpChecksum(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4ConnectionTupleParser.parseOutbound(bytes)
        }
    }

    private fun packet(
        protocol: Int,
        sourcePort: Int = 43120,
        destinationPort: Int = 443
    ): ByteArray {
        val transportLength = if (protocol == 6) 20 else 8
        val bytes = ByteArray(20 + transportLength)
        bytes[0] = 0x45
        put16(bytes, 2, bytes.size)
        put16(bytes, 4, 0x1234)
        bytes[8] = 64
        bytes[9] = protocol.toByte()
        byteArrayOf(10, 0, 0, 2).copyInto(bytes, 12)
        byteArrayOf(203.toByte(), 0, 113, 10).copyInto(bytes, 16)
        put16(bytes, 20, sourcePort)
        put16(bytes, 22, destinationPort)
        if (protocol == 6) {
            bytes[32] = 0x50
        } else {
            put16(bytes, 24, 8)
        }
        fixIpChecksum(bytes)
        return bytes
    }

    private fun fixIpChecksum(bytes: ByteArray) {
        bytes[10] = 0
        bytes[11] = 0
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv() and 0xffff
        put16(bytes, 10, checksum)
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
