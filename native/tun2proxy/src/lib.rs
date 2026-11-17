#[cfg(all(target_os = "linux", feature = "unshare"))]
extern crate bincode_next as bincode;

#[cfg(feature = "udpgw")]
use crate::udpgw::UdpGwClient;
use crate::{
    directions::{IncomingDataEvent, IncomingDirection, OutgoingDirection},
    http::HttpManager,
    no_proxy::NoProxyManager,
    session_info::{IpProtocol, SessionInfo},
    virtual_dns::VirtualDns,
};
pub use clap::ValueEnum;
use ipstack::{IpStackStream, IpStackTcpStream, IpStackUdpStream};
use proxy_handler::{ProxyHandler, ProxyHandlerManager};
use socks::SocksProxyManager;
pub use socks5_impl::UserKey;
#[cfg(feature = "udpgw")]
use std::net::{Ipv4Addr, Ipv6Addr, SocketAddrV4, SocketAddrV6};
use std::{
    io::ErrorKind,
    net::{IpAddr, SocketAddr},
    os::fd::AsRawFd,
    sync::Arc,
    time::Duration,
};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{TcpSocket, TcpStream, UdpSocket},
    sync::{Mutex, mpsc::Receiver},
};
pub use tokio_util::sync::CancellationToken;
use tproxy_config::is_private_ip;
pub use tun::DEFAULT_MTU;
use udp_stream::UdpStream;
#[cfg(feature = "udpgw")]
use udpgw::{UDPGW_KEEPALIVE_TIME, UDPGW_MAX_CONNECTIONS, UdpGwClientStream, UdpGwResponse};

pub use log::LevelFilter;
pub use socks5_impl::{ProxyParameters, ProxyType};
pub use {
    args::{ArgDns, Args},
    error::{BoxError, Error, Result},
    traffic_status::{TrafficStatus, tun2proxy_set_traffic_status_callback},
};

pub use general_api::general_run_async;

pub const FORCE_EXIT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(2);

mod android;
mod args;
mod directions;
mod dns;
mod dump_logger;
mod error;
mod general_api;
mod http;
mod no_proxy;
mod proxy_handler;
mod session_info;
pub mod socket_transfer;
mod socks;
mod traffic_status;
#[cfg(feature = "udpgw")]
pub mod udpgw;
mod virtual_dns;
#[doc(hidden)]
pub mod win_svc;

const DNS_PORT: u16 = 53;
const PROXY_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const PROXY_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Hash, Copy, Clone, Eq, PartialEq, Debug)]
#[cfg_attr(
    all(target_os = "linux", feature = "unshare"),
    derive(bincode::Encode, bincode::Decode, serde::Serialize, serde::Deserialize)
)]
pub enum SocketProtocol {
    Tcp,
    Udp,
}

#[derive(Hash, Copy, Clone, Eq, PartialEq, Debug)]
#[cfg_attr(
    all(target_os = "linux", feature = "unshare"),
    derive(bincode::Encode, bincode::Decode, serde::Serialize, serde::Deserialize)
)]
pub enum SocketDomain {
    IpV4,
    IpV6,
}

impl From<IpAddr> for SocketDomain {
    fn from(value: IpAddr) -> Self {
        match value {
            IpAddr::V4(_) => Self::IpV4,
            IpAddr::V6(_) => Self::IpV6,
        }
    }
}

struct SocketQueue {
    tcp_v4: Mutex<Receiver<TcpSocket>>,
    tcp_v6: Option<Mutex<Receiver<TcpSocket>>>,
    udp_v4: Mutex<Receiver<UdpSocket>>,
    udp_v6: Option<Mutex<Receiver<UdpSocket>>>,
}

struct SessionCountGuard(Arc<std::sync::atomic::AtomicUsize>);

impl SessionCountGuard {
    fn new(count: Arc<std::sync::atomic::AtomicUsize>) -> Self {
        let current = count.fetch_add(1, std::sync::atomic::Ordering::Relaxed).saturating_add(1);
        log::trace!("Session count {current}");
        Self(count)
    }
}

impl Drop for SessionCountGuard {
    fn drop(&mut self) {
        let current = self.0.fetch_sub(1, std::sync::atomic::Ordering::Relaxed).saturating_sub(1);
        log::trace!("Session count {current}");
    }
}

impl SocketQueue {
    async fn recv_tcp(&self, domain: SocketDomain) -> Result<TcpSocket, std::io::Error> {
        let receiver = match domain {
            SocketDomain::IpV4 => &self.tcp_v4,
            SocketDomain::IpV6 => self.tcp_v6.as_ref().ok_or_else(|| std::io::Error::from(ErrorKind::Unsupported))?,
        };
        receiver.lock().await.recv().await.ok_or(ErrorKind::Other.into())
    }
    async fn recv_udp(&self, domain: SocketDomain) -> Result<UdpSocket, std::io::Error> {
        let receiver = match domain {
            SocketDomain::IpV4 => &self.udp_v4,
            SocketDomain::IpV6 => self.udp_v6.as_ref().ok_or_else(|| std::io::Error::from(ErrorKind::Unsupported))?,
        };
        receiver.lock().await.recv().await.ok_or(ErrorKind::Other.into())
    }
}

#[cfg(all(target_os = "linux", feature = "unshare"))]
const SOCKET_REQUEST_INITIAL_RETRY_DELAY: std::time::Duration = std::time::Duration::from_millis(100);
#[cfg(all(target_os = "linux", feature = "unshare"))]
const SOCKET_REQUEST_MAX_RETRY_DELAY: std::time::Duration = std::time::Duration::from_secs(5);

#[cfg(all(target_os = "linux", feature = "unshare"))]
fn is_recoverable_socket_request_error(err: &crate::Error) -> bool {
    match err {
        crate::Error::Io(err) => matches!(
            err.kind(),
            std::io::ErrorKind::Interrupted
                | std::io::ErrorKind::WouldBlock
                | std::io::ErrorKind::TimedOut
                | std::io::ErrorKind::ConnectionReset
                | std::io::ErrorKind::ConnectionAborted
                | std::io::ErrorKind::ConnectionRefused
        ),
        crate::Error::NixErrno(err) => matches!(
            *err,
            nix::errno::Errno::EINTR | nix::errno::Errno::EAGAIN | nix::errno::Errno::ENOBUFS | nix::errno::Errno::ENOMEM
        ),
        _ => false,
    }
}

#[cfg(all(target_os = "linux", feature = "unshare"))]
fn next_socket_request_retry_delay(delay: std::time::Duration) -> std::time::Duration {
    delay.saturating_mul(2).min(SOCKET_REQUEST_MAX_RETRY_DELAY)
}

#[cfg(all(target_os = "linux", feature = "unshare"))]
async fn wait_for_socket_request_retry<T>(tx: &tokio::sync::mpsc::Sender<T>, delay: std::time::Duration) -> bool {
    tokio::select! {
        _ = tx.closed() => false,
        _ = tokio::time::sleep(delay) => true,
    }
}

#[cfg(all(target_os = "linux", feature = "unshare"))]
fn create_socket_queue(socket: Arc<Mutex<tokio::net::UnixDatagram>>, ipv6_enabled: bool) -> SocketQueue {
    use crate::socket_transfer::request_sockets;
    use tokio::sync::mpsc::channel;

    macro_rules! create_receiver {
        ($domain:ident) => {{
            const SOCKETS_PER_REQUEST: usize = 64;

            let socket = socket.clone();
            let (tx, rx) = channel(SOCKETS_PER_REQUEST);
            tokio::spawn(async move {
                let mut retry_delay = SOCKET_REQUEST_INITIAL_RETRY_DELAY;
                loop {
                    let sockets = match request_sockets(socket.lock().await, SocketDomain::$domain, SOCKETS_PER_REQUEST as u32).await {
                        Ok(sockets) => sockets,
                        Err(err) if is_recoverable_socket_request_error(&err) => {
                            log::warn!(
                                "Recoverable socket allocation request failure for {:?}: {err}; retrying in {retry_delay:?}",
                                SocketDomain::$domain
                            );
                            if !wait_for_socket_request_retry(&tx, retry_delay).await {
                                return;
                            }
                            retry_delay = next_socket_request_retry_delay(retry_delay);
                            continue;
                        }
                        Err(err) => {
                            log::error!(
                                "Permanent socket allocation request failure for {:?}; stopping queue: {err}",
                                SocketDomain::$domain
                            );
                            return;
                        }
                    };
                    if sockets.is_empty() {
                        log::warn!(
                            "Socket allocation returned no sockets for {:?}; retrying in {retry_delay:?}",
                            SocketDomain::$domain
                        );
                        if !wait_for_socket_request_retry(&tx, retry_delay).await {
                            return;
                        }
                        retry_delay = next_socket_request_retry_delay(retry_delay);
                        continue;
                    }
                    retry_delay = SOCKET_REQUEST_INITIAL_RETRY_DELAY;
                    for socket in sockets {
                        if tx.send(socket).await.is_err() {
                            return;
                        }
                    }
                }
            });
            Mutex::new(rx)
        }};
    }

    SocketQueue {
        tcp_v4: create_receiver!(IpV4),
        tcp_v6: ipv6_enabled.then(|| create_receiver!(IpV6)),
        udp_v4: create_receiver!(IpV4),
        udp_v6: ipv6_enabled.then(|| create_receiver!(IpV6)),
    }
}

