package com.aboezzat.vpn.vpn

import android.net.VpnService
import java.net.Socket

/**
 * VPN service entry point reserved for the real TUN + packet-forwarding engine.
 *
 * It deliberately does not call Builder.establish() yet. Establishing a TUN
 * interface without a functioning packet loop would black-hole device traffic.
 */
class AboEzzatVpnService : VpnService() {
    /**
     * Protects an outbound socket so future VPN-to-proxy connections do not
     * re-enter this app's own TUN interface and create a routing loop.
     * Call only from the active VpnService instance before socket.connect().
     */
    fun protectUpstreamSocket(socket: Socket): Boolean = protect(socket)
}
