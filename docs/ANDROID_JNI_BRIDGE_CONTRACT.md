# App-owned JNI bridge contract

Status: JNI callback bridge source and CMake target are now present in the app. The patched tun2proxy engine is not yet linked or shipped.

This document defines the Android socket-protection interface for the pinned tun2proxy fork. It does not establish a TUN or advertise a working VPN.

## Java/Kotlin ownership

- AboEzzatVpnService owns the ParcelFileDescriptor returned by VpnService.Builder.establish().
- The service exposes one synchronous method to native code: protectNativeSocket(fd: Int): Boolean.
- That method calls VpnService.protect(fd) directly. It must not dispatch protection to a Handler, coroutine, or executor.
- The service must not close the TUN descriptor until the native run call has returned.
- The native bridge must not cache an Activity. It currently holds a JNI global reference to the live service while the Android service is alive; this must be narrowed to the native run lifetime when the engine is integrated.

## Proposed C ABI

The fork should expose a callback registration API with explicit user data:

~~~c
typedef bool (*abz_socket_protect_fn)(int fd, void *user_data);

int tun2proxy_set_socket_protector(
    abz_socket_protect_fn callback,
    void *user_data
);

int tun2proxy_run_with_cli_args(
    const char *cli_args,
    unsigned short tun_mtu,
    bool close_fd_on_drop
);

int tun2proxy_stop(void);
~~~

Rules:

1. Registration is accepted only while no run is active.
2. Android tunnel mode refuses to start if the callback is absent.
3. The callback runs synchronously on the socket-creating thread and before connect, bind, send, or DNS network use.
4. A false callback result closes the socket and returns an error. No unprotected retry is permitted.
5. Callback and user data remain valid until the run function returns.
6. Stop is idempotent. A new run cannot start until the prior run exits and callback state is cleared.

The upstream run/stop functions alone are insufficient. The pinned fork must add this callback API and wire it into every Android outbound socket path.

## JNI callback mechanics

The current JNI bridge implements the following mechanics:

- Captures JavaVM once from JNI_OnLoad.
- Creates a global reference to the current service when the Android service starts.
- Caches the method ID for protectNativeSocket(I)Z while a valid JNI environment is available.
- In the native callback, calls GetEnv; attaches the current native thread only if detached; invokes the Java method synchronously; treats a Java exception as protection failure; detaches only if this callback attached the thread.
- Serializes callback-state teardown with an in-flight counter so the global reference cannot be deleted while a callback is using it.
- Clears the global reference only after callbacks in flight have returned. Native run integration still needs to enforce stop/run ordering.
- Treat missing service binding, JNI attach failure, a Java exception, or a failed JNI call as protection failure.

Do not hold a global lifecycle mutex while invoking Java. Use a short-lived state guard or in-flight callback counter to prevent stop/reference-deletion races without deadlocking Java error handling.

## Native socket factory requirements

At the pinned upstream revision, the central call sites are create_tcp_stream(...) and create_udp_stream(...) in src/lib.rs.

- TCP must be created unconnected (TcpSocket::new_v4/new_v6), protected by raw fd, then connected.
- UDP must be created unbound, protected by raw fd, then bound/connected and converted to Tokio.
- Protect the proxy endpoint and any UDP gateway endpoint as well as per-destination sockets.
- Resolve a proxy hostname through the underlying Android Network before TUN establishment, or require a numeric IP for the first milestone.
- Audit dependencies and optional features for independent outbound socket creation. Central call-site changes are not sufficient if another path creates sockets independently.

## Required tests before enabling the feature

### JNI/lifecycle tests

- Callback true: native socket creation proceeds.
- Callback false: socket closes and no connect/bind/send occurs.
- Missing callback: Android tunnel startup fails closed.
- Callback exception or JNI attach failure: fail closed.
- Stop during an in-flight callback: no use-after-free and no stale service reference.
- Stop waits for native run completion before Java closes the TUN descriptor.
- Repeated start/stop cycles do not retain global references or run threads.

### Socket tests

- IPv4 and IPv6 TCP sockets invoke protection exactly once before connect.
- IPv4 and IPv6 UDP sockets invoke protection exactly once before bind/connect.
- Proxy DNS and optional UDP gateway sockets are covered.
- A false result never falls back to an unprotected socket.

### Android device tests

Use a controlled proxy on the other phone. Verify its endpoint remains reachable while TUN is active. Repeat with Wi-Fi/mobile handover, VPN revoke, process stop, and a failed protect callback. Capture logs proving callback-before-connect ordering; do not infer it from a successful page load alone.

## Feature gate

Until the patched native library, JNI bridge, and tests above exist:

- Do not call Builder.establish() for production use.
- Do not label the app as connected or claim device-wide traffic protection.
- Keep saved per-app route choices as configuration only.