#[cfg(all(target_os = "linux", feature = "unshare"))]
#[cfg(test)]
mod socket_request_retry_tests {
    use super::*;

    #[test]
    fn socket_request_errors_are_classified_for_retry() {
        for kind in [
            std::io::ErrorKind::Interrupted,
            std::io::ErrorKind::WouldBlock,
            std::io::ErrorKind::TimedOut,
            std::io::ErrorKind::ConnectionReset,
        ] {
            assert!(is_recoverable_socket_request_error(&std::io::Error::from(kind).into()));
        }
        for kind in [
            std::io::ErrorKind::InvalidInput,
            std::io::ErrorKind::InvalidData,
            std::io::ErrorKind::PermissionDenied,
        ] {
            assert!(!is_recoverable_socket_request_error(&std::io::Error::from(kind).into()));
        }
        assert!(!is_recoverable_socket_request_error(&crate::Error::from(
            "invalid socket-transfer response"
        )));
    }

    #[test]
    fn socket_request_retry_delay_grows_and_is_capped() {
        let next = next_socket_request_retry_delay(SOCKET_REQUEST_INITIAL_RETRY_DELAY);
        assert_eq!(next, std::time::Duration::from_millis(200));

        let capped = (0..10).fold(SOCKET_REQUEST_INITIAL_RETRY_DELAY, |delay, _| {
            next_socket_request_retry_delay(delay)
        });
        assert_eq!(capped, SOCKET_REQUEST_MAX_RETRY_DELAY);
    }
}

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn abz_protect_socket_fd(fd: i32) -> bool;
}

fn ensure_socket_protected_with(
    fd: i32,
    protect: impl FnOnce(i32) -> bool,
) -> std::io::Result<()> {
    if fd < 0 || !protect(fd) {
        return Err(std::io::Error::new(ErrorKind::PermissionDenied, "Android VPN socket protection failed"));
    }
    Ok(())
}

fn ensure_socket_protected(fd: i32) -> std::io::Result<()> {
    #[cfg(target_os = "android")]
    {
        return ensure_socket_protected_with(fd, |raw_fd| unsafe { abz_protect_socket_fd(raw_fd) });
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = fd;
        Ok(())
    }
}

async fn create_tcp_stream(socket_queue: &Option<Arc<SocketQueue>>, peer: SocketAddr) -> std::io::Result<TcpStream> {
    match &socket_queue {
        None => {
            let socket = match peer {
                SocketAddr::V4(_) => TcpSocket::new_v4()?,
                SocketAddr::V6(_) => TcpSocket::new_v6()?,
            };
            ensure_socket_protected(socket.as_raw_fd())?;
            socket.connect(peer).await
        }
        Some(queue) => queue.recv_tcp(peer.ip().into()).await?.connect(peer).await,
    }
}

async fn create_udp_stream(socket_queue: &Option<Arc<SocketQueue>>, peer: SocketAddr) -> std::io::Result<UdpStream> {
    match &socket_queue {
        None => {
            use socket2::{Domain, Protocol, SockAddr, Socket, Type};
            let domain = match peer {
                SocketAddr::V4(_) => Domain::IPV4,
                SocketAddr::V6(_) => Domain::IPV6,
            };
            let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
            ensure_socket_protected(socket.as_raw_fd())?;
            socket.set_nonblocking(true)?;
            let bind_addr = match peer {
                SocketAddr::V4(_) => SocketAddr::from((std::net::Ipv4Addr::UNSPECIFIED, 0)),
                SocketAddr::V6(_) => SocketAddr::from((std::net::Ipv6Addr::UNSPECIFIED, 0)),
            };
            socket.bind(&SockAddr::from(bind_addr))?;
            let std_socket: std::net::UdpSocket = socket.into();
            let socket = UdpSocket::from_std(std_socket)?;
            socket.connect(peer).await?;
            UdpStream::from_tokio(socket, peer).await
        }
        Some(queue) => {
            let socket = queue.recv_udp(peer.ip().into()).await?;
            socket.connect(peer).await?;
            UdpStream::from_tokio(socket, peer).await
        }
    }
}

async fn open_proxy_connection(
    socket_queue: &Option<Arc<SocketQueue>>,
    proxy_handler: Arc<Mutex<dyn ProxyHandler>>,
) -> crate::Result<(TcpStream, Option<SocketAddr>)> {
    open_proxy_connection_with_timeouts(
        socket_queue,
        proxy_handler,
        PROXY_CONNECT_TIMEOUT,
        PROXY_HANDSHAKE_TIMEOUT,
    )
    .await
}

async fn open_proxy_connection_with_timeouts(
    socket_queue: &Option<Arc<SocketQueue>>,
    proxy_handler: Arc<Mutex<dyn ProxyHandler>>,
    connect_timeout: Duration,
    handshake_timeout: Duration,
) -> crate::Result<(TcpStream, Option<SocketAddr>)> {
    let server_addr = { proxy_handler.lock().await.get_server_addr() };
    let mut server = tokio::time::timeout(
        connect_timeout,
        create_tcp_stream(socket_queue, server_addr),
    )
    .await
    .map_err(|_| {
        std::io::Error::new(
            ErrorKind::TimedOut,
            format!("Timed out connecting to upstream proxy {server_addr}"),
        )
    })??;

    let udp_addr = tokio::time::timeout(
        handshake_timeout,
        handle_proxy_session(&mut server, proxy_handler),
    )
    .await
    .map_err(|_| {
        std::io::Error::new(
            ErrorKind::TimedOut,
            format!(
                "Upstream proxy handshake timed out after {} ms",
                handshake_timeout.as_millis()
            ),
        )
    })??;

    Ok((server, udp_addr))
}


