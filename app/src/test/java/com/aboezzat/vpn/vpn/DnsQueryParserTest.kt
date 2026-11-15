package com.aboezzat.vpn.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsQueryParserTest {
    @Test
    fun parsesSingleAQuestion() {
        val parsed = DnsQueryParser.parse(query("Example.COM", type = 1, rd = true))
        assertEquals(0x1234, parsed?.transactionId)
        assertEquals("example.com", parsed?.name)
        assertEquals(1, parsed?.type)
        assertEquals(1, parsed?.queryClass)
        assertTrue(parsed?.recursionDesired == true)
        assertFalse(parsed?.ednsPresent ?: true)
    }

    @Test
    fun parsesAaaaQuestionAndRecursionFlag() {
        val parsed = DnsQueryParser.parse(query("ipv6.example", type = 28, rd = false))
        assertEquals(28, parsed?.type)
        assertFalse(parsed?.recursionDesired ?: true)
    }

    @Test
    fun parsesEdnsOptAndDnssecOk() {
        val parsed = DnsQueryParser.parse(query("secure.example", 1, true, includeEdns = true))
        assertTrue(parsed?.ednsPresent == true)
        assertEquals(1232, parsed?.udpPayloadSize)
        assertEquals(0, parsed?.ednsVersion)
        assertTrue(parsed?.dnssecOk == true)
        assertEquals(0, parsed?.ednsExtendedRcode)
    }

    @Test
    fun rejectsMalformedEdnsAndUnsupportedQueries() {
        val valid = query("example.com", 1, true)
        val response = valid.copyOf().also { it[2] = 0x81.toByte() }
        assertNull(DnsQueryParser.parse(response))
        assertNull(DnsQueryParser.parse(valid.copyOf(valid.size - 2)))
        assertNull(DnsQueryParser.parse(ByteArray(10)))
        val multipleQuestions = valid.copyOf().also { it[5] = 2 }
        assertNull(DnsQueryParser.parse(multipleQuestions))
        val compressedName = valid.copyOf().also { it[12] = 0xc0.toByte() }
        assertNull(DnsQueryParser.parse(compressedName))

        val badOpt = query("example.com", 1, true, includeEdns = true)
            .also { it[it.size - 1] = 1 } // option data has no option header
        assertNull(DnsQueryParser.parse(badOpt))
    }

    private fun query(
        name: String,
        type: Int,
        rd: Boolean,
        includeEdns: Boolean = false
    ): ByteArray {
        val bytes = ArrayList<Byte>()
        fun add(value: Int) { bytes += value.toByte() }
        add(0x12); add(0x34)
        add(if (rd) 0x01 else 0x00); add(0x00)
        add(0x00); add(0x01) // QDCOUNT
        repeat(4) { add(0) } // ANCOUNT and NSCOUNT
        add(0); add(if (includeEdns) 1 else 0) // ARCOUNT
        name.split(".").forEach { label ->
            add(label.length)
            label.toByteArray(Charsets.US_ASCII).forEach { bytes += it }
        }
        add(0)
        add(type shr 8); add(type and 0xff)
        add(0); add(1) // IN

        if (includeEdns) {
            add(0) // root owner name
            add(0); add(41) // OPT
            add(0x04); add(0xd0) // UDP payload size 1232
            add(0); add(0) // extended RCODE and EDNS version
            add(0x80); add(0) // DO bit
            add(0); add(0) // RDLEN
        }
        return bytes.toByteArray()
    }
}
