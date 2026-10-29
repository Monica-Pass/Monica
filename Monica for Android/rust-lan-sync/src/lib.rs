//! Transport only: MDBX owns encryption at rest, object identity, merges and tombstones.
//! An invitation is a one-session, 256-bit secret and must never be advertised or logged.

mod assets;
mod discovery;
mod network;
mod peering;
mod replica;
mod storage;
mod transport;

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering},
        Arc, Mutex, OnceLock,
    },
    time::Duration,
};
use zeroize::Zeroizing;

/// Shared by the JSON dispatcher and native ABI wrappers before reading requests.
pub const MAX_REQUEST_BYTES: usize = 8 * 1024 * 1024;
const MAX_JOBS: usize = 8;
const MAX_FILE: u64 = 512 * 1024 * 1024;
const MAX_INVITATION_ADDRESSES: usize = 8;
const FILE_NAMES: [&str; 3] = ["vault.mdbx", "changes.mdbxsync", "assets.monica"];

type Result<T> = std::result::Result<T, &'static str>;

#[derive(Clone, Serialize)]
struct Progress {
    phase: &'static str,
    transferred: u64,
    total: u64,
    error: Option<&'static str>,
    failed_at: Option<&'static str>,
    connection_attempt: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    remote_address: Option<String>,
    received_directory: Option<PathBuf>,
    #[serde(skip_serializing_if = "Option::is_none")]
    peer: Option<peering::PeerInfo>,
}

struct Job {
    cancel: AtomicBool,
    applied: AtomicU8,
    progress: Mutex<Progress>,
}

impl Job {
    fn new(phase: &'static str) -> Arc<Self> {
        Arc::new(Self {
            cancel: AtomicBool::new(false),
            applied: AtomicU8::new(0),
            progress: Mutex::new(Progress {
                phase,
                transferred: 0,
                total: 0,
                error: None,
                failed_at: None,
                connection_attempt: 0,
                remote_address: None,
                received_directory: None,
                peer: None,
            }),
        })
    }

    fn update(&self, update: impl FnOnce(&mut Progress)) {
        if let Ok(mut progress) = self.progress.lock() {
            update(&mut progress);
        }
    }

    fn check(&self) -> Result<()> {
        if self.cancel.load(Ordering::Acquire) {
            Err("cancelled")
        } else {
            Ok(())
        }
    }
}

static JOBS: OnceLock<Mutex<HashMap<u64, Arc<Job>>>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);

fn jobs() -> &'static Mutex<HashMap<u64, Arc<Job>>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn register(job: Arc<Job>) -> Result<u64> {
    let mut jobs = jobs().lock().map_err(|_| "unavailable")?;
    if jobs.len() >= MAX_JOBS {
        return Err("too_many_sessions");
    }
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    jobs.insert(id, job);
    Ok(id)
}

fn job(id: u64) -> Result<Arc<Job>> {
    jobs()
        .lock()
        .map_err(|_| "unavailable")?
        .get(&id)
        .cloned()
        .ok_or("session_closed")
}

fn string<'a>(value: &'a Value, key: &str) -> Result<&'a str> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or("invalid_request")
}

fn local_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => ip.is_private() || ip.is_link_local() || ip.is_loopback(),
        IpAddr::V6(_) => false,
    }
}

fn addresses() -> Vec<Ipv4Addr> {
    let mut addresses: Vec<_> = if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|interface| match interface.ip() {
            IpAddr::V4(ip) if local_ip(IpAddr::V4(ip)) && !ip.is_loopback() => Some(ip),
            _ => None,
        })
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses.truncate(MAX_INVITATION_ADDRESSES);
    addresses
}

fn invitation_links(protocol: u32, addresses: &[Ipv4Addr], port: u16, code: &str) -> Vec<String> {
    let mut links = Vec::new();
    if protocol == 2 && addresses.len() > 1 {
        let joined = addresses
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join(",");
        links.push(format!("monica-lan:2:{joined}:{port}:{code}"));
    }
    links.extend(
        addresses
            .iter()
            .map(|address| format!("monica-lan:{protocol}:{address}:{port}:{code}")),
    );
    links
}

#[derive(Clone, Serialize, Deserialize)]
struct Offer {
    protocol: String,
    version: u32,
    session: String,
    name: String,
    port: u16,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    address: Option<String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    addresses: Vec<Ipv4Addr>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    device_id: Option<String>,
    #[serde(default)]
    trusted: bool,
    #[serde(default)]
    revision: String,
}