/// Run the proxy server
/// # Arguments
/// * `device` - The network device to use
/// * `mtu` - The MTU of the network device
/// * `args` - The arguments to use
/// * `shutdown_token` - The token to exit the server
/// # Returns
/// * The number of sessions while exiting
pub async fn run<D>(device: D, mtu: u16, args: Args, shutdown_token: CancellationToken) -> crate::Result<usize>
where
    D: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    log::info!("{} {} starting...", env!("CARGO_PKG_NAME"), version_info!());

    let server_addr = if let Some(addr) = args.proxy.addr.as_ref() {
        addr.resolve_using_tokio().await?.next()
    } else {
        None
    };

    if let Some(addr) = server_addr {
        log::info!("Proxy {} server: {addr}", args.proxy.proxy_type);
    }

    let key = args.proxy.credentials.clone();
    let dns_addr = args.dns_addr;
    let ipv6_enabled = args.ipv6_enabled;
    let virtual_dns = if args.dns == ArgDns::Virtual {
        Some(Arc::new(Mutex::new(VirtualDns::new(args.virtual_dns_pool))))
    } else {
        None
    };

    #[cfg(all(target_os = "linux", feature = "unshare"))]
    let socket_queue = match args.socket_transfer_fd {
        None => None,
        Some(fd) => {
            use crate::socket_transfer::{reconstruct_socket, reconstruct_transfer_socket};

            let fd = reconstruct_socket(fd)?;
            let socket = reconstruct_transfer_socket(fd)?;
            let socket = Arc::new(Mutex::new(socket));
            Some(Arc::new(create_socket_queue(socket, ipv6_enabled)))
        }
    };

    #[cfg(not(all(target_os = "linux", feature = "unshare")))]
    let socket_queue = None;

    use socks5_impl::protocol::Version::{V4, V5};
    let mgr: Arc<dyn ProxyHandlerManager> = match args.proxy.proxy_type {
        ProxyType::Socks5 => Arc::new(SocksProxyManager::new(server_addr.unwrap(), V5, key)),
        ProxyType::Socks4 => Arc::new(SocksProxyManager::new(server_addr.unwrap(), V4, key)),
        ProxyType::Http => Arc::new(HttpManager::new(server_addr.unwrap(), key)),
        ProxyType::None => Arc::new(NoProxyManager::new()),
        ProxyType::Mixed => unreachable!("Mixed proxy type should not be used here"),
    };

    let mut ipstack_config = ipstack::IpStackConfig::default();
    ipstack_config.mtu(mtu)?;
    let mut tcp_cfg = ipstack::TcpConfig::default();
    tcp_cfg.timeout = std::time::Duration::from_secs(args.tcp_timeout);
    if let Some(mss) = args.tcp_mss {
        tcp_cfg.options = Some(vec![ipstack::TcpOptions::MaximumSegmentSize(mss)]);
    }
    ipstack_config.with_tcp_config(tcp_cfg);
    ipstack_config.udp_timeout(std::time::Duration::from_secs(args.udp_timeout));

    let mut ip_stack = ipstack::IpStack::new(ipstack_config, device);

    #[cfg(feature = "udpgw")]
    let udpgw_client = args.udpgw_server.map(|addr| {
        log::info!("UDP Gateway enabled, server: {addr}");
        use std::time::Duration;
        let client = Arc::new(UdpGwClient::new(
            mtu,
            args.udpgw_connections.unwrap_or(UDPGW_MAX_CONNECTIONS),
            args.udpgw_keepalive.map(Duration::from_secs).unwrap_or(UDPGW_KEEPALIVE_TIME),
            args.udp_timeout,
            addr,
        ));
        let client_keepalive = client.clone();
        let shutdown_clone = shutdown_token.clone();
        tokio::spawn(async move {
            if let Err(err) = client_keepalive.heartbeat_task(shutdown_clone).await {
                log::error!("UDP Gateway heartbeat task error: {err}");
            }
        });
        client
    });

    let task_count = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let mut session_tasks = tokio::task::JoinSet::new();
    use std::sync::atomic::Ordering::Relaxed;

    let result: std::io::Result<()> = 'run: loop {
        let task_count = task_count.clone();
        let virtual_dns = virtual_dns.clone();
        let ip_stack_stream = tokio::select! {
            _ = shutdown_token.cancelled() => {
                log::info!("Shutdown received");
                break 'run Ok(());
            }
            _ = session_tasks.join_next(), if !session_tasks.is_empty() => {
                continue;
            }
            ip_stack_stream = ip_stack.accept() => {
                match ip_stack_stream {
                    Ok(stream) => stream,
                    Err(err) => break 'run Err(err.into()),
                }
            }
        };
        let max_sessions = args.max_sessions;
        match ip_stack_stream {
            IpStackStream::Tcp(tcp) => {
                if task_count.load(Relaxed) >= max_sessions {
                    if args.exit_on_fatal_error {
                        log::info!("Too many sessions that over {max_sessions}, exiting...");
                        break 'run Ok(());
                    }
                    log::warn!("Too many sessions that over {max_sessions}, dropping new session");
                    continue;
                }
                let session_count = SessionCountGuard::new(task_count.clone());
                let info = SessionInfo::new(tcp.local_addr(), tcp.peer_addr(), IpProtocol::Tcp);
                if info.dst.port() == DNS_PORT && args.dns == ArgDns::Virtual {
                    let dns_info = normalize_dns_destination(info, dns_addr);
                    let manager = mgr.clone();
                    let queue = socket_queue.clone();
                    let dns = virtual_dns.clone();
                    session_tasks.spawn(async move {
                        let _session_count = session_count;
                        if let Some(dns) = dns {
                            if let Err(err) = handle_virtual_dns_tcp_session(
                                tcp,
                                dns,
                                dns_info,
                                manager,
                                queue,
                                ipv6_enabled,
                            )
                            .await
                            {
                                log::error!("{dns_info} virtual DNS-over-TCP error \"{err}\"");
                            }
                        } else {
                            log::error!("{dns_info} virtual DNS mode has no DNS mapping table");
                        }
                    });
                    continue;
                }
                if info.dst.port() == DNS_PORT && args.dns == ArgDns::OverTcp {
                    // Remote-DNS mode sends TCP DNS directly to the resolver
                    // through the configured proxy.
                    let dns_info = normalize_dns_destination(info, dns_addr);
                    let proxy_handler = match mgr.new_proxy_handler(dns_info, None, false).await {
                        Ok(proxy_handler) => proxy_handler,
                        Err(err) => break 'run Err(err),
                    };
                    let queue = socket_queue.clone();
                    session_tasks.spawn(async move {
                        let _session_count = session_count;
                        if let Err(err) = handle_tcp_session(tcp, proxy_handler, queue).await {
                            log::error!("{dns_info} DNS-over-TCP proxy error \"{err}\"");
                        }
                    });
                    continue;
                }
                let domain_name = if let Some(virtual_dns) = &virtual_dns {
                    let mut virtual_dns = virtual_dns.lock().await;
                    virtual_dns.touch_ip(&tcp.peer_addr().ip());
                    virtual_dns.resolve_ip(&tcp.peer_addr().ip()).cloned()
                } else {
                    None
                };
                let proxy_handler = match mgr.new_proxy_handler(info, domain_name, false).await {
                    Ok(proxy_handler) => proxy_handler,
                    Err(err) => break 'run Err(err),
                };
                let socket_queue = socket_queue.clone();
                session_tasks.spawn(async move {
                    let _session_count = session_count;
                    if let Err(err) = handle_tcp_session(tcp, proxy_handler, socket_queue).await {
                        log::error!("{info} error \"{err}\"");
                    }
                });
            }
            IpStackStream::Udp(udp) => {
                if task_count.load(Relaxed) >= max_sessions {
                    if args.exit_on_fatal_error {
                        log::info!("Too many sessions that over {max_sessions}, exiting...");
                        break 'run Ok(());
                    }
                    log::warn!("Too many sessions that over {max_sessions}, dropping new session");
                    continue;
                }
                let session_count = SessionCountGuard::new(task_count.clone());
                let mut info = SessionInfo::new(udp.local_addr(), udp.peer_addr(), IpProtocol::Udp);
                if info.dst.port() == DNS_PORT {
                    info = normalize_dns_destination(info, dns_addr);
                    if args.dns == ArgDns::OverTcp {
                        info.protocol = IpProtocol::Tcp;
                        let proxy_handler = match mgr.new_proxy_handler(info, None, false).await {
                            Ok(proxy_handler) => proxy_handler,
                            Err(err) => break 'run Err(err),
                        };
                        let socket_queue = socket_queue.clone();
                        session_tasks.spawn(async move {
                            let _session_count = session_count;
                            if let Err(err) = handle_dns_over_tcp_session(udp, proxy_handler, socket_queue, ipv6_enabled).await {
                                log::error!("{info} error \"{err}\"");
                            }
                        });
                        continue;
                    }
                    if args.dns == ArgDns::Virtual {
                        let manager = mgr.clone();
                        let queue = socket_queue.clone();
                        session_tasks.spawn(async move {
                            let _session_count = session_count;
                            if let Some(virtual_dns) = virtual_dns
                                && let Err(err) = handle_virtual_dns_session(
                                    udp,
                                    virtual_dns,
                                    info,
                                    manager,
                                    queue,
                                    ipv6_enabled,
                                )
                                .await
                            {
                                log::error!("{info} virtual DNS session error \"{err}\"");
                            }
                        });
                        continue;
                    }
                    assert_eq!(args.dns, ArgDns::Direct);
                }
                // DNS above remains available over UDP so Android name resolution works.
                // Other UDP (including QUIC) is deliberately not forwarded by default;
                // clients can fall back to TCP. Use --udp-enabled to opt in.
                if !args.udp_enabled {
                    log::debug!("Dropping non-DNS UDP session by default: {info}");
                    continue;
                }
                let domain_name = if let Some(virtual_dns) = &virtual_dns {
                    let mut virtual_dns = virtual_dns.lock().await;
                    virtual_dns.touch_ip(&udp.peer_addr().ip());
                    virtual_dns.resolve_ip(&udp.peer_addr().ip()).cloned()
                } else {
                    None
                };
                #[cfg(feature = "udpgw")]
                if let Some(udpgw) = udpgw_client.clone() {
                    let tcp_src = match udp.peer_addr() {
                        SocketAddr::V4(_) => SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)),
                        SocketAddr::V6(_) => SocketAddr::V6(SocketAddrV6::new(Ipv6Addr::UNSPECIFIED, 0, 0, 0)),
                    };
                    let tcpinfo = SessionInfo::new(tcp_src, udpgw.get_udpgw_server_addr(), IpProtocol::Tcp);
                    let proxy_handler = match mgr.new_proxy_handler(tcpinfo, None, false).await {
                        Ok(proxy_handler) => proxy_handler,
                        Err(err) => break 'run Err(err),
                    };
                    let queue = socket_queue.clone();
                    session_tasks.spawn(async move {
                        let _session_count = session_count;
                        let dst = info.dst; // real UDP destination address
                        let dst_addr = match domain_name {
                            Some(ref d) => socks5_impl::Address::from((d.clone(), dst.port())),
                            None => dst.into(),
                        };
                        if let Err(e) = handle_udp_gateway_session(udp, udpgw, &dst_addr, proxy_handler, queue, ipv6_enabled).await {
                            log::info!("Ending {info} with \"{e}\"");
                        }
                    });
                    continue;
                }
                match mgr.new_proxy_handler(info, domain_name, true).await {
                    Ok(proxy_handler) => {
                        let socket_queue = socket_queue.clone();
                        session_tasks.spawn(async move {
                            let _session_count = session_count;
                            let ty = args.proxy.proxy_type;
                            if let Err(err) = handle_udp_associate_session(udp, ty, proxy_handler, socket_queue, ipv6_enabled).await {
                                log::info!("Ending {info} with \"{err}\"");
                            }
                        });
                    }
                    Err(e) => {
                        log::error!("Failed to create UDP connection: {e}");
                    }
                }
            }
            IpStackStream::UnknownTransport(u) => {
                let len = u.payload().len();
                log::info!("#0 unhandled transport - Ip Protocol {:?}, length {}", u.ip_protocol(), len);
                continue;
            }
            IpStackStream::UnknownNetwork(pkt) => {
                log::info!("#0 unknown transport - {} bytes", pkt.len());
                continue;
            }
        }
    };

    shutdown_token.cancel();
    session_tasks.abort_all();
    while session_tasks.join_next().await.is_some() {}

    result?;
    Ok(task_count.load(Relaxed))
}

