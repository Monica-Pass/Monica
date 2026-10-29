//! Bind only LAN sockets to the caller's Wi-Fi network when its subnet matches.
//! Android may otherwise send traffic over cellular when a hotspot has no internet.
use super::*;
use socket2::Socket;

pub(super) type Bindings = HashMap<Ipv4Addr, u64>;

pub(super) fn read(value: &Value) -> Result<Bindings> {
    let bindings: Bindings =
        serde_json::from_value(value.get("network_handles").cloned().unwrap_or(json!({})))
            .map_err(|_| "invalid_request")?;
    if bindings.len() > MAX_INVITATION_ADDRESSES
        || bindings.keys().any(|ip| !local_ip(IpAddr::V4(*ip)))
    {
        return Err("local_network_only");
    }
    Ok(bindings)
}

pub(super) fn handle(bindings: &Bindings, target: Ipv4Addr) -> u64 {
    if target.is_loopback() || bindings.is_empty() {
        return 0;
    }
    if let Some(handle) = bindings.get(&target) {
        return *handle;
    }
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .find_map(|interface| {
            let if_addrs::IfAddr::V4(address) = interface.addr else {
                return None;
            };
            let handle = bindings.get(&address.ip)?;
            let mask = u32::from(address.netmask);
            (u32::from(address.ip) & mask == u32::from(target) & mask).then_some(*handle)
        })
        .unwrap_or(0)
}

#[cfg(target_os = "android")]
pub(super) fn bind(socket: &Socket, handle: u64) -> Result<()> {
    use std::os::fd::AsRawFd;
    #[link(name = "android")]
    extern "C" {
        fn android_setsocknetwork(network: u64, fd: i32) -> i32;
    }
    if handle != 0 && unsafe { android_setsocknetwork(handle, socket.as_raw_fd()) } != 0 {
        return Err("discovery_unavailable");
    }
    Ok(())
}

#[cfg(not(target_os = "android"))]
pub(super) fn bind(_socket: &Socket, _handle: u64) -> Result<()> {
    Ok(())
}

pub(super) fn connect(address: &SocketAddr, bindings: &Bindings) -> Result<std::net::TcpStream> {
    let socket = Socket::new(
        socket2::Domain::IPV4,
        socket2::Type::STREAM,
        Some(socket2::Protocol::TCP),
    )
    .map_err(|_| "connection_failed")?;
    let IpAddr::V4(ip) = address.ip() else {
        return Err("local_network_only");
    };
    bind(&socket, handle(bindings, ip))?;
    socket
        .connect_timeout(&(*address).into(), Duration::from_secs(2))
        .map_err(|_| "connection_failed")?;
    Ok(socket.into())
}