struct Prepared {
    vault_id: String,
    outgoing: Option<PathBuf>,
    incoming: PathBuf,
    protocol: u32,
    device: Option<peering::DeviceHello>,
    secrets: peering::PairSecrets,
    expected_device: Option<String>,
    network_bindings: network::Bindings,
}

fn prepare(value: &Value) -> Result<Prepared> {
    let network_bindings = network::read(value)?;
    let protocol = value.get("protocol").and_then(Value::as_u64).unwrap_or(1);
    if !matches!(protocol, 1 | 2) {
        return Err("unsupported_protocol");
    }
    let device = if protocol == 2 {
        let device: peering::DeviceHello =
            serde_json::from_value(value.get("device").cloned().ok_or("invalid_device")?)
                .map_err(|_| "invalid_device")?;
        device.validate()?;
        Some(device)
    } else {
        None
    };
    let mut secrets = peering::PairSecrets::default();
    if protocol == 2 {
        secrets.vault_password = value
            .get("vault_password")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        secrets.content_key = value
            .get("content_key")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        if !secrets.content_key.is_empty() {
            let _ = peering::secret_key(&secrets.content_key)?;
        }
        if secrets.vault_password.len() > 4096 {
            return Err("invalid_request");
        }
    }
    let expected_device = value
        .get("expected_device_id")
        .and_then(Value::as_str)
        .map(str::to_owned);
    if expected_device
        .as_deref()
        .is_some_and(|id| !peering::identifier(id))
    {
        return Err("invalid_device");
    }
    let vault_id = value
        .get("vault_id")
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_owned();
    if vault_id.len() > 128 {
        return Err("invalid_vault");
    }
    let outgoing = value
        .get("outgoing_directory")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(PathBuf::from);
    if let Some(directory) = &outgoing {
        if vault_id.is_empty() || !directory.is_dir() {
            return Err("invalid_artifacts");
        }
        for name in FILE_NAMES {
            let path = directory.join(name);
            if path.exists() {
                let metadata = path
                    .symlink_metadata()
                    .map_err(|_| "artifact_unavailable")?;
                if !metadata.is_file()
                    || metadata.file_type().is_symlink()
                    || metadata.len() > MAX_FILE
                {
                    return Err("invalid_artifacts");
                }
            }
        }
        if !directory.join("changes.mdbxsync").is_file() {
            return Err("missing_sync_bundle");
        }
    }
    let incoming = PathBuf::from(string(value, "incoming_directory")?);
    // The caller creates an operation-owned empty directory. Never overwrite an existing transfer.
    std::fs::create_dir_all(&incoming).map_err(|_| "storage_directory_failed")?;
    if std::fs::read_dir(&incoming)
        .map_err(|_| "storage_read_failed")?
        .next()
        .is_some()
    {
        return Err("destination_not_empty");
    }
    if incoming
        .symlink_metadata()
        .map_err(|_| "storage_read_failed")?
        .file_type()
        .is_symlink()
    {
        return Err("invalid_destination");
    }
    Ok(Prepared {
        vault_id,
        outgoing,
        incoming,
        protocol: protocol as u32,
        device,
        secrets,
        expected_device,
        network_bindings,
    })
}

fn complete(job: &Job, result: Result<()>, incoming: &Path) {
    match result {
        Ok(()) => job.update(|p| {
            p.phase = "completed";
            p.received_directory = Some(incoming.to_owned());
        }),
        Err(error) => {
            // Only protocol-owned leaf files are removed; the caller owns the directory lifecycle.
            for name in FILE_NAMES {
                let _ = std::fs::remove_file(incoming.join(name));
                let _ = std::fs::remove_file(incoming.join(format!("{name}.part")));
            }
            job.update(|p| {
                p.failed_at = Some(p.phase);
                p.phase = if error == "cancelled" {
                    "cancelled"
                } else {
                    "failed"
                };
                p.error = Some(error);
                p.peer = None;
            });
        }
    }
}

