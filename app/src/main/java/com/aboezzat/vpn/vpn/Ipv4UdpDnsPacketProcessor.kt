package com.aboezzat.vpn.vpn

/**
 * Connects the strict IPv4/UDP DNS packet codec to a configured DNS resolver.
 *
 * The caller supplies a resolver that forwards one raw DNS query and returns
 * its raw DNS response (for example DnsTcpForwarder or DnsOverTlsForwarder).
 * This class is deliberately independent of VpnService/TUN lifecycle.
 */
fun interface DnsPacketResolver {
    fun resolve(query: ByteArray): ByteArray
}

class Ipv4UdpDnsPacketProcessor(
    private val resolver: DnsPacketResolver,
    private val responseTtl: Int = Ipv4UdpDnsPacketCodec.DEFAULT_TTL
) {
    init {
        require(responseTtl in 1..255) { "Response TTL must be between 1 and 255." }
    }

    /**
     * Returns a complete IPv4/UDP DNS response packet.
     * Invalid or unsupported input and resolver failures are surfaced to the
     * caller; no synthetic success or direct-network fallback is attempted.
     */
    fun process(packet: ByteArray): ByteArray {
        val query = Ipv4UdpDnsPacketCodec.parseQuery(packet)
        val response = resolver.resolve(query.dnsPayload.copyOf())
        return Ipv4UdpDnsPacketCodec.buildResponse(query, response, responseTtl)
    }
}
