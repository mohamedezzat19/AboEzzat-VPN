package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyTcpTunnel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/** Common contract used by the local UDP bridge for one DNS request/response. */
fun interface DnsRequestForwarder {
    @Throws(IOException::class)
    fun forward(
        profile: ProxyProfile,
        resolverHost: String,
        query: ByteArray,
        protectSocket: ((Socket) -> Boolean)?
    ): ByteArray
}

/**
 * Sends DNS over TCP through the selected upstream HTTP CONNECT or SOCKS5 proxy.
 * The resolver connection is not encrypted; use DnsOverTlsForwarder when the
 * selected resolver supports DNS-over-TLS.
 */
class DnsTcpForwarder(
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val maxMessageBytes: Int = MAX_DNS_MESSAGE_BYTES
) : DnsRequestForwarder {
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
        require(maxMessageBytes in DNS_HEADER_BYTES..MAX_DNS_MESSAGE_BYTES)
    }

    @Throws(IOException::class)
    override fun forward(
        profile: ProxyProfile,
        resolverHost: String,
        query: ByteArray,
        protectSocket: ((Socket) -> Boolean)?
    ): ByteArray {
        validateQuery(query, maxMessageBytes)
        val socket = ProxyTcpTunnel.open(
            profile = profile,
            destinationHost = resolverHost,
            destinationPort = DNS_TCP_PORT,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            protectSocket = protectSocket
        )
        socket.use {
            it.soTimeout = readTimeoutMs
            val input = DataInputStream(it.getInputStream())
            val output = DataOutputStream(it.getOutputStream())
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val responseLength = input.readUnsignedShort()
            if (responseLength !in DNS_HEADER_BYTES..maxMessageBytes) {
                throw IOException("DNS resolver returned an invalid message length.")
            }
            val response = ByteArray(responseLength)
            input.readFully(response)
            validateResponse(query, response)
            return response
        }
    }

    companion object {
        const val DNS_TCP_PORT = 53
        const val DEFAULT_TIMEOUT_MS = 7_000
        const val DNS_HEADER_BYTES = 12
        const val MAX_DNS_MESSAGE_BYTES = 65_535

        internal fun validateQuery(query: ByteArray, maxMessageBytes: Int) {
            require(query.size in DNS_HEADER_BYTES..maxMessageBytes) {
                "DNS query length is outside the supported range."
            }
            require(DnsQueryParser.parse(query) != null) {
                "DNS query is malformed or uses an unsupported query layout."
            }
        }

        internal fun validateResponse(query: ByteArray, response: ByteArray) {
            if (response.size < DNS_HEADER_BYTES) throw IOException("DNS response is too short.")
            val queryId = readU16(query, 0)
            val responseId = readU16(response, 0)
            val flags = readU16(response, 2)
            if (responseId != queryId) throw IOException("DNS response transaction ID mismatch.")
            if ((flags and 0x8000) == 0) throw IOException("DNS upstream returned a query, not a response.")
            if (((flags ushr 11) and 0x0f) != 0) throw IOException("DNS response opcode mismatch.")
        }

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or
                (bytes[offset + 1].toInt() and 0xff)
    }
}
