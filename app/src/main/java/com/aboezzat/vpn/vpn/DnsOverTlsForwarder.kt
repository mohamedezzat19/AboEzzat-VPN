package com.aboezzat.vpn.vpn

import com.aboezzat.vpn.proxy.ProxyProfile
import com.aboezzat.vpn.proxy.ProxyTcpTunnel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS-over-TLS (RFC 7858) through the configured upstream proxy.
 *
 * Uses the platform trust store and hostname verification. No certificate
 * verification is disabled and no resolver is selected implicitly.
 */
class DnsOverTlsForwarder(
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val maxMessageBytes: Int = DnsTcpForwarder.MAX_DNS_MESSAGE_BYTES,
    private val socketFactory: SSLSocketFactory =
        SSLSocketFactory.getDefault() as SSLSocketFactory
) : DnsRequestForwarder {
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
        require(maxMessageBytes in DnsTcpForwarder.DNS_HEADER_BYTES..DnsTcpForwarder.MAX_DNS_MESSAGE_BYTES)
    }

    fun forward(
        profile: ProxyProfile,
        resolverHost: String,
        query: ByteArray
    ): ByteArray = forward(profile, resolverHost, query, null)

    @Throws(IOException::class)
    override fun forward(
        profile: ProxyProfile,
        resolverHost: String,
        query: ByteArray,
        protectSocket: ((Socket) -> Boolean)?
    ): ByteArray {
        require(resolverHost.isNotBlank() && !resolverHost.any(Char::isWhitespace)) {
            "A DNS-over-TLS resolver hostname or IP is required."
        }
        DnsTcpForwarder.validateQuery(query, maxMessageBytes)

        val tunnel = ProxyTcpTunnel.open(
            profile = profile,
            destinationHost = resolverHost,
            destinationPort = DNS_OVER_TLS_PORT,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            protectSocket = protectSocket
        )
        tunnel.use { rawSocket ->
            val tls = socketFactory.createSocket(
                rawSocket,
                resolverHost,
                DNS_OVER_TLS_PORT,
                true
            ) as SSLSocket
            tls.use { secureSocket ->
                secureSocket.soTimeout = readTimeoutMs
                val parameters = secureSocket.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                if (!isIpLiteral(resolverHost)) {
                    parameters.serverNames = listOf(SNIHostName(resolverHost))
                }
                secureSocket.sslParameters = parameters
                secureSocket.startHandshake()

                val input = DataInputStream(secureSocket.getInputStream())
                val output = DataOutputStream(secureSocket.getOutputStream())
                output.writeShort(query.size)
                output.write(query)
                output.flush()

                val responseLength = input.readUnsignedShort()
                if (responseLength !in DnsTcpForwarder.DNS_HEADER_BYTES..maxMessageBytes) {
                    throw IOException("DNS-over-TLS resolver returned an invalid message length.")
                }
                val response = ByteArray(responseLength)
                input.readFully(response)
                DnsTcpForwarder.validateResponse(query, response)
                return response
            }
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || IPV4_PATTERN.matches(host)

    companion object {
        const val DNS_OVER_TLS_PORT = 853
        const val DEFAULT_TIMEOUT_MS = 7_000
        private val IPV4_PATTERN =
            Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    }
}
