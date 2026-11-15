package com.aboezzat.vpn.vpn


/**
 * Strict IPv4/UDP DNS packet codec for a future TUN packet loop.
 *
 * It intentionally rejects fragments and non-IPv4 traffic. It does not open
 * sockets, capture traffic, or establish a VPN interface.
 */
object Ipv4UdpDnsPacketCodec {
    data class Query(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val identification: Int,
        val dscpEcn: Int,
        val dontFragment: Boolean,
        val ttl: Int,
        val ipOptions: ByteArray,
        val dnsPayload: ByteArray
    )

    fun parseQuery(packet: ByteArray): Query {
        require(packet.size >= MIN_PACKET_BYTES) { "IPv4 UDP packet is too short." }
        val versionAndIhl = u8(packet, 0)
        require((versionAndIhl ushr 4) == IPV4_VERSION) { "Only IPv4 packets are supported." }
        val headerLength = (versionAndIhl and 0x0f) * 4
        require(headerLength in IPV4_MIN_HEADER_BYTES..IPV4_MAX_HEADER_BYTES) {
            "Invalid IPv4 header length."
        }
        require(packet.size >= headerLength + UDP_HEADER_BYTES) { "IPv4 packet is truncated." }

        val totalLength = u16(packet, 2)
        require(totalLength == packet.size && totalLength >= headerLength + UDP_HEADER_BYTES) {
            "IPv4 total length does not match the packet."
        }
        require(checksum(packet, 0, headerLength) == 0) { "Invalid IPv4 header checksum." }
        require(u8(packet, 9) == UDP_PROTOCOL) { "IPv4 packet is not UDP." }

        val fragmentField = u16(packet, 6)
        require((fragmentField and FRAGMENT_MASK) == 0) {
            "Fragmented IPv4 UDP packets are not supported."
        }

        val udpOffset = headerLength
        val udpLength = u16(packet, udpOffset + 4)
        require(udpLength >= UDP_HEADER_BYTES && headerLength + udpLength == totalLength) {
            "Invalid UDP length."
        }
        val sourcePort = u16(packet, udpOffset)
        val destinationPort = u16(packet, udpOffset + 2)
        require(destinationPort == DNS_PORT) { "UDP destination is not the DNS port." }

        val sourceAddress = packet.copyOfRange(12, 16)
        val destinationAddress = packet.copyOfRange(16, 20)
        val udpChecksum = u16(packet, udpOffset + 6)
        if (udpChecksum != 0) {
            require(verifyUdpChecksum(sourceAddress, destinationAddress, packet, udpOffset, udpLength)) {
                "Invalid UDP checksum."
            }
        }

        val dns = packet.copyOfRange(udpOffset + UDP_HEADER_BYTES, totalLength)
        require(DnsQueryParser.parse(dns) != null) { "Malformed or unsupported DNS query." }

        return Query(
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            identification = u16(packet, 4),
            dscpEcn = u8(packet, 1),
            dontFragment = (fragmentField and DONT_FRAGMENT_FLAG) != 0,
            ttl = u8(packet, 8),
            ipOptions = packet.copyOfRange(IPV4_MIN_HEADER_BYTES, headerLength),
            dnsPayload = dns
        )
    }

