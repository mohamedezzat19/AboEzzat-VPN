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

The callback symbol is supplied by the app-owned JNI C++ bridge. The patched Rust static library is cross-compiled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, linked into the `abz_protector` shared library, and packaged in the Android APK. JNI exposes native run/stop and start-arm/cancel methods. The Android service now establishes a user-initiated TUN interface, runs the engine on a dedicated worker, and keeps the ParcelFileDescriptor open until the native run call returns.

The Rust API shutdown watchdog was also changed so an embedded library cleanup delay logs an error rather than calling `std::process::exit` and terminating the host Android process. Stop requests arriving before the Rust cancellation token is published are carried into startup and consumed safely.

## Current verification boundary

CI verifies host Rust tests, Android target compilation, static linking, Kotlin unit tests, and APK packaging. These checks do not prove live packet forwarding on a physical device. The current service routes the whole device through the selected upstream proxy; saved per-app route policies are not yet enforced. TCP is proxied; general UDP depends on SOCKS5 UDP ASSOCIATE and is not supported by ordinary HTTP CONNECT. Device tests for DNS, authentication, no-loop socket protection, stop/revoke cleanup, reconnect, and background lifecycle are still required before calling this production-ready.
