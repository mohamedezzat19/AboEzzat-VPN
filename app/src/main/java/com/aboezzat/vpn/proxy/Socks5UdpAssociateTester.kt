package com.aboezzat.vpn.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Verifies SOCKS5 UDP support with a real DNS datagram round-trip.
 * The TCP control connection remains open for the lifetime of the UDP association.
 */
data class ProxyUdpTestResult(
    val supported: Boolean,
    val message: String,
    val relayAddress: String? = null,
    val relayPort: Int? = null
)

object Socks5UdpAssociateTester {
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000
    private const val DNS_TRANSACTION_ID = 0xA1B2

    fun test(
        profile: ProxyProfile,
        protectSocket: ((Socket) -> Boolean)? = null,
        protectDatagramSocket: ((DatagramSocket) -> Boolean)? = null
    ): ProxyUdpTestResult {
        profile.validate()?.let { return ProxyUdpTestResult(false, it) }
        if (profile.protocol != ProxyProtocol.SOCKS5) {
            return ProxyUdpTestResult(
                false,
                "UDP is not available through HTTP CONNECT. This upstream mode carries TCP only."
            )
        }

        return try {
            Socket().use { control ->
                if (protectSocket != null && !protectSocket(control)) {
                    throw IOException("Could not protect the SOCKS5 control socket from VPN routing.")
                }
                control.connect(InetSocketAddress(profile.host, profile.port), CONNECT_TIMEOUT_MS)
                control.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(control.getInputStream())
                val output = BufferedOutputStream(control.getOutputStream())
                negotiateAuthentication(profile, input, output)

                // Keep this TCP control socket open until the UDP relay test completes.
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                if (input.read() != 5) throw IOException("Invalid SOCKS5 UDP ASSOCIATE response.")
                val reply = input.read()
                input.read() // RSV
                val addressType = input.read()
                if (reply != 0) throw IOException("SOCKS5 UDP ASSOCIATE rejected (reply code $reply).")

                val relayHost = when (addressType) {
                    1 -> InetAddress.getByAddress(readExactly(input, 4)).hostAddress
                    4 -> InetAddress.getByAddress(readExactly(input, 16)).hostAddress
                    3 -> {
                        val length = input.read()
                        if (length !in 1..255) throw IOException("Invalid SOCKS5 relay domain.")
                        String(readExactly(input, length), StandardCharsets.US_ASCII)
                    }
                    else -> throw IOException("Unknown SOCKS5 relay address type.")
                }
                val portBytes = readExactly(input, 2)
                val relayPort = ((portBytes[0].toInt() and 0xff) shl 8) or
                    (portBytes[1].toInt() and 0xff)
                if (relayPort == 0) throw IOException("SOCKS5 server returned an invalid UDP relay port.")

                DatagramSocket(null).use { datagram ->
                    if (protectDatagramSocket != null && !protectDatagramSocket(datagram)) {
                        throw IOException("Could not protect the UDP datagram socket from VPN routing.")
                    }
                    datagram.reuseAddress = true
                    datagram.bind(InetSocketAddress(0))
                    datagram.soTimeout = READ_TIMEOUT_MS

                    val relay = InetSocketAddress(InetAddress.getByName(relayHost), relayPort)
                    val dnsQuery = buildDnsQuery()
                    val request = buildSocksUdpRequest(dnsQuery)
                    datagram.send(DatagramPacket(request, request.size, relay))

                    val responseBuffer = ByteArray(2048)
                    val response = DatagramPacket(responseBuffer, responseBuffer.size)
                    datagram.receive(response)
                    val dnsPayload = extractDnsPayload(response.data.copyOf(response.length))
                    if (dnsPayload.size < 12) throw IOException("UDP relay returned an incomplete DNS response.")
                    val transactionId = ((dnsPayload[0].toInt() and 0xff) shl 8) or
                        (dnsPayload[1].toInt() and 0xff)
                    if (transactionId != DNS_TRANSACTION_ID || (dnsPayload[2].toInt() and 0x80) == 0) {
                        throw IOException("UDP relay response did not match the DNS test request.")
                    }
                }

                ProxyUdpTestResult(
                    true,
                    "SOCKS5 UDP datagram round-trip verified with a DNS response.",
                    relayHost,
                    relayPort
                )
            }
        } catch (_: java.net.SocketTimeoutException) {
            ProxyUdpTestResult(false, "SOCKS5 UDP relay timed out: no DNS response was received.")
        } catch (error: Exception) {
            ProxyUdpTestResult(
                false,
                error.message?.take(140) ?: "SOCKS5 UDP datagram test failed."
            )
        }
    }

    private fun buildDnsQuery(): ByteArray {
        val name = byteArrayOf(
            7, 101, 120, 97, 109, 112, 108, 101,
            3, 99, 111, 109, 0
        )
        return byteArrayOf(
            (DNS_TRANSACTION_ID ushr 8).toByte(), DNS_TRANSACTION_ID.toByte(),
            0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ) + name + byteArrayOf(0x00, 0x01, 0x00, 0x01)
    }

    private fun buildSocksUdpRequest(payload: ByteArray): ByteArray =
        byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + payload

    private fun extractDnsPayload(packet: ByteArray): ByteArray {
        if (packet.size < 10 || packet[0].toInt() != 0 || packet[1].toInt() != 0) {
            throw IOException("Invalid SOCKS5 UDP relay response header.")
        }
        if (packet[2].toInt() != 0) throw IOException("Fragmented SOCKS5 UDP response is unsupported.")
        val addressLength = when (packet[3].toInt() and 0xff) {
            1 -> 4
            4 -> 16
            3 -> {
                if (packet.size < 5) throw IOException("Invalid UDP response domain header.")
                1 + (packet[4].toInt() and 0xff)
            }
            else -> throw IOException("Unknown UDP response address type.")
        }
        val payloadOffset = 4 + addressLength + 2
        if (packet.size < payloadOffset + 12) throw IOException("UDP relay response has no DNS payload.")
        return packet.copyOfRange(payloadOffset, packet.size)
    }

    private fun negotiateAuthentication(
        profile: ProxyProfile,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val hasCredentials = !profile.username.isNullOrEmpty()
        output.write(if (hasCredentials) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        val version = input.read()
        val method = input.read()
        if (version != 5) throw IOException("Server is not a SOCKS5 proxy.")
        when (method) {
            0 -> Unit
            2 -> {
                if (!hasCredentials) throw IOException("SOCKS5 server requires username and password.")
                val username = profile.username!!.toByteArray(StandardCharsets.UTF_8)
                val password = profile.password.orEmpty().toByteArray(StandardCharsets.UTF_8)
                require(username.size in 1..255 && password.size in 1..255) {
                    "SOCKS5 credentials must be 1–255 bytes."
                }
                output.write(byteArrayOf(1, username.size.toByte()))
                output.write(username)
                output.write(password.size)
                output.write(password)
                output.flush()
                if (input.read() != 1 || input.read() != 0) throw IOException("SOCKS5 authentication failed.")
            }
            0xff -> throw IOException("SOCKS5 server rejected all authentication methods.")
            else -> throw IOException("Unsupported SOCKS5 authentication method.")
        }
    }

    private fun readExactly(input: BufferedInputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        for (index in 0 until count) {
            val value = input.read()
            if (value < 0) throw IOException("Incomplete SOCKS5 UDP ASSOCIATE response.")
            result[index] = value.toByte()
        }
        return result
    }
}
