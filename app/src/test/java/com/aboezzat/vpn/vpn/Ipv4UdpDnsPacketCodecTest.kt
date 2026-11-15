package com.aboezzat.vpn.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4UdpDnsPacketCodecTest {
    @Test
    fun parsesDnsUdpQueryAndBuildsReverseResponseWithValidChecksums() {
        val dns = dnsQuery()
        val packet = makeIpv4UdpPacket(
            source = byteArrayOf(192.toByte(), 0, 2, 10),
            destination = byteArrayOf(192.toByte(), 0, 2, 53),
            sourcePort = 53000,
            destinationPort = 53,
            dns = dns
        )
        val parsed = Ipv4UdpDnsPacketCodec.parseQuery(packet)
        assertEquals(53000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(dns, parsed.dnsPayload)

        val responseDns = dns.copyOf().also {
            it[2] = 0x81.toByte()
            it[3] = 0x80.toByte()
        }
        val response = Ipv4UdpDnsPacketCodec.buildResponse(parsed, responseDns)
        val parsedResponse = Ipv4UdpDnsPacketCodec.parseResponseForTest(response)
        assertEquals(53, parsedResponse.first)
        assertEquals(53000, parsedResponse.second)
        assertArrayEquals(responseDns, parsedResponse.third)
        assertTrue("Generated UDP checksum must validate", udpChecksumValid(response))
    }

    @Test
    fun rejectsReservedIpv4Flag() {
        val packet = makeIpv4UdpPacket(
            byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 1),
            50000, 53, dnsQuery()
        )
        putU16(packet, 6, 0x8000)
        putU16(packet, 10, 0)
        putU16(packet, 10, ipv4Checksum(packet, 20))

        assertThrows(IllegalArgumentException::class.java) {
            Ipv4UdpDnsPacketCodec.parseQuery(packet)
        }
    }

    @Test
    fun rejectsWrongDestinationPortAndCorruptHeaderChecksum() {
        val dns = dnsQuery()
        val wrongPort = makeIpv4UdpPacket(
            byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 1),
            50000, 5353, dns
        )
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4UdpDnsPacketCodec.parseQuery(wrongPort)
        }

        val corrupt = makeIpv4UdpPacket(
            byteArrayOf(10, 0, 0, 2), byteArrayOf(10, 0, 0, 1),
            50000, 53, dns
        )
        corrupt[8] = 10
        assertThrows(IllegalArgumentException::class.java) {
            Ipv4UdpDnsPacketCodec.parseQuery(corrupt)
        }
    }

    private fun makeIpv4UdpPacket(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        dns: ByteArray
    ): ByteArray {
        val packet = ByteArray(20 + 8 + dns.size)
        packet[0] = 0x45
        putU16(packet, 2, packet.size)
        putU16(packet, 4, 0x1234)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        val udp = 20
        putU16(packet, udp, sourcePort)
        putU16(packet, udp + 2, destinationPort)
        putU16(packet, udp + 4, 8 + dns.size)
        dns.copyInto(packet, udp + 8)
        putU16(packet, 10, ipv4Checksum(packet, 20))
        return packet
    }

    private fun udpChecksumValid(packet: ByteArray): Boolean {
        val ihl = (packet[0].toInt() and 0x0f) * 4
        val udpLength = ((packet[ihl + 4].toInt() and 0xff) shl 8) or
            (packet[ihl + 5].toInt() and 0xff)
        var sum = 0L
        for (offset in listOf(12, 16)) {
            for (i in offset until offset + 4 step 2) {
                sum += ((packet[i].toInt() and 0xff) shl 8) or
                    (packet[i + 1].toInt() and 0xff)
            }
        }
        sum += 17
        sum += udpLength
        for (i in ihl until ihl + udpLength step 2) {
            val high = (packet[i].toInt() and 0xff) shl 8
            val low = if (i + 1 < ihl + udpLength) packet[i + 1].toInt() and 0xff else 0
            sum += high or low
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return (sum.toInt() and 0xffff) == 0xffff
    }

    private fun ipv4Checksum(bytes: ByteArray, length: Int): Int {
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

    private fun dnsQuery(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
        'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01
    )
}

/** Test-only minimal decoder to check reply ports and DNS payload. */
private fun Ipv4UdpDnsPacketCodec.parseResponseForTest(packet: ByteArray): Triple<Int, Int, ByteArray> {
    val ihl = (packet[0].toInt() and 0x0f) * 4
    val sourcePort = ((packet[ihl].toInt() and 0xff) shl 8) or (packet[ihl + 1].toInt() and 0xff)
    val destinationPort = ((packet[ihl + 2].toInt() and 0xff) shl 8) or (packet[ihl + 3].toInt() and 0xff)
    return Triple(sourcePort, destinationPort, packet.copyOfRange(ihl + 8, packet.size))
}
