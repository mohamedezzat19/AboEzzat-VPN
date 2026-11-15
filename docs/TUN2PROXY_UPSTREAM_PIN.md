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

The Rust socket factories now create TCP sockets unconnected and UDP sockets unbound, invoke the app-owned `abz_protect_socket_fd(fd)` callback, and fail closed before connect/bind when protection fails. The Rust engine is now cross-compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, linked into the `abz_protector` JNI shared library, and included in a successful debug APK build. The JNI run/stop entry points are exposed. The service still does **not** establish or run a production TUN session; device-side start/stop and traffic verification remain pending.

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

The GitHub Actions workflow now installs Rust 1.90 and runs formatting plus
host-side Rust library tests, then builds the Android/Kotlin app and the
app-owned C++ JNI callback library with a pinned Android NDK and CMake. It does
not yet cross-compile Rust for Android. The next phase needs Android Rust
targets for each shipped ABI, a reproducible Cargo build, static/dynamic symbol
resolution with the JNI bridge, and APK packaging verification.

## Feature gate

Do not call `VpnService.Builder.establish()` for production until the patched
native engine is cross-compiled and linked, JNI callback lifetime is tied to
native run/stop, socket-ordering tests pass on Android, and device tests confirm
that traffic does not loop or leak around the VPN. The current app remains a proxy-preflight
and configuration foundation, not a functioning device-wide VPN.