fn start(value: &Value) -> Result<Value> {
    let mut prepared = prepare(value)?;
    // Listening is a connection role, independent of the negotiated data direction.
    // A receive-only v2 peer intentionally has no local export or content key.
    if prepared.outgoing.is_none()
        && prepared.device.as_ref().and_then(|device| device.sync_mode)
            != Some(peering::SyncMode::ReceiveOnly)
    {
        return Err("missing_sync_bundle");
    }
    let listen_address = value
        .get("listen_address")
        .and_then(Value::as_str)
        .unwrap_or("0.0.0.0")
        .parse::<Ipv4Addr>()
        .map_err(|_| "invalid_request")?;
    if !listen_address.is_unspecified() && !local_ip(IpAddr::V4(listen_address)) {
        return Err("local_network_only");
    }
    let listener = TcpListener::bind((listen_address, 0)).map_err(|_| "listen_failed")?;
    listener
        .set_nonblocking(true)
        .map_err(|_| "listen_failed")?;
    let port = listener.local_addr().map_err(|_| "listen_failed")?.port();
    let trusted_key = value.get("trusted_key").and_then(Value::as_str);
    if trusted_key.is_some() && (prepared.protocol != 2 || prepared.expected_device.is_none()) {
        return Err("invalid_device");
    }
    let key = if let Some(encoded) = trusted_key {
        peering::secret_key(encoded)?
    } else {
        let mut key = Zeroizing::new([0u8; 32]);
        rand::rngs::OsRng.fill_bytes(key.as_mut());
        key
    };
    if prepared.protocol == 2 && trusted_key.is_none() {
        prepared.secrets.pair_id = peering::random_id();
        prepared.secrets.trusted_key = peering::random_key();
        if let Some(device) = &mut prepared.device {
            device.pair_id = prepared.secrets.pair_id.clone();
        }
    }
    let code = URL_SAFE_NO_PAD.encode(key.as_ref());
    let mut session = [0u8; 16];
    rand::rngs::OsRng.fill_bytes(&mut session);
    let local_addresses = if listen_address.is_unspecified() {
        addresses()
    } else {
        vec![listen_address]
    };
    let offer = Offer {
        protocol: "monica-lan".into(),
        version: prepared.protocol,
        session: prepared
            .device
            .as_ref()
            .filter(|d| !d.pair_id.is_empty())
            .map(|d| d.pair_id.clone())
            .unwrap_or_else(|| URL_SAFE_NO_PAD.encode(session)),
        name: value
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("Monica")
            .chars()
            .filter(|c| !c.is_control())
            .take(64)
            .collect(),
        port,
        address: None,
        addresses: local_addresses.clone(),
        device_id: prepared
            .device
            .as_ref()
            .map(|device| device.device_id.clone()),
        trusted: trusted_key.is_some(),
        revision: value
            .get("revision")
            .and_then(Value::as_str)
            .unwrap_or("")
            .chars()
            .take(256)
            .collect(),
    };
    let job = Job::new("waiting");
    let handle = register(job.clone())?;
    let invitations = invitation_links(prepared.protocol, &local_addresses, port, &code);
    let discovery = discovery::register(
        offer,
        listen_address.is_unspecified(),
        prepared.network_bindings.clone(),
    );
    let discovery_available = discovery.available();
    std::thread::spawn(move || {
        let incoming = prepared.incoming.clone();
        let result = transport::serve(listener, key, discovery, prepared, &job);
        complete(&job, result, &incoming);
    });
    Ok(
        json!({"handle": handle, "port": port, "code": code, "addresses": local_addresses, "invitations": invitations, "discovery_available":discovery_available}),
    )
}

fn connect(value: &Value) -> Result<Value> {
    let invitation = string(value, "invitation")?.trim();
    if invitation.len() > 512 {
        return Err("invalid_invitation");
    }
    let parts: Vec<_> = invitation.split(':').collect();
    if parts.len() != 5 || parts[0] != "monica-lan" || !matches!(parts[1], "1" | "2") {
        return Err("invalid_invitation");
    }
    let addresses = parts[2]
        .split(',')
        .map(|text| {
            let address: Ipv4Addr = text.parse().map_err(|_| "invalid_invitation")?;
            if address.to_string() != text {
                return Err("invalid_invitation");
            }
            if !local_ip(IpAddr::V4(address)) {
                return Err("local_network_only");
            }
            Ok(address)
        })
        .collect::<Result<Vec<_>>>()?;
    if addresses.is_empty()
        || addresses.len() > MAX_INVITATION_ADDRESSES
        || (parts[1] == "1" && addresses.len() != 1)
    {
        return Err("invalid_invitation");
    }
    if addresses
        .iter()
        .collect::<std::collections::HashSet<_>>()
        .len()
        != addresses.len()
    {
        return Err("invalid_invitation");
    }
    let port: u16 = parts[3].parse().map_err(|_| "invalid_invitation")?;
    if port == 0 {
        return Err("invalid_invitation");
    }
    let decoded = Zeroizing::new(
        URL_SAFE_NO_PAD
            .decode(parts[4])
            .map_err(|_| "invalid_invitation")?,
    );
    let mut key = Zeroizing::new([0u8; 32]);
    if decoded.len() != key.len() {
        return Err("invalid_invitation");
    }
    key.copy_from_slice(&decoded);
    let prepared = prepare(value)?;
    if prepared.protocol.to_string() != parts[1] {
        return Err("unsupported_protocol");
    }
    let job = Job::new("connecting");
    let handle = register(job.clone())?;
    std::thread::spawn(move || {
        let incoming = prepared.incoming.clone();
        let targets = addresses
            .into_iter()
            .map(|address| SocketAddr::from((address, port)))
            .collect();
        let result = transport::connect(targets, key, prepared, &job);
        complete(&job, result, &incoming);
    });
    Ok(json!({"handle": handle}))
}