#[cfg(all(test, target_os = "linux", feature = "unshare"))]
mod socket_queue_tests {
    use super::create_socket_queue;
    use std::sync::Arc;
    use tokio::sync::Mutex;

    #[tokio::test]
    async fn ipv4_only_mode_does_not_start_ipv6_queues() {
        let (local, _peer) = tokio::net::UnixDatagram::pair().unwrap();
        let queue = create_socket_queue(Arc::new(Mutex::new(local)), false);

        assert!(queue.tcp_v6.is_none());
        assert!(queue.udp_v6.is_none());
    }
}

fn normalize_dns_destination(mut info: SessionInfo, dns_addr: IpAddr) -> SessionInfo {
    if is_private_ip(info.dst.ip()) {
        info.dst.set_ip(dns_addr);
    }
    info
}

fn decode_dns_tcp_queries(buffer: &mut Vec<u8>, received: &[u8]) -> crate::Result<Vec<Vec<u8>>> {
    buffer.extend_from_slice(received);

    let mut consumed = 0;
    let mut queries = Vec::new();
    while buffer.len().saturating_sub(consumed) >= 2 {
        let message_len = u16::from_be_bytes([buffer[consumed], buffer[consumed + 1]]) as usize;
        if message_len == 0 {
            return Err("DNS-over-TCP query has an empty frame".into());
        }
        let frame_end = consumed + 2 + message_len;
        if buffer.len() < frame_end {
            break;
        }
        queries.push(buffer[consumed + 2..frame_end].to_vec());
        consumed = frame_end;
    }

    if consumed > 0 {
        buffer.drain(..consumed);
    }
    Ok(queries)
}

async fn handle_virtual_dns_tcp_session(
    mut tcp: IpStackTcpStream,
    dns: Arc<Mutex<VirtualDns>>,
    session_info: SessionInfo,
    manager: Arc<dyn ProxyHandlerManager>,
    socket_queue: Option<Arc<SocketQueue>>,
    ipv6_enabled: bool,
) -> crate::Result<()> {
    let mut buf = [0_u8; 8192];
    let mut pending = Vec::new();
    loop {
        let len = tcp.read(&mut buf).await?;
        if len == 0 {
            break;
        }
        for query in decode_dns_tcp_queries(&mut pending, &buf[..len])? {
            let synthetic_query = dns.lock().await.is_synthetic_query(&query)?;
            let response = if synthetic_query {
                let (response, qname, ip) = dns.lock().await.generate_query(&query)?;
                log::debug!("Virtual DNS-over-TCP query: {qname} -> {ip}");
                response
            } else {
                let mut dns_info = session_info;
                dns_info.protocol = IpProtocol::Tcp;
                let proxy_handler = manager.new_proxy_handler(dns_info, None, false).await?;
                let (mut server, _) = open_proxy_connection(&socket_queue, proxy_handler).await?;
                let response = forward_dns_query_over_tcp(&mut server, &query, ipv6_enabled).await?;
                log::debug!("Virtual DNS-over-TCP forwarded non-A/AAAA query through proxy: {session_info}");
                response
            };
            let response_len = u16::try_from(response.len())?;
            tcp.write_all(&response_len.to_be_bytes()).await?;
            tcp.write_all(&response).await?;
        }
    }
    Ok(())
}

async fn forward_dns_query_over_tcp<S>(
    server: &mut S,
    query: &[u8],
    ipv6_enabled: bool,
) -> crate::Result<Vec<u8>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    if query.len() < 12 {
        return Err("DNS query is shorter than its header".into());
    }

    let query_len = u16::try_from(query.len())?;
    server.write_all(&query_len.to_be_bytes()).await?;
    server.write_all(query).await?;

    let mut length_prefix = [0_u8; 2];
    server.read_exact(&mut length_prefix).await?;
    let response_len = u16::from_be_bytes(length_prefix) as usize;
    if response_len < 12 {
        return Err("DNS-over-TCP resolver returned an incomplete response".into());
    }

    let mut response = vec![0_u8; response_len];
    server.read_exact(&mut response).await?;
    if response.get(0..2) != query.get(0..2) || response[2] & 0x80 == 0 {
        return Err("DNS-over-TCP resolver returned a mismatched response".into());
    }

    let mut message = dns::parse_data_to_dns_message(&response, false)?;
    if !ipv6_enabled {
        dns::remove_ipv6_entries(&mut message);
    }
    Ok(message.to_vec()?)
}

