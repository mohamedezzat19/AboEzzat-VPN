# tun2proxy upstream pin and Android integration status

## Audited upstream revision

- Repository: https://github.com/tun2proxy/tun2proxy
- Tag: `v0.8.7`
- Immutable commit: `b7cd433a25bcd131e7994163874bbbad4834eb3e`
- Cargo package: `tun2proxy 0.8.7`
- License declared by Cargo.toml: MIT
- Rust edition: 2024
- Minimum Rust version: 1.90
- Library crate types: `staticlib`, `cdylib`, `lib`

The MIT license text must be retained with any vendored or modified source.

## Integration status in AboEzzat VPN

This revision is now vendored under `native/tun2proxy/`, with the upstream MIT license retained. The AboEzzat Android socket-protection patch is in `native/tun2proxy/src/lib.rs`; see `native/tun2proxy/UPSTREAM.md` for the exact provenance and verification boundary.

The Rust socket factories now create TCP sockets unconnected and UDP sockets unbound, invoke the app-owned `abz_protect_socket_fd(fd)` callback, and fail closed before connect/bind when protection fails. The Rust engine has been cross-compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, linked into the `abz_protector` JNI shared library, and included in a successful debug APK build.

The Android service now has user-initiated start/stop actions. It resolves the upstream proxy host before creating the VPN route, establishes a dual-stack Android TUN interface, configures virtual DNS or DNS-over-TCP according to the saved profile, and starts the blocking Rust engine on a dedicated worker. A foreground notification represents the active tunnel. The TUN descriptor is retained until the native run call returns; JNI start arming, cancellation, and stop transitions are serialized to cover early-stop races.

The current tunnel is a **whole-device upstream-proxy tunnel**. Saved per-app route policies are not yet enforced. TCP is proxied; general UDP support depends on SOCKS5 UDP ASSOCIATE and is not available through ordinary HTTP CONNECT. Physical-device traffic, DNS, reconnect, revocation, and background lifecycle tests are still pending.

## Required patch points

The central functions in `src/lib.rs` are:

- `create_tcp_stream(...)`
- `create_udp_stream(...)`

The Android patch must:
1. Create TCP sockets unconnected, call the synchronous protector on the raw fd, then connect.
2. Create UDP sockets unbound, protect the raw fd, then bind/connect.
3. Abort and close the socket if protection returns false or errors.
4. Cover IPv4 and IPv6, proxy endpoint resolution, DNS, and any UDP gateway sockets.
5. Audit all dependency and feature paths for sockets created outside these factories.

The Java/Kotlin callback endpoint and a first app-owned JNI bridge are now present. The JNI shared library exposes `abz_protect_socket_fd(int)` and calls
`AboEzzatVpnService.protectNativeSocket(fd)`. It delegates to the tested,
fail-closed `NativeSocketProtection` boundary. The JNI callback handles native
thread attachment, synchronous Java calls, Java exceptions, and in-flight
reference teardown. The JNI layer now calls the `tun2proxy_run_with_cli_args` and `tun2proxy_stop` C ABI functions. The service must still enforce run/stop lifetime ordering and keep the TUN descriptor alive until the native run function has returned.

## Android build prerequisites for the native phase

The GitHub Actions workflow installs Rust 1.90, runs host-side Rust library tests and Android target checks, builds release static libraries for all three shipped Android ABIs, links each static library into the JNI shared library, and assembles the debug APK. Run `43716191472` completed successfully and uploaded `AboEzzat-VPN-debug`. Later service and lifecycle changes are being revalidated by CI; a successful build does not prove execution on a physical Android device.

The Kotlin `Tun2ProxyCommandBuilder` creates the native CLI for HTTP CONNECT and SOCKS5 profiles, percent-encodes credentials, brackets IPv6 proxy hosts, selects virtual DNS or DNS-over-TCP, and passes the TUN descriptor with `--close-fd-on-drop false` so the service retains descriptor ownership. Unit tests cover these cases.

## Feature gate

The service now calls `VpnService.Builder.establish()` only after the user initiates connection and Android grants VPN consent. Do not describe this as production-ready until physical-device tests confirm packet forwarding, proxy authentication, DNS behavior, socket protection, stop/revoke cleanup, and no routing loops. Per-app policies remain stored-only; the currently wired mode applies to the whole device.
