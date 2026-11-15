package com.aboezzat.vpn.vpn

import java.net.InetAddress

/**
 * Extracts the 5-tuple needed for Android's connection-owner UID lookup from
 * an outbound IPv4 TCP or UDP packet read from a future VpnService TUN.
 *
 * Fragmented packets are rejected because the transport ports may not exist
 * in every fragment. IPv6 and non-TCP/UDP protocols are not supported here.
 */
object Ipv4ConnectionTupleParser {
    fun parseOutbound(packet: ByteArray): NetworkConnectionTuple {
        require(packet.size >= IPV4_MIN_HEADER_BYTES) { "IPv4 packet is too short." }
        val versionAndIhl = u8(packet, 0)
        require((versionAndIhl ushr 4) == IPV4_VERSION) { "Only IPv4 is supported." }

        val ipHeaderLength = (versionAndIhl and 0x0f) * 4
        require(ipHeaderLength in IPV4_MIN_HEADER_BYTES..IPV4_MAX_HEADER_BYTES) {
            "Invalid IPv4 header length."
        }
        require(packet.size >= ipHeaderLength) { "IPv4 header is truncated." }

        val totalLength = u16(packet, 2)
        require(totalLength == packet.size && totalLength >= ipHeaderLength) {
            "IPv4 total length does not match packet size."
        }
        require(checksum(packet, 0, ipHeaderLength) == 0) {
            "Invalid IPv4 header checksum."
        }

        val fragmentField = u16(packet, 6)
        require((fragmentField and RESERVED_FLAG) == 0) {
            "IPv4 reserved fragment flag must be zero."
        }
        require((fragmentField and FRAGMENT_MASK) == 0) {
            "Fragmented IPv4 packets are not supported for UID lookup."
        }

        val protocol = when (u8(packet, 9)) {
            TCP_PROTOCOL -> IpTransportProtocol.TCP
            UDP_PROTOCOL -> IpTransportProtocol.UDP
            else -> throw IllegalArgumentException("Only TCP and UDP packets can be attributed.")
        }

        val transportOffset = ipHeaderLength
        val transportLength = totalLength - ipHeaderLength
        require(transportLength >= if (protocol == IpTransportProtocol.TCP) TCP_MIN_HEADER_BYTES else UDP_HEADER_BYTES) {
            "Transport header is truncated."
        }

        val sourcePort = u16(packet, transportOffset)
        val destinationPort = u16(packet, transportOffset + 2)
        require(sourcePort in 1..65535 && destinationPort in 1..65535) {
            "Invalid transport port."
        }

        when (protocol) {
            IpTransportProtocol.TCP -> {
                val tcpHeaderLength = (u8(packet, transportOffset + 12) ushr 4) * 4
                require(tcpHeaderLength in TCP_MIN_HEADER_BYTES..transportLength) {
                    "Invalid TCP data offset."
                }
            }
            IpTransportProtocol.UDP -> {
                val udpLength = u16(packet, transportOffset + 4)
                require(udpLength == transportLength && udpLength >= UDP_HEADER_BYTES) {
                    "Invalid UDP length."
                }
            }
        }

        val localAddress = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val remoteAddress = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        return NetworkConnectionTuple(
            protocol = protocol,
            local = java.net.InetSocketAddress(localAddress, sourcePort),
            remote = java.net.InetSocketAddress(remoteAddress, destinationPort)
        )
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
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

    private fun u8(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private const val IPV4_VERSION = 4
    private const val IPV4_MIN_HEADER_BYTES = 20
    private const val IPV4_MAX_HEADER_BYTES = 60
    private const val TCP_MIN_HEADER_BYTES = 20
    private const val UDP_HEADER_BYTES = 8
    private const val TCP_PROTOCOL = 6
    private const val UDP_PROTOCOL = 17
    private const val RESERVED_FLAG = 0x8000
    private const val FRAGMENT_MASK = 0x3fff
}
