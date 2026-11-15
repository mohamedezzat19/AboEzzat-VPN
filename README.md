# AboEzzat VPN

A professional Android traffic-routing client designed to combine an upstream HTTP/HTTPS or SOCKS5 proxy with a single Android VPN service and per-app routing.

> **Development status:** foundation scaffold. The current app is a UI/build baseline only. It does not yet establish a working proxy tunnel or VPN data plane. Do not use it as a privacy or security product until the tunnel, DNS, IPv6, UDP, leak, and reconnect tests are implemented and passed.

## Product goals

- One Android `VpnService` owner; never start a second competing VPN service.
- Upstream proxy profiles: HTTP/HTTPS CONNECT and SOCKS5, with optional credentials.
- Per-app route policies: VPN, upstream proxy, proxy-then-VPN, or direct.
- Banking / direct-bypass profiles, with a clear warning that apps may still detect VPN state or reject proxy/network characteristics.
- Persistent upstream-proxy connection configuration to the first phone's Android Server Proxy.
- Professional dashboard, diagnostics, and build automation.

## Important Android networking constraint

Android exposes one active VPN interface per user/profile. The app-routing allowlist and denylist are mutually exclusive and are applied when establishing the VPN interface. Excluded apps use the device's normal system network; they do **not** automatically use the upstream proxy. Therefore, routing apps independently among direct, proxy-only, VPN-only, and proxy-then-VPN requires a real packet-processing engine and explicit socket/proxy routing. The UI policy model in this scaffold is not yet a working data plane.

Official docs: https://developer.android.com/develop/connectivity/vpn

## Architecture

- `app/` — Android application and Compose UI.
- `app/src/main/java/com/aboezzat/vpn/core/` — route and proxy domain models.
- `app/src/main/java/com/aboezzat/vpn/proxy/` — proxy profile validation and protocol definitions.
- `app/src/main/java/com/aboezzat/vpn/routing/` — per-app policy model.
- `app/src/main/java/com/aboezzat/vpn/vpn/` — future VPN service/data-plane integration.
- `.github/workflows/android.yml` — CI build and APK artifact.

## Build

Open the repository in Android Studio or run:

```bash
./gradlew :app:assembleDebug
```

The GitHub Actions workflow builds a debug APK on pushes and pull requests.

## Next implementation milestones

1. Inspect the exact Android Server Proxy protocol, port, authentication, and supported UDP behavior.
2. Implement and test HTTP CONNECT and SOCKS5 transport adapters.
3. Implement the VPN TUN packet loop and protected upstream sockets.
4. Implement routing policy enforcement and DNS/IPv6 handling.
5. Add app picker, persistence, diagnostics, reconnect, and leak tests.
6. Verify Banking Mode on real banking apps without promising that every app will accept it.

## Security

Never commit proxy passwords, VPN private keys, or server credentials. Store secrets using Android Keystore-backed encryption once profile persistence is implemented.
