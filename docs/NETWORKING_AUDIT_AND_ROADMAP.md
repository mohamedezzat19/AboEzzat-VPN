# Networking audit and staged recovery plan

**Audit date:** 2026-11-17  
**Repository:** `mohamedezzat19/AboEzzat-VPN`

## Goal and operating rule

Stop speculative one-off patches. Establish one reproducible data path, test each layer independently, and only advance to the next stage when the previous stage passes. Do not change MTU, session limits, DNS addresses, or routing defaults without a measured reason.

## Current architecture verified in source

- This repository implements the Android **client** on the second phone. The proxy server runs separately on the first phone.
- The active VPN path is Android `VpnService` -> TUN file descriptor -> app-owned JNI bridge -> vendored `tun2proxy v0.8.7`.
- The native engine supports HTTP CONNECT and SOCKS proxy managers. HTTP CONNECT is TCP-only. SOCKS5 UDP requires a working UDP ASSOCIATE relay from the upstream server.
- The app protects native upstream sockets with `VpnService.protect(fd)`; a protection failure must stop that socket rather than retry unprotected.
- The TUN path installs IPv4 and IPv6 default routes. Current MTU is 1500. The native engine default maximum session count is 200.
- `addDisallowedApplication` implements direct bypass. The remaining route labels do not currently map to separate per-app packet paths.
- There is no proxy server implementation in this repository and no complete device-wide proxy-only mode. A proxy-only stage can validate the upstream transport and app-level proxy use; device-wide capture of arbitrary Android apps requires the VPN stage.

## Findings and corrective changes

1. **Virtual DNS record coverage:** the virtual DNS generator creates synthetic answers for A and AAAA. Other record types previously became empty responses. The current hybrid-DNS change preserves synthetic A/AAAA mapping and forwards unsupported UDP/TCP queries through the configured resolver over the proxy.
2. **TCP session cleanup:** the TCP relay used `tokio::join!` for both directions. If one direction errors while the other remains blocked, the session can remain alive unnecessarily. It should preserve normal half-close behavior but cancel the opposite direction on error.
3. **Unbounded upstream setup:** TCP connect and HTTP/SOCKS handshake did not have explicit deadlines in the native data path. A stalled proxy can consume a session slot. The current hardening change adds bounded connect and handshake timeouts.
4. **Kotlin socket protection order:** helper sockets were passed to `VpnService.protect()` before binding. On Android an unbound Java socket may not yet have an allocated native descriptor. The helper paths now bind first, protect second, and connect third.
5. **UDP diagnostic strength:** the previous UDP diagnostic accepted any matching DNS transaction ID with the QR bit set. It now also requires a successful RCODE and at least one answer; this verifies DNS resolution, not arbitrary QUIC compatibility.
6. **Documentation drift:** the README and older JNI/TUN notes described the engine as not integrated even though the current service establishes a TUN. Those documents are now explicitly marked historical.
7. **Reproducibility and test coverage:** the native crate has no committed Cargo.lock, and CI has no Android instrumentation test that drives real traffic through a live TUN. These remain build/test-hygiene tasks.
8. **Operational limits requiring measurement:** MTU 1500, max_sessions 200, IPv6 availability, network-change recovery, and non-TCP/UDP IP protocols need explicit diagnostics and tests. Do not blindly raise limits or force a DNS address.

## Open-source references and what to take from them

- [tun2proxy](https://github.com/tun2proxy/tun2proxy) (MIT): closest fit because it supports HTTP and SOCKS, IPv4/IPv6, SOCKS5 UDP, DNS-over-TCP, and an optional UDP gateway. Keep the existing pinned engine until a controlled test proves it inadequate.
- [HevSocks5Tunnel](https://github.com/heiher/hev-socks5-tunnel) and [SocksTun](https://github.com/heiher/sockstun) (MIT): strong Android and UDP references; Hev supports UDP-in-UDP and UDP-in-TCP, but UDP-in-TCP requires its compatible server protocol. It is not a drop-in replacement for an arbitrary Android SOCKS5 server.
- [ProxyDroid](https://github.com/madeye/proxydroid): useful reference for VPN-first architecture, DNS-over-HTTPS through the same upstream proxy, and emulator integration tests. Use design/test ideas only; do not copy its GPL-licensed implementation into this project.

## Stage 1 — proxy-only baseline (no TUN)

The first stage must isolate the upstream proxy from Android VPN routing. Use an in-app WebView browser configured through AndroidX WebKit ProxyController with either the HTTP proxy URL or SOCKS proxy URL. Android documents this as a process-specific override for WebView requests; it does not configure unrelated apps:

- HTTP CONNECT to a known HTTPS destination: proxy handshake, TLS handshake, and actual HTTP response body.
- SOCKS5 CONNECT to both a domain name and an IP address; no-auth and username/password paths.
- DNS A/AAAA virtual hostname mapping, plus SRV, TXT, HTTPS/SVCB and TCP fallback queries forwarded through the proxy with no direct DNS fallback.
- SOCKS5 UDP ASSOCIATE: verify the control TCP socket stays open, the advertised relay address is usable, the UDP response comes from the relay, and the DNS response has RCODE 0 and a real answer.
- Test a non-DNS UDP destination separately. A DNS datagram round-trip alone is not a QUIC test.
- Record exact connect/handshake durations and errors. No TUN should be running during these baseline tests.

**Limit:** this browser mode affects only WebViews inside AboEzzat VPN. It cannot transparently route every Android app without a VPN. Device-wide app capture is Stage 2.

## Stage 2 — proxy + VPN

Only after Stage 1 passes:

- Establish the TUN and route IPv4/IPv6 traffic through it.
- Verify every native upstream TCP and UDP socket is protected before connect/bind; fail closed if protection fails.
- Implement hybrid DNS: synthesize A/AAAA for virtual hostname mapping; forward all other record types and TCP fallback to the resolver through the proxy.
- Verify SOCKS5 UDP ASSOCIATE end-to-end with actual UDP traffic, including QUIC/UDP 443 where the upstream server supports it.
- Test session cleanup, idle timeout, max-session pressure, IPv4/IPv6 behavior, MTU/fragmentation, and switching between Wi-Fi/hotspot networks.
- Keep direct bypass behavior explicit. Do not show route modes that are not implemented as if they were active.
- Add Android instrumentation/device tests before calling the tunnel stable.

## Stage 3 — country / exit location

Only after Stage 2 passes:

- Select the external VPN/proxy exit on the first phone or a separate upstream endpoint in the desired country.
- Verify the public egress IP and its country from the second phone.
- Keep the local proxy hop and country-changing egress as separate, observable layers.

## Acceptance gates

| Gate | Required proof |
|---|---|
| Proxy TCP | CONNECT/SOCKS5 handshake + TLS + real HTTP response |
| DNS | A/AAAA + non-address record types + TCP fallback, all through proxy |
| Proxy UDP | UDP ASSOCIATE control + relay + valid DNS answer + non-DNS UDP test |
| VPN TCP | Browser and native app traffic observed through TUN and proxy |
| VPN UDP | UDP/QUIC flow reaches the relay and returns data |
| IPv4/IPv6 | Each family tested separately; no silent blackhole |
| Reliability | Stalled proxy times out; failed relay closes both directions; session count returns to baseline |
| Leak behavior | No direct fallback when proxy or socket protection fails |

No build, unit test, or single successful app is by itself proof that all apps and websites work.
