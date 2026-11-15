package com.aboezzat.vpn.vpn

import android.net.VpnService

/**
 * VPN service entry point reserved for the real TUN + packet-forwarding engine.
 *
 * It deliberately does not call Builder.establish() yet. Establishing a TUN
 * interface without a functioning packet loop would black-hole device traffic.
 */
class AboEzzatVpnService : VpnService()
