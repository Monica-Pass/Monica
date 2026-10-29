//! Local discovery is shared by all pairing listeners in this process.
//! UDP replies are hints only; the subsequent Noise connection authenticates the device.
use super::*;
use socket2::{Domain, Protocol, Socket, Type};
use std::net::UdpSocket;
use std::time::Instant;

const GROUP: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 168);
const PORT: u16 = 53318;
const PROBE: &[u8] = b"monica-lan-discover/1";

#[derive(Default)]
struct Registry {
    next: u64,
    offers: HashMap<u64, (Offer, bool, network::Bindings)>,
    running: bool,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

pub(super) struct Registration(Option<u64>);
impl Drop for Registration {
    fn drop(&mut self) {
        if let Some(id) = self.0 {
            if let Ok(mut state) = registry().lock() {
                state.offers.remove(&id);
            }
        }
    }
}
impl Registration {
    pub(super) fn available(&self) -> bool {
        self.0.is_some()
    }
}

fn response_socket() -> Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
        .map_err(|_| "discovery_unavailable")?;
    socket
        .set_reuse_address(true)
        .map_err(|_| "discovery_unavailable")?;
    socket
        .bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT)).into())
        .map_err(|_| "discovery_unavailable")?;
    socket
        .set_nonblocking(true)
        .map_err(|_| "discovery_unavailable")?;
    for address in addresses() {
        // Failure to join one interface does not disable other interfaces or unicast.
        let _ = socket.join_multicast_v4(&GROUP, &address);
    }
    let _ = socket.join_multicast_v4(&GROUP, &Ipv4Addr::UNSPECIFIED);
    Ok(socket.into())
}

pub(super) fn register(
    offer: Offer,
    follow_interfaces: bool,
    bindings: network::Bindings,
) -> Registration {
    let Ok(mut state) = registry().lock() else {
        return Registration(None);
    };
    let socket = if !state.running {
        match response_socket() {
            Ok(socket) => Some(socket),
            Err(_) => return Registration(None),
        }
    } else {
        None
    };
    state.next += 1;
    let id = state.next;
    state
        .offers
        .insert(id, (offer, follow_interfaces, bindings));
    if let Some(socket) = socket {
        state.running = true;
        std::thread::spawn(move || respond(socket));
    }
    Registration(Some(id))
}

fn respond(socket: UdpSocket) {
    let mut buffer = [0; 128];
    let mut recent: HashMap<SocketAddr, Instant> = HashMap::new();
    let mut joined: std::collections::HashSet<_> = addresses().into_iter().collect();
    let mut refreshed = Instant::now();
    loop {
        {
            let Ok(mut state) = registry().lock() else {
                return;
            };
            if state.offers.is_empty() {
                // Close before releasing the registry lock so a new listener can safely bind.
                drop(socket);
                state.running = false;
                return;
            }
        }
        if refreshed.elapsed() >= Duration::from_secs(2) {
            let current = addresses();
            for previous in joined.clone() {
                if !current.contains(&previous) {
                    let _ = socket.leave_multicast_v4(&GROUP, &previous);
                    joined.remove(&previous);
                }
            }
            for address in &current {
                if !joined.contains(address) && socket.join_multicast_v4(&GROUP, address).is_ok() {
                    joined.insert(*address);
                }
            }
            if let Ok(mut state) = registry().lock() {
                for (offer, follow_interfaces, _) in state.offers.values_mut() {
                    if *follow_interfaces {
                        offer.addresses = current.clone();
                    }
                }
            }
            refreshed = Instant::now();
        }
        match socket.recv_from(&mut buffer) {
            Ok((length, peer)) if &buffer[..length] == PROBE && local_ip(peer.ip()) => {
                let now = Instant::now();
                if recent
                    .get(&peer)
                    .is_some_and(|seen| now.duration_since(*seen) < Duration::from_millis(100))
                {
                    continue;
                }
                if recent.len() >= 64 {
                    recent.retain(|_, seen| now.duration_since(*seen) < Duration::from_secs(1));
                }
                if recent.len() < 64 {
                    recent.insert(peer, now);
                }
                let Ok(state) = registry().lock() else { return };
                // A unicast datagram reaches one UDP socket. Reply with every active offer,
                // rather than letting several per-peer sockets compete for it.
                for (offer, _, bindings) in state.offers.values() {
                    if let Ok(message) = serde_json::to_vec(offer) {
                        let handle = match peer.ip() {
                            IpAddr::V4(ip) => network::handle(bindings, ip),
                            _ => 0,
                        };
                        if handle == 0 {
                            let _ = socket.send_to(&message, peer);
                        } else if let Ok(reply) = probe_socket(handle) {
                            let _ = reply.send_to(&message, &peer.into());
                        }
                    }
                }
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(15))
            }
            Err(_) => std::thread::sleep(Duration::from_millis(30)),
        }
    }
}