async fn handle_virtual_dns_session(
    mut udp: IpStackUdpStream,
    dns: Arc<Mutex<VirtualDns>>,
    session_info: SessionInfo,
    manager: Arc<dyn ProxyHandlerManager>,
    socket_queue: Option<Arc<SocketQueue>>,
    ipv6_enabled: bool,
) -> crate::Result<()> {
    let mut buf = [0_u8; 4096];
    loop {
        let len = match udp.read(&mut buf).await {
            Err(e) => {
                // A closed UDP session is normal; report it at debug level.
                log::debug!("Virtual DNS session {session_info} closed: {e}");
                break;
            }
            Ok(len) => len,
        };
        if len == 0 {
            break;
        }

        let query = &buf[..len];
        let synthetic_query = dns.lock().await.is_synthetic_query(query)?;
        let response = if synthetic_query {
            let (message, qname, ip) = dns.lock().await.generate_query(query)?;
            log::debug!("Virtual DNS query: {qname} -> {ip}");
            message
        } else {
            // Preserve SRV/TXT/HTTPS/SVCB and other records by asking the real
            // resolver through the same proxy. Never fall back to device DNS.
            let mut dns_info = session_info;
            dns_info.protocol = IpProtocol::Tcp;
            let proxy_handler = manager.new_proxy_handler(dns_info, None, false).await?;
            let (mut server, _) = open_proxy_connection(&socket_queue, proxy_handler).await?;
            let response = forward_dns_query_over_tcp(&mut server, query, ipv6_enabled).await?;
            log::debug!("Virtual DNS forwarded non-A/AAAA query through proxy: {session_info}");
            response
        };

        udp.write_all(&response).await?;
    }
    Ok(())
}

async fn copy_and_record_traffic<R, W>(reader: &mut R, writer: &mut W, is_tx: bool) -> tokio::io::Result<u64>
where
    R: tokio::io::AsyncRead + Unpin + ?Sized,
    W: tokio::io::AsyncWrite + Unpin + ?Sized,
{
    let mut buf = vec![0; 8192];
    let mut total = 0;
    loop {
        match reader.read(&mut buf).await? {
            0 => break, // EOF
            n => {
                total += n as u64;
                let (tx, rx) = if is_tx { (n, 0) } else { (0, n) };
                if let Err(e) = crate::traffic_status::traffic_status_update(tx, rx) {
                    log::debug!("Record traffic status error: {e}");
                }
                writer.write_all(&buf[..n]).await?;
            }
        }
    }
    Ok(total)
}

async fn handle_tcp_session(
    mut tcp_stack: IpStackTcpStream,
    proxy_handler: Arc<Mutex<dyn ProxyHandler>>,
    socket_queue: Option<Arc<SocketQueue>>,
) -> crate::Result<()> {
    let (session_info, server_addr) = {
        let handler = proxy_handler.lock().await;
        (handler.get_session_info(), handler.get_server_addr())
    };

    log::info!("TCP flow {session_info}: opening upstream proxy socket to {server_addr}");
    let (server, _) = match open_proxy_connection(&socket_queue, proxy_handler).await {
        Ok(connection) => connection,
        Err(error) => {
            log::warn!("TCP flow {session_info}: upstream proxy connect/handshake failed: {error}");
            if let Err(shutdown_error) = tcp_stack.shutdown().await {
                log::debug!("TCP flow {session_info}: shutdown after proxy failure: {shutdown_error}");
            }
            return Err(error);
        }
    };
    log::info!("TCP flow {session_info}: upstream proxy CONNECT established; starting bidirectional relay");

    let (mut t_rx, mut t_tx) = tokio::io::split(tcp_stack);
    let (mut s_rx, mut s_tx) = tokio::io::split(server);

    // Preserve TCP half-close semantics on normal EOF, but cancel the opposite
    // relay immediately if either direction errors. join! could wait forever
    // for the other direction and retain a dead session until its idle timeout.
    let relay_result = tokio::try_join!(
        async move {
            let result = copy_and_record_traffic(&mut t_rx, &mut s_tx, true).await;
            if let Err(error) = s_tx.shutdown().await {
                log::debug!("TCP flow {session_info}: client-to-proxy half-close: {error}");
            }
            result
        },
        async move {
            let result = copy_and_record_traffic(&mut s_rx, &mut t_tx, false).await;
            if let Err(error) = t_tx.shutdown().await {
                log::debug!("TCP flow {session_info}: proxy-to-client half-close: {error}");
            }
            result
        },
    );

    match relay_result {
        Ok((client_to_proxy, proxy_to_client)) => {
            log::info!("TCP flow {session_info}: client -> proxy finished ({client_to_proxy} bytes)");
            log::info!("TCP flow {session_info}: proxy -> client finished ({proxy_to_client} bytes)");
            Ok(())
        }
        Err(error) => {
            log::warn!("TCP flow {session_info}: relay failed; both directions are being closed: {error}");
            Err(error.into())
        }
    }
}

#[cfg(feature = "udpgw")]
async fn handle_udp_gateway_session(
    mut udp_stack: IpStackUdpStream,
    udpgw_client: Arc<UdpGwClient>,
    udp_dst: &socks5_impl::Address,
    proxy_handler: Arc<Mutex<dyn ProxyHandler>>,
    socket_queue: Option<Arc<SocketQueue>>,
    ipv6_enabled: bool,
) -> crate::Result<()> {
    let udp_mtu = udpgw_client.get_udp_mtu();
    let udp_timeout = udpgw_client.get_udp_timeout();

    let mut stream = loop {
        match udpgw_client.pop_server_connection_from_queue().await {
            Some(mut stream) => {
                if stream.is_closed() {
                    continue;
                } else {
                    stream.rotate_serial_number();
                    break stream;
                }
            }
            None => {
                let (tcp_server_stream, _) = match open_proxy_connection(&socket_queue, proxy_handler).await {
                    Ok(connection) => connection,
                    Err(error) => return Err(format!("udpgw connection error: {error}").into()),
                };
                break UdpGwClientStream::new(tcp_server_stream);
            }
        }
    };

    let tcp_local_addr = stream.local_addr();
    let sn = stream.serial_number();

    log::info!("[UdpGw] Beginning stream {} {} -> {}", sn, tcp_local_addr, udp_dst);

    let Some(mut reader) = stream.get_reader() else {
        return Err("get reader failed".into());
    };

    let Some(mut writer) = stream.get_writer() else {
        return Err("get writer failed".into());
    };

    let mut tmp_buf = vec![0; udp_mtu.into()];
    // Partial inbound frame kept outside any future: makes the recv branch
    // cancellation-safe under tokio::select! (see recv_udpgw_packet_csafe).
    let mut recv_acc: Vec<u8> = Vec::with_capacity(4096);
    // The connection may only go back to the pool when the flow ends without
    // leaving a partially read frame behind (see recv_udpgw_packet_csafe).
    let mut reusable = false;

    loop {
        tokio::select! {
            len = udp_stack.read(&mut tmp_buf) => {
                let read_len = match len {
                    Ok(0) => {
                        log::info!("[UdpGw] Ending stream {} {} <> {}", sn, tcp_local_addr, udp_dst);
                        reusable = true;
                        break;
                    }
                    Ok(n) => n,
                    Err(e) => {
                        log::info!("[UdpGw] Ending stream {} {} <> {} with udp stack \"{}\"", sn, tcp_local_addr, udp_dst, e);
                        reusable = true;
                        break;
                    }
                };
                crate::traffic_status::traffic_status_update(read_len, 0)?;
                let sn = stream.serial_number();
                if let Err(e) = UdpGwClient::send_udpgw_packet(ipv6_enabled, &tmp_buf[0..read_len], udp_dst, sn, &mut writer).await {
                    log::info!("[UdpGw] Ending stream {} {} <> {} with send_udpgw_packet {}", sn, tcp_local_addr, udp_dst, e);
                    break;
                }
                log::debug!("[UdpGw] stream {} {} -> {} send len {}", sn, tcp_local_addr, udp_dst, read_len);
                stream.update_activity();
            }
            // Cancellation-safe reader (plain read() + caller-owned buffer):
            // a select! cancellation mid-frame must never lose socket bytes.
            // The per-read timeout is refreshed on every loop iteration, i.e.
            // whenever the application sends a datagram, so active flows keep
            // the stream alive while idle ones are still closed after udp_timeout.
            ret = UdpGwClient::recv_udpgw_packet_csafe(udp_mtu, udp_timeout, &mut reader, &mut recv_acc) => {
                if let Ok((len, _)) = ret {
                    crate::traffic_status::traffic_status_update(0, len)?;
                }
                match ret {
                    Err(e) => {
                        log::warn!("[UdpGw] Ending stream {} {} <> {} with recv_udpgw_packet {}", sn, tcp_local_addr, udp_dst, e);
                        stream.close();
                        break;
                    }
                    Ok((_, packet)) => match packet {
                        //should not received keepalive
                        UdpGwResponse::KeepAlive => {
                            log::error!("[UdpGw] Ending stream {} {} <> {} with recv keepalive", sn, tcp_local_addr, udp_dst);
                            stream.close();
                            break;
                        }
                        //server udp may be timeout,can continue to receive udp data?
                        UdpGwResponse::Error => {
                            log::info!("[UdpGw] Ending stream {} {} <> {} with recv udp error", sn, tcp_local_addr, udp_dst);
                            stream.update_activity();
                            continue;
                        }
                        UdpGwResponse::TcpClose => {
                            log::error!("[UdpGw] Ending stream {} {} <> {} with tcp closed", sn, tcp_local_addr, udp_dst);
                            stream.close();
                            break;
                        }
                        UdpGwResponse::Data(data) => {
                            let conn_id = data.header.conn_id;
                            if conn_id != sn {
                                log::debug!("[UdpGw] stream {sn} {tcp_local_addr} <- {udp_dst} ignoring late packet for retired conn_id {conn_id}");
                                stream.update_activity();
                                continue;
                            }
                            use socks5_impl::StreamOperation;
                            let len = data.len();
                            let f = data.header.flags;
                            log::debug!("[UdpGw] stream {sn} {} <- {} receive {f} len {len}", tcp_local_addr, udp_dst);
                            if let Err(e) = udp_stack.write_all(&data.data).await {
                                log::error!("[UdpGw] Ending stream {} {} <> {} with send_udp_packet {}", sn, tcp_local_addr, udp_dst, e);
                                reusable = true;
                                break;
                            }
                        }
                    }
                }
                stream.update_activity();
            }
        }
    }

    // A connection may only return to the pool when no partial inbound frame is
    // buffered: `recv_acc` can still hold bytes read from the socket before the
    // recv branch was cancelled, and the next reader would start mid-stream.
    if reusable && recv_acc.is_empty() {
        match UdpGwClient::send_udpgw_close_packet(sn, &mut writer).await {
            Ok(len) => {
                crate::traffic_status::traffic_status_update(len, 0)?;
                stream.update_activity();
                udpgw_client.store_server_connection_full(stream, reader, writer).await;
            }
            Err(e) => {
                log::debug!("[UdpGw] stream {sn} {tcp_local_addr} -> {udp_dst} send close packet failed: {e}");
                stream.close();
            }
        }
    } else {
        stream.close();
    }
    Ok(())
}