fn request(value: Value) -> Result<Value> {
    if string(&value, "operation")?.starts_with("asset_") {
        return assets::request(&value);
    }
    if string(&value, "operation")?.starts_with("replica_") {
        return replica::request(&value);
    }
    match string(&value, "operation")? {
        "start" => start(&value),
        "connect" => connect(&value),
        "discover" => {
            let targets: Vec<Ipv4Addr> =
                serde_json::from_value(value.get("targets").cloned().unwrap_or(json!([])))
                    .map_err(|_| "invalid_request")?;
            if targets.len() > 64
                || targets
                    .iter()
                    .any(|address| !local_ip(IpAddr::V4(*address)))
            {
                return Err("local_network_only");
            }
            let multicast = value
                .get("multicast")
                .and_then(Value::as_bool)
                .unwrap_or(true);
            let broadcast = value
                .get("broadcast")
                .and_then(Value::as_bool)
                .unwrap_or(multicast);
            Ok(
                json!({"devices":discovery::discover(Duration::from_secs(2), targets, multicast, broadcast, network::read(&value)?)?}),
            )
        }
        "progress" => {
            let job = job(value
                .get("handle")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?)?;
            let progress = job.progress.lock().map_err(|_| "unavailable")?.clone();
            Ok(json!(progress))
        }
        "acknowledge" => {
            let job = job(value
                .get("handle")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?)?;
            let success = value
                .get("success")
                .and_then(Value::as_bool)
                .ok_or("invalid_request")?;
            if job.progress.lock().map_err(|_| "unavailable")?.phase != "received" {
                return Err("not_ready");
            }
            job.applied
                .compare_exchange(
                    0,
                    if success { 1 } else { 2 },
                    Ordering::AcqRel,
                    Ordering::Acquire,
                )
                .map_err(|_| "already_acknowledged")?;
            Ok(json!({}))
        }
        "cancel" => {
            let handle = value
                .get("handle")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?;
            if let Some(job) = jobs().lock().map_err(|_| "unavailable")?.get(&handle) {
                job.cancel.store(true, Ordering::Release);
            }
            Ok(json!({}))
        }
        "release" => {
            let handle = value
                .get("handle")
                .and_then(Value::as_u64)
                .ok_or("invalid_request")?;
            let mut jobs = jobs().lock().map_err(|_| "unavailable")?;
            if let Some(job) = jobs.get(&handle) {
                let phase = job.progress.lock().map_err(|_| "unavailable")?.phase;
                if !matches!(phase, "completed" | "failed" | "cancelled") {
                    return Err("session_busy");
                }
            }
            jobs.remove(&handle);
            Ok(json!({}))
        }
        _ => Err("invalid_request"),
    }
}

/// Every error is a fixed code. Paths, vault data, invitations and native errors are never echoed.
pub fn request_json(input: &str) -> String {
    let result = std::panic::catch_unwind(|| {
        if input.len() > MAX_REQUEST_BYTES {
            return Err("request_too_large");
        }
        request(serde_json::from_str(input).map_err(|_| "invalid_request")?)
    })
    .unwrap_or(Err("unavailable"));
    match result {
        Ok(data) => json!({"ok": true, "data": data}),
        Err(code) => json!({"ok": false, "error": code}),
    }
    .to_string()
}

#[cfg(test)]
mod tests;