fn probe_socket(network_handle: u64) -> Result<Socket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
        .map_err(|_| "discovery_unavailable")?;
    socket
        .bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)).into())
        .map_err(|_| "discovery_unavailable")?;
    socket
        .set_nonblocking(true)
        .map_err(|_| "discovery_unavailable")?;
    socket
        .set_multicast_ttl_v4(1)
        .map_err(|_| "discovery_unavailable")?;
    socket
        .set_broadcast(true)
        .map_err(|_| "discovery_unavailable")?;
    network::bind(&socket, network_handle)?;
    Ok(socket)
}

fn broadcast_addresses() -> Vec<Ipv4Addr> {
    let mut addresses: Vec<_> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|interface| match interface.addr {
            if_addrs::IfAddr::V4(address)
                if address.ip.is_private() || address.ip.is_link_local() =>
            {
                address
                    .broadcast
                    .filter(|ip| ip.is_private() || ip.is_link_local())
            }
            _ => None,
        })
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses.truncate(MAX_INVITATION_ADDRESSES);
    addresses
}

pub(super) fn discover(
    duration: Duration,
    mut targets: Vec<Ipv4Addr>,
    multicast: bool,
    broadcast: bool,
    bindings: network::Bindings,
) -> Result<Vec<Offer>> {
    // Hotspots may filter multicast. Probe only actual interface broadcasts,
    // never a guessed subnet or the global 255.255.255.255 broadcast address.
    if broadcast {
        targets.extend(broadcast_addresses());
    }
    targets.sort();
    targets.dedup();
    targets.truncate(64);
    let mut sockets: Vec<UdpSocket> = Vec::new();
    if !targets.is_empty() {
        let mut networks: HashMap<u64, Vec<Ipv4Addr>> = HashMap::new();
        for address in targets {
            networks
                .entry(network::handle(&bindings, address))
                .or_default()
                .push(address);
        }
        for (handle, targets) in networks {
            let socket = probe_socket(handle)?;
            for address in targets {
                let _ = socket.send_to(PROBE, &SocketAddr::from((address, PORT)).into());
            }
            sockets.push(socket.into());
        }
    }
    if multicast {
        // Each outgoing socket stays attached to its interface, including while replies arrive.
        for address in addresses() {
            let Ok(socket) = probe_socket(network::handle(&bindings, address)) else {
                continue;
            };
            if socket.set_multicast_if_v4(&address).is_err() {
                continue;
            }
            if socket
                .send_to(PROBE, &SocketAddr::from((GROUP, PORT)).into())
                .is_ok()
            {
                sockets.push(socket.into());
            }
        }
        if sockets.is_empty() {
            let socket = probe_socket(0)?;
            let _ = socket.send_to(PROBE, &SocketAddr::from((GROUP, PORT)).into());
            sockets.push(socket.into());
        }
    }
    if sockets.is_empty() {
        return Ok(Vec::new());
    }
    let deadline = Instant::now() + duration;
    let mut devices: HashMap<String, Offer> = HashMap::new();
    let mut buffer = [0; 2048];
    while Instant::now() < deadline && devices.len() < 64 {
        for socket in &sockets {
            // Bound work per interface so one chatty peer cannot starve other sockets.
            for _ in 0..64 {
                let Ok((length, peer)) = socket.recv_from(&mut buffer) else {
                    break;
                };
                if !local_ip(peer.ip()) {
                    continue;
                }
                let Ok(mut offer) = serde_json::from_slice::<Offer>(&buffer[..length]) else {
                    continue;
                };
                if offer.protocol != "monica-lan"
                    || !matches!(offer.version, 1 | 2)
                    || offer.port == 0
                    || offer.session.len() > 64
                    || offer.name.chars().count() > 64
                    || offer.name.chars().any(char::is_control)
                    || offer.revision.len() > 256
                    || offer.addresses.len() > MAX_INVITATION_ADDRESSES
                    || offer
                        .addresses
                        .iter()
                        .any(|address| !local_ip(IpAddr::V4(*address)))
                    || offer
                        .device_id
                        .as_deref()
                        .is_some_and(|id| !peering::identifier(id))
                {
                    continue;
                }
                let id = format!(
                    "{}:{}",
                    offer.session,
                    offer.device_id.as_deref().unwrap_or("")
                );
                if let Some(known) = devices.get(&id) {
                    for address in &known.addresses {
                        if offer.addresses.len() < MAX_INVITATION_ADDRESSES
                            && !offer.addresses.contains(address)
                        {
                            offer.addresses.push(*address);
                        }
                    }
                }
                // Prefer a reply's actual source over addresses claimed inside that reply.
                offer.address = Some(peer.ip().to_string());
                if let IpAddr::V4(address) = peer.ip() {
                    offer.addresses.retain(|candidate| *candidate != address);
                    offer.addresses.insert(0, address);
                    offer.addresses.truncate(MAX_INVITATION_ADDRESSES);
                }
                devices.insert(id, offer);
            }
        }
        std::thread::sleep(Duration::from_millis(15));
    }
    let mut devices: Vec<_> = devices.into_values().collect();
    devices.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(devices)
}
