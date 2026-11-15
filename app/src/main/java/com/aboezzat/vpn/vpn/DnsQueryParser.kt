package com.aboezzat.vpn.vpn

/**
 * Strict parser for a single-question DNS query with optional EDNS(0) OPT.
 *
 * It extracts routing metadata only. It does not resolve names, forward DNS,
 * synthesize replies, or parse DNS-over-TCP length prefixes.
 */
object DnsQueryParser {
    data class Question(
        val transactionId: Int,
        val name: String,
        val type: Int,
        val queryClass: Int,
        val recursionDesired: Boolean,
        val ednsPresent: Boolean = false,
        val udpPayloadSize: Int? = null,
        val ednsVersion: Int? = null,
        val dnssecOk: Boolean = false,
        val ednsExtendedRcode: Int? = null
    )

    fun parse(packet: ByteArray): Question? {
        if (packet.size < HEADER_SIZE + 5) return null
        val id = u16(packet, 0)
        val flags = u16(packet, 2)
        val questionCount = u16(packet, 4)
        val answerCount = u16(packet, 6)
        val authorityCount = u16(packet, 8)
        val additionalCount = u16(packet, 10)

        if ((flags and 0x8000) != 0) return null
        if (((flags ushr 11) and 0x0f) != 0) return null
        if (questionCount != 1 || answerCount != 0 || authorityCount != 0) return null
        if (additionalCount !in 0..1) return null

        var offset = HEADER_SIZE
        val labels = ArrayList<String>()
        var wireNameLength = 1 // terminating root label
        while (true) {
            if (offset >= packet.size) return null
            val length = packet[offset++].toInt() and 0xff
            if (length == 0) break
            if ((length and 0xc0) != 0 || length > 63) return null
            if (offset + length > packet.size) return null
            wireNameLength += length + 1
            if (wireNameLength > 255) return null
            val labelBytes = packet.copyOfRange(offset, offset + length)
            if (labelBytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) return null
            labels += labelBytes.toString(Charsets.US_ASCII)
            offset += length
        }
        if (labels.isEmpty() || offset + 4 > packet.size) return null

        val type = u16(packet, offset)
        val queryClass = u16(packet, offset + 2)
        offset += 4
        if (type == 0 || queryClass == 0) return null

        var ednsPresent = false
        var udpPayloadSize: Int? = null
        var ednsVersion: Int? = null
        var dnssecOk = false
        var ednsExtendedRcode: Int? = null

        if (additionalCount == 1) {
            // OPT must use the root owner name and be the only additional RR.
            if (offset + 11 > packet.size || packet[offset++].toInt() != 0) return null
            if (u16(packet, offset) != OPT_TYPE) return null
            offset += 2
            val payloadSize = u16(packet, offset)
            offset += 2
            if (payloadSize < 512) return null

            val ttlHigh = packet[offset++].toInt() and 0xff
            val version = packet[offset++].toInt() and 0xff
            val ttlFlags = u16(packet, offset)
            offset += 2
            val dataLength = u16(packet, offset)
            offset += 2
            if (offset + dataLength != packet.size) return null
            if (!validEdnsOptions(packet, offset, dataLength)) return null

            ednsPresent = true
            udpPayloadSize = payloadSize
            ednsVersion = version
            dnssecOk = (ttlFlags and 0x8000) != 0
            ednsExtendedRcode = ttlHigh
        } else if (offset != packet.size) {
            return null
        }

        return Question(
            transactionId = id,
            name = labels.joinToString(".").lowercase(),
            type = type,
            queryClass = queryClass,
            recursionDesired = (flags and 0x0100) != 0,
            ednsPresent = ednsPresent,
            udpPayloadSize = udpPayloadSize,
            ednsVersion = ednsVersion,
            dnssecOk = dnssecOk,
            ednsExtendedRcode = ednsExtendedRcode
        )
    }

    private fun validEdnsOptions(packet: ByteArray, start: Int, length: Int): Boolean {
        var offset = start
        val end = start + length
        while (offset < end) {
            if (offset + 4 > end) return false
            val optionLength = u16(packet, offset + 2)
            offset += 4
            if (offset + optionLength > end) return false
            offset += optionLength
        }
        return offset == end
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or
            (data[offset + 1].toInt() and 0xff)

    private const val HEADER_SIZE = 12
    private const val OPT_TYPE = 41
}
