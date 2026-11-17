# AboEzzat VPN

Android client for sending traffic from a second phone through an upstream proxy running on another device.

**Important architecture boundary:** this repository is the Android proxy client. It does not implement the proxy server on the first phone. The first phone must run a compatible HTTP CONNECT or SOCKS5 server.

## Current implementation

- Android `VpnService` creates a TUN interface.
- The TUN traffic is handled by the vendored `tun2proxy` Rust engine, pinned to upstream `v0.8.7`.
- Native TCP/UDP sockets are protected through the app-owned JNI callback to `VpnService.protect(fd)`; protection failure is fail-closed.
- HTTP CONNECT carries TCP only.
- SOCKS5 carries TCP and can use UDP ASSOCIATE when the upstream server implements it.
- IPv4 and IPv6 routes are installed by the current VPN path.
- Direct bypass uses Android's `addDisallowedApplication`. The other displayed route labels are not independent routing engines yet.

## Current limitations being audited

- The app does not yet provide a complete standalone, device-wide proxy-only mode. Its direct proxy checks are preflight tests; Android-wide app capture requires the VPN stage.
- Virtual DNS synthesizes A/AAAA records and forwards other DNS record types (including TCP fallback) through the upstream proxy.
- A successful SOCKS5 UDP DNS test proves that one DNS datagram received an answer; it does not prove every UDP application or QUIC flow works.
- The current TUN MTU is 1500 and the engine's default maximum concurrent sessions is 200. Neither should be changed without measurements on the actual hotspot/MikroTik path.
- Non-TCP/UDP IP transports are not forwarded by the current userspace engine.
- Physical-device end-to-end tests are still required; host unit tests and Android compilation do not prove that every app works.

The authoritative findings and staged plan are in [docs/NETWORKING_AUDIT_AND_ROADMAP.md](docs/NETWORKING_AUDIT_AND_ROADMAP.md). The vendored engine provenance is in [native/tun2proxy/UPSTREAM.md](native/tun2proxy/UPSTREAM.md).

## Recovery plan

1. **Proxy-only baseline:** use the in-app WebView browser with AndroidX ProxyController (HTTP or SOCKS proxy) without starting the TUN, then verify SOCKS5 CONNECT, DNS, and real UDP ASSOCIATE responses. The WebView proxy override applies only to WebViews in this app; other apps are unaffected.
2. **Proxy + VPN:** after the proxy baseline passes, route device traffic through the TUN and verify DNS, TCP, UDP/QUIC, IPv4/IPv6, session cleanup, and network changes.
3. **Country/egress selection:** only after the first two stages are stable, add an external egress VPN or proxy endpoint in the selected country and verify the public exit IP.

## Releases

GitHub Actions builds signed **Release APKs only** for main-branch updates. The workflow runs Rust tests, Android ABI checks, Gradle release unit tests, signing validation, and publishes the release asset for the in-app updater.

Do not treat a successful build as proof that all network protocols work. Release validation and real-device network validation are separate gates.
