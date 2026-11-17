> **Historical snapshot — superseded.** This document records the pre-integration research stage. The current main branch now establishes the Android TUN and runs the pinned tun2proxy engine. Use [NETWORKING_AUDIT_AND_ROADMAP.md](NETWORKING_AUDIT_AND_ROADMAP.md) and [native/tun2proxy/UPSTREAM.md](../native/tun2proxy/UPSTREAM.md) for current status.

# Android native tunnel integration — verified design notes

Status: research/design only. This document does not claim that the native engine is integrated or that a VPN tunnel is working.

## Upstream revision inspected

- Project: https://github.com/tun2proxy/tun2proxy
- Tag: `v0.8.7`
- Cargo package version: `0.8.7`
- License declared by Cargo.toml: MIT
- Rust edition: 2024; minimum Rust version: 1.90.
- Library crate types: `staticlib`, `cdylib`, and `lib`.

Files inspected at that tag:
- `src/android.rs`
- `src/general_api.rs`
- `src/args.rs`
- `src/lib.rs`
- `src/socket_transfer.rs`
- `src/traffic_status.rs`

## Confirmed entry points

The generic C ABI exports:
- `tun2proxy_run_with_cli_args(const char*, unsigned short, bool)`
- `tun2proxy_stop()`
- `tun2proxy_set_traffic_status_callback(...)`

The Android JNI convenience entry points in upstream `src/android.rs` are named for the Java class `com.github.shadowsocks.bg.Tun2proxy`; they are not drop-in methods for this app's package. Prefer a small app-owned JNI bridge calling the generic C ABI, rather than copying those Java symbol names.

The run function is blocking. It must execute on a dedicated native/background thread. The stop function cancels the active run token. Upstream protects against a second concurrent run with a global run token; callers must still serialize start/stop and wait for the run thread to exit before releasing the TUN descriptor or unloading the library.

For an Android `VpnService`-owned TUN descriptor, the native arguments must include `--tun-fd <fd> --close-fd-on-drop false` so the native engine does not close a descriptor owned by the Java service. The Java service remains responsible for closing its `ParcelFileDescriptor` after the native run has terminated.

## Critical socket-loop issue

Android's `VpnService.protect(int)` must be called for every upstream socket before that socket connects/sends, otherwise traffic to the proxy server can be routed back into the VPN interface.

At v0.8.7, the normal Android build path in `src/lib.rs` sets `socket_queue = None`. In that path:
- TCP upstream sockets are created through `TcpStream::connect(peer)`.
- UDP upstream sockets are created with `UdpSocket::bind(...)`, then connected.
- The Linux `socket_transfer` implementation is cfg-gated to Linux with the `unshare` feature and is not an Android socket-protection hook.

No call to Android `VpnService.protect(int)` is exposed by these inspected creation paths. Therefore, the unmodified upstream library must not be started behind an established Android TUN and assumed loop-safe.

## Required upstream patch before integration

A maintained fork or vendored patch must introduce a socket-protection hook at socket creation time:

1. Create TCP sockets without connecting; obtain the raw descriptor; invoke the registered protector; fail closed if protection returns false; only then connect.
2. Create UDP sockets without binding/sending; invoke the protector on the raw descriptor; fail closed if protection returns false; only then bind/connect.
3. Cover every socket path, including proxy endpoint DNS resolution and optional UDP gateway traffic. Avoid a path that silently falls back to unprotected sockets.
4. Expose a narrow C callback registration API or an Android-specific JNI bridge. The callback must safely attach native worker threads to the JVM, hold/release global references correctly, and call the live service's `protect(fd)`.
5. Ensure callback lifetime is bounded by the native run lifecycle; do not retain a stale service reference after stop/revoke.
6. Add native tests proving the callback is invoked before connect/bind and that a false callback prevents network use.

A Java/Kotlin wrapper alone cannot protect sockets created internally by Tokio unless the native engine exposes their descriptors before network use.

## Concrete socket patch shape

The v0.8.7 call sites in `src/lib.rs` are `create_tcp_stream(...)` and `create_udp_stream(...)`. The normal Android path currently reaches `TcpStream::connect(peer)` for TCP and `UdpSocket::bind(...)` followed by `connect(peer)` for UDP.

The Android-specific patch should centralize socket creation:

- TCP: create `tokio::net::TcpSocket::new_v4()` or `new_v6()`; call the registered protector with `AsRawFd::as_raw_fd()`; if false, return an error; only then connect.
- UDP: create a raw nonblocking datagram socket with `socket2` (or equivalent) so the descriptor exists before bind; call protect; if false, close and return an error; only then bind/connect and convert to Tokio's UDP socket.
- Keep non-Android builds on their existing socket path behind `cfg(target_os = "android")`.
- On Android tunnel runs, a missing callback must be an error, never an implicit unprotected fallback.

The callback must be registered before native run starts and remain valid until the run thread exits. JNI must call `VpnService.protect(fd)` synchronously; asynchronous protection is too late because the socket must not connect first.

### Proxy hostname resolution

There is another pre-TUN dependency: `general_run_async` resolves the proxy address before starting the packet loop. A hostname lookup must use the underlying Android network, not the TUN being established. For the first integration milestone, require a validated numeric proxy IP (the phone-to-phone proxy is normally a LAN address), or resolve it through an explicitly selected Android `Network` before establishing TUN.

### Failure and lifecycle rules

- A false protect result closes the new socket and aborts that connection. Never retry unprotected.
- On revoke/stop, call native stop, wait for native run to return, then close the Java-owned TUN descriptor and release JNI references.
- Do not close the TUN descriptor while native code may still read it.
- Test IPv4/IPv6, TCP/UDP, false callback, exactly-once callback invocation, and stop racing socket creation.

## Safe integration sequence

1. Build and test the patched native library for Android arm64-v8a.
2. Add the app-owned JNI bridge and test run/stop serialization without establishing a production TUN.
3. Verify the protect callback with a controlled local proxy endpoint and assert that a failed protect callback aborts startup/connection.
4. Only then add the TUN builder and packet engine connection.
5. Keep the feature disabled until device tests cover start, stop, revoke, process death, Wi-Fi/mobile changes, IPv4, IPv6, DNS, TCP, and UDP.
6. Implement per-app policy separately. Android allow/disallow application lists alone do not implement all four app route modes.

## Current product status

AboEzzat VPN remains a proxy-profile and protected proxy-preflight foundation. No TUN is established, no device traffic is forwarded, and the per-app route selections remain saved preferences only. This document does not change that status.
