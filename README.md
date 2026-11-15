# AboEzzat VPN

A professional Android app foundation for combining an upstream proxy from a first phone with a future per-app VPN routing engine.

> **Current status — foundation and proxy preflight only.** The app can save proxy profiles, encrypt saved credentials with Android Keystore, save per-app route preferences, request Android's VPN permission, and run an HTTP CONNECT or SOCKS5 proxy handshake from its VPN service using a protected outbound socket. It does **not** create a TUN interface, forward device traffic, enforce per-app routing, or provide an active VPN tunnel yet. Do not treat the current build as a privacy or security VPN.

## Current capabilities

- Proxy profiles for HTTP CONNECT and SOCKS5.
- Optional username/password authentication.
- AES-GCM credential encryption backed by Android Keystore.
- Proxy preflight test for TCP CONNECT handshake, with a connection timeout and latency result.
- The preflight service calls `VpnService.protect(socket)` before opening its outbound proxy socket.
- Saved per-app route preferences:
  - VPN only
  - Original upstream proxy only
  - Proxy then VPN
  - Direct bypass
- VPN permission request flow.
- GitHub Actions debug APK build and local unit tests.

The proxy test confirms only that the proxy handshake succeeded. It does not verify the destination TLS handshake, UDP, DNS behavior for all traffic, or device-wide routing.

## Android networking constraints

Android exposes one active VPN interface per Android user/profile. VPN app allowlists and disallowlists are mutually exclusive and are applied when the VPN interface is established. Excluding an app from the VPN sends it through the normal system network; it does **not** automatically send it through the upstream proxy.

Therefore, independently enforcing direct, proxy-only, VPN-only, and proxy-then-VPN routes requires a real packet-processing engine, explicit per-app policy enforcement, and careful DNS/socket routing. The route choices currently shown in the app are saved preferences only.

Official Android documentation: https://developer.android.com/develop/connectivity/vpn

## Native tunnel engine investigation

The Android integration research for upstream `tun2proxy v0.8.7` is documented in [ANDROID_NATIVE_TUNNEL_INTEGRATION.md](docs/ANDROID_NATIVE_TUNNEL_INTEGRATION.md). The upstream C run/stop entry points are identifiable, but its normal Android socket-creation path does not expose a `VpnService.protect(int)` hook. A patched native socket-protection callback is therefore a hard prerequisite; the unmodified engine must not be connected to an established TUN.

This is a research milestone only. No native library is bundled, no JNI bridge is enabled, and no TUN is established by the app.

## Repository layout

- `app/src/main/java/com/aboezzat/vpn/core/` — route policy model.
- `app/src/main/java/com/aboezzat/vpn/proxy/` — proxy models, request validation, and HTTP CONNECT/SOCKS5 preflight.
- `app/src/main/java/com/aboezzat/vpn/data/` — profile and route preference persistence.
- `app/src/main/java/com/aboezzat/vpn/vpn/` — Android VPN service entry point; no TUN data plane is enabled yet.
- `.github/workflows/android.yml` — CI unit tests and debug APK artifact.

## Build

The repository currently builds in GitHub Actions with Gradle 8.9 and JDK 17:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

There is currently no Gradle wrapper in the repository. Android Studio can also be used to open and build the project with a compatible Android Gradle Plugin setup.

## Next engineering milestones

1. Verify the exact Android Server Proxy protocol, port, authentication behavior, and whether it supports UDP.
2. Implement transport adapters with explicit socket lifecycle, timeouts, cancellation, and protocol tests.
3. Design and implement the TUN packet loop before calling `Builder.establish()`.
4. Add TCP stream forwarding and protected upstream sockets without routing loops.
5. Implement per-app route enforcement, DNS handling, IPv4/IPv6 behavior, and reconnect handling.
6. Decide and implement UDP behavior according to upstream proxy capabilities.
7. Add leak, disconnect, network-switch, and real-device tests before presenting the app as a working VPN.

## Security notes

- Never commit proxy passwords, WireGuard private keys, or server credentials.
- Proxy credentials are encrypted with Android Keystore before persistence.
- Banking/direct-bypass preferences cannot guarantee that a banking app will accept the device's network or VPN state.
- Do not enable a TUN interface until the packet loop and failure-safe shutdown path are complete.