/// Select a usable UDP relay endpoint from the SOCKS5 BND.ADDR response.
/// Some proxies advertise an unspecified address (RFC 1928) or incorrectly
/// advertise loopback even though the client reached them over the network.
fn normalize_udp_relay_addr(proxy_addr: SocketAddr, advertised: SocketAddr) -> crate::Result<SocketAddr> {
    if advertised.port() == 0 {
        return Err("SOCKS5 UDP relay returned an invalid port 0".into());
    }

    let unusable_advertised_ip = advertised.ip().is_unspecified()
        || (advertised.ip().is_loopback() && !proxy_addr.ip().is_loopback());
    if unusable_advertised_ip {
        Ok(SocketAddr::new(proxy_addr.ip(), advertised.port()))
    } else {
        Ok(advertised)
    }
}

async fn handle_udp_associate_session(
    mut udp_stack: IpStackUdpStream,
    proxy_type: ProxyType,
    proxy_handler: Arc<Mutex<dyn ProxyHandler>>,
    socket_queue: Option<Arc<SocketQueue>>,
    ipv6_enabled: bool,
) -> crate::Result<()> {
    use socks5_impl::{Address, StreamOperation, protocol::UdpHeader};

    let (session_info, server_addr, domain_name, udp_addr) = {
        let handler = proxy_handler.lock().await;
        (
            handler.get_session_info(),
            handler.get_server_addr(),
            handler.get_domain_name(),
            handler.get_udp_associate(),
        )
    };

    log::info!("Beginning {session_info}");

    // `_server` is meaningful here, it must be alive all the time
    // to ensure that UDP transmission will not be interrupted accidentally.
    let (_server, udp_addr) = match udp_addr {
        Some(udp_addr) => (None, udp_addr),
        None => {
            let (server, udp_addr) = open_proxy_connection(&socket_queue, proxy_handler).await?;
            (Some(server), udp_addr.ok_or("udp associate failed")?)
        }
    };

    // RFC 1928 allows an unspecified relay address. Also compensate for
    // proxies that incorrectly advertise loopback to remote clients.
    let advertised_udp_addr = udp_addr;
    let udp_addr = normalize_udp_relay_addr(server_addr, advertised_udp_addr)?;
    if udp_addr != advertised_udp_addr {
        log::warn!(
            "SOCKS5 UDP relay address adjusted: advertised={advertised_udp_addr}, proxy={server_addr}, effective={udp_addr}"
        );
    } else {
        log::info!(
            "SOCKS5 UDP relay address accepted: advertised={advertised_udp_addr}, proxy={server_addr}"
        );
    }

    let mut udp_server = create_udp_stream(&socket_queue, udp_addr).await?;

    let mut buf1 = [0_u8; 4096];
    let mut buf2 = [0_u8; 4096];
    loop {
        tokio::select! {
            len = udp_stack.read(&mut buf1) => {
                let len = len?;
                if len == 0 {
                    break;
                }
                let buf1 = &buf1[..len];

                crate::traffic_status::traffic_status_update(len, 0)?;

                if let ProxyType::Socks4 | ProxyType::Socks5 = proxy_type {
                    let s5addr = if let Some(domain_name) = &domain_name {
                        Address::DomainAddress(domain_name.clone().into(), session_info.dst.port())
                    } else {
                        session_info.dst.into()
                    };

                    // Add SOCKS5 UDP header to the incoming data
                    let mut s5_udp_data = Vec::<u8>::new();
                    UdpHeader::new(0, s5addr).write_to_stream(&mut s5_udp_data)?;
                    s5_udp_data.extend_from_slice(buf1);

                    udp_server.write_all(&s5_udp_data).await?;
                } else {
                    udp_server.write_all(buf1).await?;
                }
            }
            len = udp_server.read(&mut buf2) => {
                let len = len?;
                if len == 0 {
                    break;
                }
                let buf2 = &buf2[..len];

                crate::traffic_status::traffic_status_update(0, len)?;

                if let ProxyType::Socks4 | ProxyType::Socks5 = proxy_type {
                    // Remove SOCKS5 UDP header from the server data
                    let header = UdpHeader::retrieve_from_stream(&mut &buf2[..])?;
                    let data = &buf2[header.len()..];

                    let buf = if session_info.dst.port() == DNS_PORT {
                        let mut message = dns::parse_data_to_dns_message(data, false)?;
                        if !ipv6_enabled {
                            dns::remove_ipv6_entries(&mut message);
                        }
                        message.to_vec()?
                    } else {
                        data.to_vec()
                    };

                    udp_stack.write_all(&buf).await?;
                } else {
                    udp_stack.write_all(buf2).await?;
                }
            }
        }
    }

    log::info!("Ending {session_info}");

    Ok(())
}

async fn handle_dns_over_tcp_session(
    mut udp_stack: IpStackUdpStream,
    proxy_handler: Arc<Mutex<dyn ProxyHandler>>,
    socket_queue: Option<Arc<SocketQueue>>,
    ipv6_enabled: bool,
) -> crate::Result<()> {
    let session_info = { proxy_handler.lock().await.get_session_info() };
    let (mut server, _) = open_proxy_connection(&socket_queue, proxy_handler).await?;

    log::info!("Beginning {session_info}");

    let mut buf1 = [0_u8; 4096];
    let mut buf2 = [0_u8; 4096];
    let mut response_buffer = Vec::new();
    loop {
        tokio::select! {
            len = udp_stack.read(&mut buf1) => {
                let len = len?;
                if len == 0 {
                    break;
                }
                let buf1 = &buf1[..len];

                _ = dns::parse_data_to_dns_message(buf1, false)?;

                // Insert the DNS message length in front of the payload
                let len = u16::try_from(buf1.len())?;
                let mut buf = Vec::with_capacity(std::mem::size_of::<u16>() + usize::from(len));
                buf.extend_from_slice(&len.to_be_bytes());
                buf.extend_from_slice(buf1);

                server.write_all(&buf).await?;

                crate::traffic_status::traffic_status_update(buf.len(), 0)?;
            }
            len = server.read(&mut buf2) => {
                let len = len?;
                if len == 0 {
                    break;
                }
                crate::traffic_status::traffic_status_update(0, len)?;

                for packet in decode_dns_tcp_responses(&mut response_buffer, &buf2[..len], ipv6_enabled)? {
                    udp_stack.write_all(&packet).await?;
                }
            }
        }
    }

    log::info!("Ending {session_info}");

    Ok(())
}