    fun buildResponse(query: Query, dnsResponse: ByteArray, ttl: Int = DEFAULT_TTL): ByteArray {
        require(ttl in 1..255) { "TTL must be between 1 and 255." }
        require(dnsResponse.size in DnsTcpForwarder.DNS_HEADER_BYTES..DnsTcpForwarder.MAX_DNS_MESSAGE_BYTES) {
            "DNS response length is outside the supported range."
        }
        val validDnsResponse = try {
            DnsTcpForwarder.validateResponse(query.dnsPayload, dnsResponse)
            true
        } catch (_: Exception) {
            false
        }
        require(validDnsResponse) { "DNS response does not match the original query." }

        val ipHeaderLength = IPV4_MIN_HEADER_BYTES + query.ipOptions.size
        require(ipHeaderLength <= IPV4_MAX_HEADER_BYTES && ipHeaderLength % 4 == 0) {
            "Invalid saved IPv4 options."
        }
        val udpLength = UDP_HEADER_BYTES + dnsResponse.size
        val totalLength = ipHeaderLength + udpLength
        require(totalLength <= MAX_IPV4_PACKET_BYTES) { "DNS response exceeds the IPv4 packet limit." }

        val packet = ByteArray(totalLength)
        packet[0] = ((IPV4_VERSION shl 4) or (ipHeaderLength / 4)).toByte()
        packet[1] = query.dscpEcn.toByte()
        putU16(packet, 2, totalLength)
        putU16(packet, 4, query.identification)
        putU16(packet, 6, if (query.dontFragment) DONT_FRAGMENT_FLAG else 0)
        packet[8] = ttl.toByte()
        packet[9] = UDP_PROTOCOL.toByte()
        query.destinationAddress.copyInto(packet, 12)
        query.sourceAddress.copyInto(packet, 16)
        query.ipOptions.copyInto(packet, IPV4_MIN_HEADER_BYTES)

        val udpOffset = ipHeaderLength
        putU16(packet, udpOffset, query.destinationPort)
        putU16(packet, udpOffset + 2, query.sourcePort)
        putU16(packet, udpOffset + 4, udpLength)
        dnsResponse.copyInto(packet, udpOffset + UDP_HEADER_BYTES)

        var udpSum = udpChecksum(
            query.destinationAddress,
            query.sourceAddress,
            packet,
            udpOffset,
            udpLength
        )
        if (udpSum == 0) udpSum = 0xffff
        putU16(packet, udpOffset + 6, udpSum)
        putU16(packet, 10, checksum(packet, 0, ipHeaderLength))
        return packet
    }

    private fun verifyUdpChecksum(
        source: ByteArray,
        destination: ByteArray,
        packet: ByteArray,
        offset: Int,
        length: Int
    ): Boolean = udpChecksum(source, destination, packet, offset, length) == 0

    private fun udpChecksum(
        source: ByteArray,
        destination: ByteArray,
        packet: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        for (i in 0 until 4 step 2) {
            sum += ((source[i].toInt() and 0xff) shl 8) or (source[i + 1].toInt() and 0xff)
            sum += ((destination[i].toInt() and 0xff) shl 8) or (destination[i + 1].toInt() and 0xff)
        }
        sum += UDP_PROTOCOL
        sum += length
        sum = addWords(sum, packet, offset, length)
        return finishChecksum(sum)
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int =
        finishChecksum(addWords(0L, bytes, offset, length))

    private fun addWords(initial: Long, bytes: ByteArray, offset: Int, length: Int): Long {
        var sum = initial
        var index = 0
        while (index + 1 < length) {
            sum += ((bytes[offset + index].toInt() and 0xff) shl 8) or
                (bytes[offset + index + 1].toInt() and 0xff)
            index += 2
        }
        if (index < length) sum += (bytes[offset + index].toInt() and 0xff) shl 8
        return sum
    }

    private fun finishChecksum(value: Long): Int {
        var sum = value
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.toInt().inv() and 0xffff
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    const val DNS_PORT = 53
    const val DEFAULT_TTL = 64
    private const val IPV4_VERSION = 4
    private const val IPV4_MIN_HEADER_BYTES = 20
    private const val IPV4_MAX_HEADER_BYTES = 60
    private const val UDP_HEADER_BYTES = 8
    private const val MIN_PACKET_BYTES = IPV4_MIN_HEADER_BYTES + UDP_HEADER_BYTES + DnsTcpForwarder.DNS_HEADER_BYTES
    private const val UDP_PROTOCOL = 17
    private const val DONT_FRAGMENT_FLAG = 0x4000
    private const val FRAGMENT_MASK = 0x3fff
    private const val MAX_IPV4_PACKET_BYTES = 65_535
}
