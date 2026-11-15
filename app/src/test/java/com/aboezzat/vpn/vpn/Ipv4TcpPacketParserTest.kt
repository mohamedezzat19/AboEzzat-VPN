package com.aboezzat.vpn.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class Ipv4TcpPacketParserTest {
    @Test
    fun parsesTcpHeaderFlagsSequenceAndPayload() {
        val bytes = tcpPacket(payload = byteArrayOf(1, 2, 3, 4))
        val parsed = Ipv4TcpPacketParser.parse(bytes)

        assertEquals(IpTransportProtocol.TCP, parsed.tuple.protocol)
        assertEquals(43120, parsed.tuple.local.port)
        assertEquals(443, parsed.tuple.remote.port)
        assertEquals(0xf1234567L, parsed.sequenceNumber)
        assertEquals(0x89abcdefL, parsed.acknowledgementNumber)
        assertEquals(0x5018, parsed.flags)
        assertTrue(parsed.isPsh)
        assertTrue(parsed.isAck)
        assertFalse(parsed.isSyn)
        assertEquals(32768, parsed.windowSize)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parsed.payload)
        assertEquals(20, parsed.tcpHeaderLength)
        assertTrue(parsed.tcpOptions.isEmpty())
    }

    @Test
    fun parsesTcpOptionsAndNsFlag() {
        val bytes = tcpPacket(
            options = byteArrayOf(1, 1, 0, 0),
            flags = Ipv4TcpPacketParser.FLAG_SYN or Ipv4TcpPacketParser.FLAG_NS
        )
        val parsed = Ipv4TcpPacketParser.parse(bytes)
        assertEquals(24, parsed.tcpHeaderLength)
        assertTrue(parsed.isSyn)
        assertTrue(parsed.isNs)
        assertArrayEquals(byteArrayOf(1, 1, 0, 0), parsed.tcpOptions)
    }

    @Test
    fun rejectsInvalidTcpChecksum() {
        val bytes = tcpPacket(payload = byteArrayOf(9))
        bytes[bytes.lastIndex] = 8
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4TcpPacketParser.parse(bytes)
        }
    }

    @Test
    fun rejectsMalformedTcpOptionLength() {
        val bytes = tcpPacket(options = byteArrayOf(2, 1, 0, 0))
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4TcpPacketParser.parse(bytes)
        }
    }

    @Test
    fun rejectsUdpPackets() {
        val bytes = tcpPacket()
        bytes[9] = 17
        fixIpChecksum(bytes)
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4TcpPacketParser.parse(bytes)
        }
    }

    private fun tcpPacket(
        payload: ByteArray = byteArrayOf(),
        options: ByteArray = byteArrayOf(),
        flags: Int = Ipv4TcpPacketParser.FLAG_PSH or Ipv4TcpPacketParser.FLAG_ACK
    ): ByteArray {
        require(options.size % 4 == 0)
        val ipLength = 20
        val tcpHeaderLength = 20 + options.size
        val tcpLength = tcpHeaderLength + payload.size
        val bytes = ByteArray(ipLength + tcpLength)
        bytes[0] = 0x45
        put16(bytes, 2, bytes.size)
        put16(bytes, 4, 0x1234)
        bytes[8] = 64
        bytes[9] = 6
        byteArrayOf(10, 0, 0, 2).copyInto(bytes, 12)
        byteArrayOf(203.toByte(), 0, 113, 10).copyInto(bytes, 16)

        val tcp = ipLength
        put16(bytes, tcp, 43120)
        put16(bytes, tcp + 2, 443)
        put32(bytes, tcp + 4, 0xf1234567L)
        put32(bytes, tcp + 8, 0x89abcdefL)
        bytes[tcp + 12] = ((tcpHeaderLength / 4) shl 4 or ((flags ushr 8) and 1)).toByte()
        bytes[tcp + 13] = flags.toByte()
        put16(bytes, tcp + 14, 32768)
        put16(bytes, tcp + 18, 0)
        options.copyInto(bytes, tcp + 20)
        payload.copyInto(bytes, tcp + tcpHeaderLength)
        put16(bytes, tcp + 16, tcpChecksum(bytes, tcp, tcpLength))
        fixIpChecksum(bytes)
        return bytes
    }

    private fun tcpChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        for (i in 12 until 20 step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        }
        sum += 6
        sum += length
        var index = 0
        while (index + 1 < length) {
            sum += ((bytes[offset + index].toInt() and 0xff) shl 8) or
                (bytes[offset + index + 1].toInt() and 0xff)
            index += 2
        }
        if (index < length) sum += (bytes[offset + index].toInt() and 0xff) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun fixIpChecksum(bytes: ByteArray) {
        bytes[10] = 0
        bytes[11] = 0
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((bytes[i].toInt() and 0xff) shl 8) or (bytes[i + 1].toInt() and 0xff)
        }
        while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
        put16(bytes, 10, sum.inv() and 0xffff)
    }

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        put16(bytes, offset, (value ushr 16).toInt() and 0xffff)
        put16(bytes, offset + 2, value.toInt() and 0xffff)
    }
}