fn decode_dns_tcp_responses(buffer: &mut Vec<u8>, received: &[u8], ipv6_enabled: bool) -> crate::Result<Vec<Vec<u8>>> {
    buffer.extend_from_slice(received);

    let mut consumed = 0;
    let mut responses = Vec::new();
    while buffer.len() - consumed >= 2 {
        let message_len = u16::from_be_bytes([buffer[consumed], buffer[consumed + 1]]) as usize;
        let frame_end = consumed + 2 + message_len;
        if buffer.len() < frame_end {
            break;
        }

        let mut message = dns::parse_data_to_dns_message(&buffer[consumed + 2..frame_end], false)?;
        let name = dns::extract_domain_from_dns_message(&message)?;
        let ip = dns::extract_ipaddr_from_dns_message(&message);
        log::trace!("DNS over TCP query result: {name} -> {ip:?}");

        if !ipv6_enabled {
            dns::remove_ipv6_entries(&mut message);
        }
        responses.push(message.to_vec()?);
        consumed = frame_end;
    }

    if consumed > 0 {
        buffer.drain(..consumed);
    }
    Ok(responses)
}

/// This function is used to handle the business logic of tun2proxy and SOCKS5 server.
/// When handling UDP proxy, the return value UDP associate IP address is the result of this business logic.
/// However, when handling TCP business logic, the return value Ok(None) is meaningless, just indicating that the operation was successful.
async fn handle_proxy_session(server: &mut TcpStream, proxy_handler: Arc<Mutex<dyn ProxyHandler>>) -> crate::Result<Option<SocketAddr>> {
    let mut launched = false;
    let mut proxy_handler = proxy_handler.lock().await;
    let dir = OutgoingDirection::ToServer;
    let (mut tx, mut rx) = (0, 0);

    loop {
        if proxy_handler.connection_established() {
            break;
        }

        if !launched {
            let data = proxy_handler.peek_data(dir).buffer;
            let len = data.len();
            if len == 0 {
                return Err("proxy_handler launched went wrong".into());
            }
            server.write_all(data).await?;
            proxy_handler.consume_data(dir, len);
            tx += len;

            launched = true;
        }

        let mut buf = [0_u8; 4096];
        let len = server.read(&mut buf).await?;
        if len == 0 {
            return Err("server closed accidentially".into());
        }
        rx += len;
        let event = IncomingDataEvent {
            direction: IncomingDirection::FromServer,
            buffer: &buf[..len],
        };
        proxy_handler.push_data(event).await?;

        let data = proxy_handler.peek_data(dir).buffer;
        let len = data.len();
        if len > 0 {
            server.write_all(data).await?;
            proxy_handler.consume_data(dir, len);
            tx += len;
        }
    }
    crate::traffic_status::traffic_status_update(tx, rx)?;
    Ok(proxy_handler.get_udp_associate())
}

#[cfg(test)]
mod dns_tcp_response_tests {
    use super::*;
    use hickory_proto::{
        op::{Message, MessageType, OpCode, Query},
        rr::{
            Name, RData, Record, RecordType,
            rdata::{A, AAAA},
        },
    };
    use std::{
        net::{Ipv4Addr, Ipv6Addr},
        str::FromStr,
    };

    fn response_frame(name: &str, record_type: RecordType) -> Vec<u8> {
        let name = Name::from_str(name).unwrap();
        let mut message = Message::new(0, MessageType::Response, OpCode::Query);
        message.add_query(Query::query(name.clone(), record_type));
        let answer = match record_type {
            RecordType::A => Record::from_rdata(name, 60, RData::A(A(Ipv4Addr::new(192, 0, 2, 1)))),
            RecordType::AAAA => Record::from_rdata(name, 60, RData::AAAA(AAAA(Ipv6Addr::LOCALHOST))),
            _ => panic!("test response must use A or AAAA"),
        };
        message.add_answer(answer);
        let message = message.to_vec().unwrap();
        let mut frame = Vec::with_capacity(message.len() + 2);
        frame.extend_from_slice(&(message.len() as u16).to_be_bytes());
        frame.extend_from_slice(&message);
        frame
    }

    fn query_frame(name: &str, record_type: RecordType) -> Vec<u8> {
        let name = Name::from_str(name).unwrap();
        let mut message = Message::new(0, MessageType::Query, OpCode::Query);
        message.add_query(Query::query(name, record_type));
        let message = message.to_vec().unwrap();
        let mut frame = Vec::with_capacity(message.len() + 2);
        frame.extend_from_slice(&(message.len() as u16).to_be_bytes());
        frame.extend_from_slice(&message);
        frame
    }

    #[test]
    fn private_dns_destination_uses_configured_remote_resolver() {
        let info = SessionInfo::new(
            "10.77.0.2:53000".parse().unwrap(),
            "10.77.0.1:53".parse().unwrap(),
            IpProtocol::Tcp,
        );
        let normalized = normalize_dns_destination(info, "8.8.8.8".parse().unwrap());
        assert_eq!(normalized.dst, "8.8.8.8:53".parse().unwrap());

        let public_dns = SessionInfo::new(
            "10.77.0.2:53000".parse().unwrap(),
            "1.1.1.1:53".parse().unwrap(),
            IpProtocol::Tcp,
        );
        let unchanged = normalize_dns_destination(public_dns, "8.8.8.8".parse().unwrap());
        assert_eq!(unchanged.dst, "1.1.1.1:53".parse().unwrap());
    }

    #[test]
    fn virtual_dns_tcp_query_decoder_handles_split_and_coalesced_frames() {
        let first = query_frame("a.example.", RecordType::A);
        let second = query_frame("b.example.", RecordType::AAAA);
        let mut pending = Vec::new();

        assert!(decode_dns_tcp_queries(&mut pending, &first[..1]).unwrap().is_empty());
        assert_eq!(pending, first[..1]);
        let mut rest = first[1..].to_vec();
        rest.extend_from_slice(&second);
        let queries = decode_dns_tcp_queries(&mut pending, &rest).unwrap();
        assert_eq!(queries, vec![
            first[2..].to_vec(),
            second[2..].to_vec(),
        ]);
        assert!(pending.is_empty());
    }

    #[test]
    fn virtual_dns_tcp_query_decoder_rejects_empty_frames() {
        let mut pending = Vec::new();
        assert!(decode_dns_tcp_queries(&mut pending, &[0, 0]).is_err());
    }

    #[test]
    fn virtual_dns_tcp_query_decoder_handles_split_prefix_body_and_coalesced_frames() {
        let mut pending = Vec::new();
        assert!(decode_dns_tcp_queries(&mut pending, &[0]).unwrap().is_empty());
        assert!(decode_dns_tcp_queries(&mut pending, &[3, 1]).unwrap().is_empty());
        assert_eq!(pending, vec![0, 3, 1]);

        let queries = decode_dns_tcp_queries(&mut pending, &[2, 3, 0, 2, 4, 5]).unwrap();
        assert_eq!(queries, vec![vec![1, 2, 3], vec![4, 5]]);
        assert!(pending.is_empty());
    }

    #[test]
    fn virtual_dns_tcp_query_decoder_rejects_empty_frames() {
        assert!(decode_dns_tcp_queries(&mut Vec::new(), &[0, 0]).is_err());
    }

