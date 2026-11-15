# Vendored tun2proxy source provenance

- Upstream: https://github.com/tun2proxy/tun2proxy
- Upstream tag: `v0.8.7`
- Immutable upstream commit: `b7cd433a25bcd131e7994163874bbbad4834eb3e`
- Upstream license: MIT; see the adjacent `LICENSE` file.

The source under this directory is based on the exact upstream revision above.
AboEzzat-specific changes are being made locally and must remain reviewable
against that immutable revision.

## Local Android socket-protection patch

The Android path in `src/lib.rs` now routes TCP and UDP socket creation
through a fail-closed protection check:

- TCP uses an unconnected `TcpSocket`, calls
  `abz_protect_socket_fd(raw_fd)`, and only then connects.
- UDP creates an unbound `socket2::Socket`, calls the same protection
  callback, and only then binds and connects.
- A missing/failed protection callback returns an error; it must never retry
  without protection.
- Non-Android builds retain the upstream direct socket behavior so host tests
  can exercise the rest of the crate.

The callback symbol is currently supplied by the app-owned JNI C++ bridge.
The Rust shared/static library is not yet linked into the Android APK, and
native engine start/stop is not yet connected to `AboEzzatVpnService`.

## Current verification boundary

CI runs Rust formatting and host library tests, plus the Android/Kotlin build.
This does not yet prove Android cross-compilation, symbol resolution between
the Rust and C++ libraries, TUN lifecycle, packet routing, or live VPN behavior.
Do not call `VpnService.Builder.establish()` until those integration steps
and device tests pass.
