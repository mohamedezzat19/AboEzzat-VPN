# TUN Engine Evaluation — 2026-11-15

## Decision status

**Candidate selected for a proof of concept: heiher/hev-socks5-tunnel. Not integrated into the app yet.**

The current Kotlin packet parsers and flow tracker remain useful for validation and diagnostics, but they are not a replacement for a complete TCP/IP stack. The app must not call VpnService.Builder.establish() until the native engine, proxy adapter, cancellation path, and failure-safe shutdown have been tested together.

## Candidate

Pinned proof-of-concept release: 2.20.0 (2026-11-13).

- Android AAR: hev-socks5-tunnel.aar (617,551 bytes).
- Published SHA-256: ae8a2e96b80b4eccccd037805a4bb4a278090c89dbcae8bc6fcb7634e9a255b9.
- Project: https://github.com/heiher/hev-socks5-tunnel
- Android app reference: https://github.com/heiher/sockstun
- License reported by both repositories: MIT.
- Engine capabilities documented upstream: IPv4 and IPv6, TCP forwarding, UDP forwarding, and Android support.
- Android integration documented upstream: an AAR exposing hev.htproxy.TProxyService native methods:
  - TProxyStartService(config_path, tun_fd)
  - TProxyStopService()
  - TProxyIsRunning()
  - TProxyGetStats()
- Upstream release artifacts include Android native builds for arm64-v8a, armeabi-v7a, x86, and x86_64.

## Why this is preferable to writing TCP from scratch

A production TCP/IP data plane must handle sequence numbers, acknowledgements, retransmission, out-of-order segments, congestion/window behavior, FIN/RST lifecycle, IPv4/IPv6, UDP, DNS, and shutdown under network changes. The existing Kotlin parser and lifecycle tracker only inspect packet metadata; they do not implement these transport behaviors.

Hev already provides a mature tun2socks data plane, so a bounded integration proof is safer than continuing to build a partial TCP stack in this repository.

## Blocking compatibility gaps

1. **SOCKS5 upstream only:** Hev's documented tunnel endpoint is SOCKS5. The app also supports HTTP CONNECT profiles. A local SOCKS5-to-upstream adapter would be required for HTTP CONNECT profiles; it must preserve authentication, remote-DNS behavior, cancellation, socket protection, and error reporting.
2. **Four route modes are not provided by the stock integration:** SocksTun demonstrates global and per-app selection by using Android VpnService.Builder.addAllowedApplication() for one allowlist. This is not the same as independent per-app routing among VPN-only, proxy-only, proxy-then-VPN, and direct bypass.
3. **One Android VPN interface:** Android permits one active VPN interface per Android user/profile. Excluding an app from the VPN makes it use the normal system network; it does not automatically send it through the upstream proxy.
4. **UDP depends on upstream support:** Hev can forward UDP, but a SOCKS5 server must support UDP ASSOCIATE or a supported UDP-over-TCP mode. An HTTP CONNECT-only proxy cannot carry arbitrary UDP.
5. **Native supply-chain review:** Before vendoring source or consuming a prebuilt AAR, pin an exact upstream release/commit, verify checksums, inspect the full dependency/submodule license set, and build/test all required ABIs in this repository's CI.

## Proposed proof-of-concept sequence

1. Pin one Hev release and record its checksum and full third-party license inventory.
2. Add the native artifact behind a disabled build feature; do not establish a TUN yet.
3. Build a local SOCKS5 adapter with loopback-only binding and integration tests:
   - SOCKS5 CONNECT -> existing SOCKS5 upstream.
   - SOCKS5 CONNECT -> HTTP CONNECT upstream.
   - authentication success/failure.
   - remote DNS and local DNS behavior.
   - bounded sockets, cancellation, and no credential logging.
4. Create a test-only VpnService path that establishes a TUN only after explicit user consent and starts Hev immediately with the descriptor.
5. Verify stop/revoke/error behavior always stops the native engine before closing the descriptor, and that the upstream socket is protected from VPN recursion.
6. Test IPv4 TCP, IPv6 TCP, DNS, UDP, Wi-Fi/mobile network changes, upstream disconnects, and device reboot on physical Android devices.
7. Only after the single-route data plane is stable, design the per-app policy extension. Do not claim the four route modes are implemented until each mode has a separate end-to-end test.

## Per-app routing design constraint

The stock SocksTun approach uses an allowlist: selected packages are placed inside the TUN; all other packages remain outside it. That is useful for a first single-route proof, but it cannot independently enforce four different routes. A later design must either:

- extend the native packet engine with a verified per-flow routing callback and a safe app-UID attribution strategy; or
- use a different architecture that can maintain the required per-app routes without creating a second Android VPN interface.

Do not assume ConnectivityManager.getConnectionOwnerUid() can always identify the owner of the first SYN observed on a TUN. This must be validated on supported Android versions before relying on it for route decisions.

## Explicit non-goals for this proof

- No silent VPN start.
- No VpnService.Builder.establish() from the existing placeholder service.
- No claim of banking-app acceptance or guaranteed bypass.
- No claim that HTTP CONNECT supports UDP.
- No release APK presented as a functioning VPN until device-level traffic tests pass.
