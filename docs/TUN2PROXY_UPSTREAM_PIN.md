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

This revision is audited but **not yet vendored, patched, linked, or shipped** in the Android app. Do not add a Cargo dependency on the upstream tag and assume Android sockets are protected.

The upstream Android path inspected for this revision has no app-owned `VpnService.protect(fd)` callback in the TCP/UDP socket creation paths. The Android path must be patched before use.

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
reference teardown. The engine still has to register/use the C ABI and enforce
run/stop lifetime ordering.

## Android build prerequisites for the native phase

The current GitHub Actions workflow builds only the Android/Kotlin project.
The native phase will need a pinned Rust toolchain (at least 1.90), Android
NDK, Android Rust targets for each shipped ABI, and a reproducible Cargo build.
The resulting shared/static libraries must be packaged and verified in the APK.

## Feature gate

Do not call `VpnService.Builder.establish()` for production until the patched
native engine, JNI callback, stop/run synchronization, socket-ordering tests,
and Android device tests all pass. The current app remains a proxy-preflight
and configuration foundation, not a functioning device-wide VPN.