    #[test]
    fn dns_tcp_responses_survive_split_prefix_and_body() {
        let frame = response_frame("example.org.", RecordType::A);
        let mut pending = Vec::new();

        assert!(decode_dns_tcp_responses(&mut pending, &frame[..1], true).unwrap().is_empty());
        assert_eq!(pending, frame[..1]);
        assert!(decode_dns_tcp_responses(&mut pending, &frame[1..5], true).unwrap().is_empty());
        assert_eq!(pending, frame[..5]);

        let responses = decode_dns_tcp_responses(&mut pending, &frame[5..], true).unwrap();
        assert_eq!(responses.len(), 1);
        assert!(pending.is_empty());
        let response = dns::parse_data_to_dns_message(&responses[0], false).unwrap();
        assert_eq!(
            dns::extract_ipaddr_from_dns_message(&response).unwrap(),
            IpAddr::V4(Ipv4Addr::new(192, 0, 2, 1))
        );
    }

    #[test]
    fn dns_tcp_decoder_handles_coalesced_frames_and_ipv6_filtering() {
        let a_frame = response_frame("a.example.", RecordType::A);
        let aaaa_frame = response_frame("aaaa.example.", RecordType::AAAA);
        let mut coalesced = a_frame;
        coalesced.extend_from_slice(&aaaa_frame);
        let mut pending = Vec::new();

        let responses = decode_dns_tcp_responses(&mut pending, &coalesced, false).unwrap();
        assert_eq!(responses.len(), 2);
        assert!(pending.is_empty());
        let a_response = dns::parse_data_to_dns_message(&responses[0], false).unwrap();
        assert_eq!(a_response.answers.len(), 1);
        let aaaa_response = dns::parse_data_to_dns_message(&responses[1], false).unwrap();
        assert!(aaaa_response.answers.is_empty());
    }

    #[test]
    fn socks5_unspecified_udp_relay_uses_proxy_endpoint() {
        let proxy: SocketAddr = "10.100.93.82:1088".parse().unwrap();
        let advertised: SocketAddr = "0.0.0.0:34699".parse().unwrap();
        assert_eq!(
            normalize_udp_relay_addr(proxy, advertised).unwrap(),
            "10.100.93.82:34699".parse::<SocketAddr>().unwrap()
        );
    }

    #[test]
    fn socks5_unspecified_ipv6_udp_relay_uses_proxy_endpoint() {
        let proxy: SocketAddr = "10.100.93.82:1088".parse().unwrap();
        let advertised: SocketAddr = "[::]:34699".parse().unwrap();
        assert_eq!(
            normalize_udp_relay_addr(proxy, advertised).unwrap(),
            "10.100.93.82:34699".parse::<SocketAddr>().unwrap()
        );
    }

    #[test]
    fn socks5_loopback_relay_is_rewritten_only_for_remote_proxy() {
        let remote_proxy: SocketAddr = "10.100.93.82:1088".parse().unwrap();
        let loopback_relay: SocketAddr = "127.0.0.1:34699".parse().unwrap();
        assert_eq!(
            normalize_udp_relay_addr(remote_proxy, loopback_relay).unwrap(),
            "10.100.93.82:34699".parse::<SocketAddr>().unwrap()
        );

        let local_proxy: SocketAddr = "127.0.0.1:1088".parse().unwrap();
        assert_eq!(
            normalize_udp_relay_addr(local_proxy, loopback_relay).unwrap(),
            loopback_relay
        );
    }

    #[test]
    fn socks5_explicit_udp_relay_is_preserved_and_zero_port_is_rejected() {
        let proxy: SocketAddr = "10.100.93.82:1088".parse().unwrap();
        let advertised: SocketAddr = "10.100.93.90:34699".parse().unwrap();
        assert_eq!(normalize_udp_relay_addr(proxy, advertised).unwrap(), advertised);

        let invalid: SocketAddr = "0.0.0.0:0".parse().unwrap();
        assert!(normalize_udp_relay_addr(proxy, invalid).is_err());
    }

    #[test]
    fn session_count_guard_releases_count_on_early_exit() {
        let count = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let result: Result<(), ()> = {
            let _guard = SessionCountGuard::new(count.clone());
            assert_eq!(count.load(std::sync::atomic::Ordering::Relaxed), 1);
            Err(())
        };

        assert!(result.is_err());
        assert_eq!(count.load(std::sync::atomic::Ordering::Relaxed), 0);
    }
}

#[cfg(test)]
mod android_socket_protection_tests {
    use super::ensure_socket_protected_with;
    use std::io::ErrorKind;
    use std::sync::{Arc, Mutex};

    #[test]
    fn protector_receives_the_raw_descriptor() {
        let observed = Arc::new(Mutex::new(None));
        let captured = Arc::clone(&observed);
        ensure_socket_protected_with(42, move |fd| {
            *captured.lock().unwrap() = Some(fd);
            true
        }).unwrap();
        assert_eq!(*observed.lock().unwrap(), Some(42));
    }

    #[test]
    fn failed_protection_fails_closed() {
        let result = ensure_socket_protected_with(42, |_| false);
        assert_eq!(result.unwrap_err().kind(), ErrorKind::PermissionDenied);
    }

    #[test]
    fn invalid_descriptor_is_rejected_without_callback() {
        let called = Arc::new(Mutex::new(false));
        let captured = Arc::clone(&called);
        let result = ensure_socket_protected_with(-1, move |_| {
            *captured.lock().unwrap() = true;
            true
        });
        assert_eq!(result.unwrap_err().kind(), ErrorKind::PermissionDenied);
        assert!(!*called.lock().unwrap());
    }
}


#[cfg(test)]
mod proxy_connection_timeout_tests {
    use super::*;
    use socks5_impl::protocol::Version;
    use std::time::Instant;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn stalled_upstream_handshake_times_out_and_releases_connection() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy_addr = listener.local_addr().unwrap();
        let server_task = tokio::spawn(async move {
            let (_stream, _) = listener.accept().await.unwrap();
            // Keep the accepted socket alive but never answer the SOCKS5 greeting.
            tokio::time::sleep(Duration::from_secs(1)).await;
        });

        let manager = SocksProxyManager::new(proxy_addr, Version::V5, None);
        let handler = manager
            .new_proxy_handler(
                SessionInfo::new(
                    "127.0.0.1:12345".parse().unwrap(),
                    "192.0.2.1:443".parse().unwrap(),
                    IpProtocol::Tcp,
                ),
                None,
                false,
            )
            .await
            .unwrap();

        let started = Instant::now();
        let error = open_proxy_connection_with_timeouts(
            &None,
            handler,
            Duration::from_secs(1),
            Duration::from_millis(50),
        )
        .await
        .unwrap_err();

        assert!(error.to_string().contains("handshake timed out"));
        assert!(started.elapsed() < Duration::from_millis(500));
        server_task.abort();
    }
}


#[cfg(test)]
mod virtual_dns_tcp_fallback_tests {
    use super::*;
    use hickory_proto::{
        op::{Message, MessageType, OpCode, Query},
        rr::{Name, RecordType},
    };
    use std::str::FromStr;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    #[tokio::test]
    async fn forwards_non_address_dns_query_using_tcp_length_prefix() {
        let mut message = Message::new(0x1234, MessageType::Query, OpCode::Query);
        message.add_query(Query::query(
            Name::from_str("service.example.").unwrap(),
            RecordType::TXT,
        ));
        let query = message.to_vec().unwrap();
        let expected_query = query.clone();

        let mut expected_response = query.clone();
        expected_response[2] |= 0x80;

        let (mut client, mut resolver) = tokio::io::duplex(4096);
        let resolver_task = tokio::spawn(async move {
            let mut prefix = [0_u8; 2];
            resolver.read_exact(&mut prefix).await.unwrap();
            let query_len = u16::from_be_bytes(prefix) as usize;
            let mut received_query = vec![0_u8; query_len];
            resolver.read_exact(&mut received_query).await.unwrap();
            assert_eq!(received_query, expected_query);

            let response_len = u16::try_from(expected_response.len()).unwrap();
            resolver.write_all(&response_len.to_be_bytes()).await.unwrap();
            resolver.write_all(&expected_response).await.unwrap();
        });

        let response = forward_dns_query_over_tcp(&mut client, &query, true).await.unwrap();
        assert_eq!(response.get(0..2), query.get(0..2));
        assert_ne!(response[2] & 0x80, 0);
        resolver_task.await.unwrap();
    }
}
