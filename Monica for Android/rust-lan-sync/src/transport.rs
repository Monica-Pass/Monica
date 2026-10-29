use super::*;
use sha2::{Digest, Sha256};
use std::{
    fs::{File, OpenOptions},
    io::{Read, Write},
    net::TcpStream,
    time::Instant,
};

const NOISE: &str = "Noise_NNpsk0_25519_ChaChaPoly_BLAKE2s";
const CHUNK: usize = 60 * 1024;
const MAX_FRAME: usize = 65_535;
const SESSION_TTL: Duration = Duration::from_secs(600);
const IO_TIMEOUT: Duration = Duration::from_secs(30);

pub(super) fn serve(
    listener: TcpListener,
    key: Zeroizing<[u8; 32]>,
    _discovery: discovery::Registration,
    prepared: Prepared,
    job: &Job,
) -> Result<()> {
    let deadline = Instant::now() + SESSION_TTL;
    let mut failures = 0;
    loop {
        job.check()?;
        if Instant::now() >= deadline {
            return Err("pairing_expired");
        }
        match listener.accept() {
            Ok((stream, peer)) if local_ip(peer.ip()) => {
                job.update(|p| p.phase = "authenticating");
                match Channel::handshake(stream, &key, false, job, prepared.protocol) {
                    Ok(mut channel) => {
                        job.update(|p| p.remote_address = Some(peer.ip().to_string()));
                        return exchange(&mut channel, prepared, job, true);
                    }
                    Err("cancelled") => return Err("cancelled"),
                    Err(_) => {
                        failures += 1;
                        if failures >= 8 {
                            return Err("pairing_rejected");
                        }
                        job.update(|p| p.phase = "waiting");
                    }
                }
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(25))
            }
            Err(_) => return Err("listen_failed"),
        }
    }
}

pub(super) fn connect(
    addresses: Vec<SocketAddr>,
    key: Zeroizing<[u8; 32]>,
    prepared: Prepared,
    job: &Job,
) -> Result<()> {
    let mut last_failure = ("connection_failed", "connecting");
    for address in addresses {
        job.check()?;
        job.update(|p| {
            p.phase = "connecting";
            p.connection_attempt += 1;
        });
        let stream = match network::connect(&address, &prepared.network_bindings) {
            Ok(stream) => stream,
            Err(_) => continue,
        };
        job.update(|p| p.phase = "authenticating");
        match Channel::handshake(stream, &key, true, job, prepared.protocol) {
            Ok(mut channel) => {
                job.update(|p| p.remote_address = Some(address.ip().to_string()));
                return exchange(&mut channel, prepared, job, false);
            }
            Err("cancelled") => return Err("cancelled"),
            Err(error) => last_failure = (error, "authenticating"),
        }
    }
    job.update(|p| p.phase = last_failure.1);
    Err(last_failure.0)
}

struct Channel<'a> {
    stream: TcpStream,
    noise: snow::TransportState,
    job: &'a Job,
}

fn read_exact(
    stream: &mut TcpStream,
    mut bytes: &mut [u8],
    job: &Job,
    timeout: Duration,
) -> Result<()> {
    let deadline = Instant::now() + timeout;
    while !bytes.is_empty() {
        job.check()?;
        if Instant::now() >= deadline {
            return Err("transfer_timeout");
        }
        match stream.read(bytes) {
            Ok(0) => return Err("connection_closed"),
            Ok(read) => {
                bytes = &mut bytes[read..];
            }
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock
                        | std::io::ErrorKind::TimedOut
                        | std::io::ErrorKind::Interrupted
                ) => {}
            Err(_) => return Err("connection_failed"),
        }
    }
    Ok(())
}

fn write_all(stream: &mut TcpStream, mut bytes: &[u8], job: &Job) -> Result<()> {
    let deadline = Instant::now() + IO_TIMEOUT;
    while !bytes.is_empty() {
        job.check()?;
        if Instant::now() >= deadline {
            return Err("transfer_timeout");
        }
        match stream.write(bytes) {
            Ok(0) => return Err("connection_closed"),
            Ok(written) => bytes = &bytes[written..],
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock
                        | std::io::ErrorKind::TimedOut
                        | std::io::ErrorKind::Interrupted
                ) => {}
            Err(_) => return Err("connection_failed"),
        }
    }
    Ok(())
}

fn write_frame(stream: &mut TcpStream, bytes: &[u8], job: &Job) -> Result<()> {
    if bytes.len() > MAX_FRAME {
        return Err("invalid_frame");
    }
    write_all(stream, &(bytes.len() as u32).to_be_bytes(), job)?;
    write_all(stream, bytes, job)
}

fn read_frame(stream: &mut TcpStream, job: &Job, timeout: Duration) -> Result<Vec<u8>> {
    let mut length = [0u8; 4];
    read_exact(stream, &mut length, job, timeout)?;
    let length = u32::from_be_bytes(length) as usize;
    if length == 0 || length > MAX_FRAME {
        return Err("invalid_frame");
    }
    let mut bytes = vec![0u8; length];
    read_exact(stream, &mut bytes, job, timeout)?;
    Ok(bytes)
}

impl<'a> Channel<'a> {
    fn handshake(
        mut stream: TcpStream,
        key: &[u8; 32],
        initiator: bool,
        job: &'a Job,
        protocol: u32,
    ) -> Result<Self> {
        stream
            .set_read_timeout(Some(Duration::from_millis(250)))
            .map_err(|_| "connection_failed")?;
        stream
            .set_write_timeout(Some(Duration::from_millis(250)))
            .map_err(|_| "connection_failed")?;
        let _ = stream.set_nodelay(true);
        let prologue: &[u8] = if protocol == 2 {
            b"Monica device sync v2"
        } else {
            b"Monica LAN sync v1"
        };
        let builder = snow::Builder::new(NOISE.parse().map_err(|_| "unavailable")?)
            .prologue(prologue)
            .psk(0, key);
        let mut noise = if initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .map_err(|_| "unavailable")?;
        let mut buffer = [0u8; 1024];
        for send in [initiator, !initiator] {
            job.check()?;
            if send {
                let length = noise
                    .write_message(&[], &mut buffer)
                    .map_err(|_| "pairing_rejected")?;
                write_frame(&mut stream, &buffer[..length], job)?;
            } else {
                let message = read_frame(&mut stream, job, Duration::from_secs(3))?;
                noise
                    .read_message(&message, &mut buffer)
                    .map_err(|_| "pairing_rejected")?;
            }
        }
        Ok(Self {
            stream,
            noise: noise
                .into_transport_mode()
                .map_err(|_| "pairing_rejected")?,
            job,
        })
    }

    fn send(&mut self, bytes: &[u8]) -> Result<()> {
        self.job.check()?;
        if bytes.len() > CHUNK {
            return Err("invalid_frame");
        }
        let mut buffer = vec![0u8; bytes.len() + 16];
        let length = self
            .noise
            .write_message(bytes, &mut buffer)
            .map_err(|_| "authentication_failed")?;
        write_frame(&mut self.stream, &buffer[..length], self.job)
    }

    fn receive(&mut self) -> Result<Vec<u8>> {
        self.receive_with_timeout(IO_TIMEOUT)
    }

    fn receive_with_timeout(&mut self, timeout: Duration) -> Result<Vec<u8>> {
        let bytes = read_frame(&mut self.stream, self.job, timeout)?;
        let mut output = vec![0u8; bytes.len()];
        let length = self
            .noise
            .read_message(&bytes, &mut output)
            .map_err(|_| "authentication_failed")?;
        output.truncate(length);
        Ok(output)
    }
}

#[derive(Serialize, Deserialize)]
struct Artifact {
    name: String,
    size: u64,
    sha256: String,
}
#[derive(Serialize, Deserialize)]
struct Manifest {
    version: u32,
    vault_id: String,
    files: Vec<Artifact>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    device: Option<peering::DeviceHello>,
}

fn hash_file(path: &Path, job: &Job) -> Result<String> {
    let mut file = File::open(path).map_err(|_| "artifact_unavailable")?;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; CHUNK];
    loop {
        job.check()?;
        let length = file.read(&mut buffer).map_err(|_| "storage_read_failed")?;
        if length == 0 {
            break;
        }
        hash.update(&buffer[..length]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn manifest(prepared: &Prepared, job: &Job) -> Result<Manifest> {
    let mut files = Vec::new();
    let may_send = prepared.device.as_ref().and_then(|device| device.sync_mode)
        != Some(peering::SyncMode::ReceiveOnly);
    if let Some(directory) = prepared.outgoing.as_ref().filter(|_| may_send) {
        for name in FILE_NAMES {
            let path = directory.join(name);
            if path.is_file() {
                files.push(Artifact {
                    name: name.into(),
                    size: path.metadata().map_err(|_| "artifact_unavailable")?.len(),
                    sha256: hash_file(&path, job)?,
                });
            }
        }
    }
    Ok(Manifest {
        version: prepared.protocol,
        vault_id: prepared.vault_id.clone(),
        files,
        device: prepared.device.clone(),
    })
}

fn validate_manifest(manifest: &Manifest) -> Result<()> {
    if !matches!(manifest.version, 1 | 2)
        || manifest.vault_id.len() > 128
        || manifest.files.len() > FILE_NAMES.len()
    {
        return Err("invalid_manifest");
    }
    if manifest.version == 2 {
        manifest
            .device
            .as_ref()
            .ok_or("invalid_device")?
            .validate()?;
    }
    let mut names = std::collections::HashSet::new();
    for file in &manifest.files {
        if !FILE_NAMES.contains(&file.name.as_str())
            || !names.insert(&file.name)
            || file.size == 0
            || file.size > MAX_FILE
            || file.sha256.len() != 64
            || !file.sha256.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err("invalid_manifest");
        }
    }
    if !manifest.files.is_empty()
        && (manifest.vault_id.is_empty()
            || !manifest.files.iter().any(|f| f.name == "changes.mdbxsync"))
    {
        return Err("invalid_manifest");
    }
    Ok(())
}

fn exchange(channel: &mut Channel<'_>, prepared: Prepared, job: &Job, host: bool) -> Result<()> {
    job.update(|p| p.phase = "checking");
    let mut local = manifest(&prepared, job)?;
    validate_manifest(&local)?;
    let local_json = serde_json::to_vec(&local).map_err(|_| "invalid_manifest")?;
    let remote_bytes = if host {
        channel.send(&local_json)?;
        channel.receive()?
    } else {
        let remote = channel.receive()?;
        channel.send(&local_json)?;
        remote
    };
    if remote_bytes.len() > MAX_REQUEST_BYTES {
        return Err("invalid_manifest");
    }
    let mut remote: Manifest =
        serde_json::from_slice(&remote_bytes).map_err(|_| "invalid_manifest")?;
    validate_manifest(&remote)?;
    if local.version != remote.version {
        return Err("unsupported_protocol");
    }
    let flow = if prepared.protocol == 2 {
        peering::SyncFlow::negotiate(
            local.device.as_ref().and_then(|device| device.sync_mode),
            remote.device.as_ref().and_then(|device| device.sync_mode),
        )?
    } else {
        peering::SyncFlow {
            send: true,
            receive: true,
        }
    };
    let different = !local.vault_id.is_empty()
        && !remote.vault_id.is_empty()
        && local.vault_id != remote.vault_id;
    let needs_bootstrap = local.vault_id.is_empty()
        || (different && prepared.protocol == 2 && remote.vault_id < local.vault_id);
    let remote_needs_bootstrap = remote.vault_id.is_empty()
        || (different && prepared.protocol == 2 && local.vault_id < remote.vault_id);
    if (!flow.send || !flow.receive) && (needs_bootstrap || remote_needs_bootstrap) {
        return Err("sync_direction_unsupported");
    }
    if different && prepared.protocol == 1 {
        return Err("different_vault");
    }
    if needs_bootstrap && !remote.files.iter().any(|f| f.name == "vault.mdbx") {
        return Err("bootstrap_required");
    }
    if !needs_bootstrap && !remote_needs_bootstrap {
        local.files.retain(|file| file.name != "vault.mdbx");
        remote.files.retain(|file| file.name != "vault.mdbx");
    }
    if prepared.protocol == 2 {
        let device = remote.device.clone().ok_or("invalid_device")?;
        if prepared
            .device
            .as_ref()
            .is_some_and(|local| local.vault_content_version != device.vault_content_version)
        {
            return Err("unsupported_vault_content");
        }
        if Some(device.content_type.as_str())
            != prepared
                .device
                .as_ref()
                .map(|device| device.content_type.as_str())
        {
            return Err("different_content_type");
        }
        if Some(device.device_id.as_str())
            == prepared
                .device
                .as_ref()
                .map(|device| device.device_id.as_str())
            || prepared
                .expected_device
                .as_deref()
                .is_some_and(|expected| expected != device.device_id)
        {
            return Err("different_device");
        }
        let mut outgoing = prepared.secrets.clone();
        if !flow.send {
            outgoing.content_key.clear();
        }
        if !remote_needs_bootstrap {
            outgoing.vault_password.clear();
        }
        if remote_needs_bootstrap && outgoing.vault_password.is_empty() {
            return Err("credential_required");
        }
        let bytes = Zeroizing::new(serde_json::to_vec(&outgoing).map_err(|_| "invalid_device")?);
        let incoming = Zeroizing::new(if host {
            channel.send(&bytes)?;
            channel.receive()?
        } else {
            let incoming = channel.receive()?;
            channel.send(&bytes)?;
            incoming
        });
        if incoming.len() > 8192 {
            return Err("invalid_device");
        }
        let received: peering::PairSecrets =
            serde_json::from_slice(&incoming).map_err(|_| "invalid_device")?;
        if flow.receive && (device.content_type == "passwords" || device.vault_content_version == 2)
        {
            let _ = peering::secret_key(&received.content_key)?;
        }
        if received.vault_password.len() > 4096
            || (needs_bootstrap && received.vault_password.is_empty())
        {
            return Err("credential_required");
        }
        let mut pairing = if host {
            prepared.secrets.clone()
        } else {
            received.clone()
        };
        if !pairing.trusted_key.is_empty() {
            let _ = peering::secret_key(&pairing.trusted_key)?;
            if !peering::identifier(&pairing.pair_id) {
                return Err("invalid_device");
            }
        }
        pairing.vault_password = if needs_bootstrap {
            received.vault_password.clone()
        } else {
            String::new()
        };
        pairing.content_key = if flow.receive {
            received.content_key.clone()
        } else {
            String::new()
        };
        job.update(|progress| {
            progress.peer = Some(peering::PeerInfo {
                device,
                pairing,
                vault_id: remote.vault_id.clone(),
                needs_bootstrap,
                send_content: flow.send,
                receive_content: flow.receive,
            })
        });
        if needs_bootstrap {
            local.files.clear();
        }
        if remote_needs_bootstrap {
            remote.files.clear();
        }
    }
    if !flow.send {
        local.files.clear();
    }
    if !flow.receive {
        remote.files.clear();
    }
    job.update(|p| {
        p.total = local
            .files
            .iter()
            .chain(&remote.files)
            .map(|f| f.size)
            .sum();
        p.phase = "transferring";
    });
    if host {
        send_files(channel, &prepared, &local, job)?;
        receive_files(channel, &prepared, &remote, job)?;
    } else {
        receive_files(channel, &prepared, &remote, job)?;
        send_files(channel, &prepared, &local, job)?;
    }
    // Receipt means authenticated transport completion, never database merge completion.
    channel.send(b"received")?;
    if channel.receive()? != b"received" {
        return Err("invalid_receipt");
    }
    job.update(|p| {
        p.phase = "received";
        p.received_directory = Some(prepared.incoming.clone());
    });
    let deadline = Instant::now() + SESSION_TTL;
    while job.applied.load(Ordering::Acquire) == 0 {
        job.check()?;
        if Instant::now() >= deadline {
            return Err("merge_timeout");
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    let applied = job.applied.load(Ordering::Acquire) == 1;
    job.update(|p| p.phase = "confirming");
    channel.send(if applied { b"applied" } else { b"rejected" })?;
    // The other device may still be applying a large vault after ours has finished.
    let receipt = channel.receive_with_timeout(SESSION_TTL)?;
    if !applied {
        return Err("local_merge_failed");
    }
    if receipt != b"applied" {
        return Err("peer_merge_failed");
    }
    Ok(())
}

fn send_files(
    channel: &mut Channel<'_>,
    prepared: &Prepared,
    manifest: &Manifest,
    job: &Job,
) -> Result<()> {
    for artifact in &manifest.files {
        let mut file = File::open(
            prepared
                .outgoing
                .as_ref()
                .ok_or("artifact_unavailable")?
                .join(&artifact.name),
        )
        .map_err(|_| "artifact_unavailable")?;
        let mut remaining = artifact.size;
        let mut buffer = [0u8; CHUNK];
        let mut hash = Sha256::new();
        while remaining > 0 {
            job.check()?;
            let maximum = remaining.min(CHUNK as u64) as usize;
            let length = file
                .read(&mut buffer[..maximum])
                .map_err(|_| "storage_read_failed")?;
            if length == 0 {
                return Err("artifact_changed");
            }
            hash.update(&buffer[..length]);
            channel.send(&buffer[..length])?;
            remaining -= length as u64;
            job.update(|p| p.transferred += length as u64);
        }
        if format!("{:x}", hash.finalize()) != artifact.sha256
            || file
                .read(&mut buffer[..1])
                .map_err(|_| "storage_read_failed")?
                != 0
        {
            return Err("artifact_changed");
        }
        if channel.receive()? != b"file_received" {
            return Err("invalid_receipt");
        }
    }
    Ok(())
}

fn receive_files(
    channel: &mut Channel<'_>,
    prepared: &Prepared,
    manifest: &Manifest,
    job: &Job,
) -> Result<()> {
    for artifact in &manifest.files {
        let temporary = prepared.incoming.join(format!("{}.part", artifact.name));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| "storage_create_failed")?;
        let mut remaining = artifact.size;
        let mut hash = Sha256::new();
        while remaining > 0 {
            let bytes = channel.receive()?;
            if bytes.is_empty() || bytes.len() as u64 > remaining {
                return Err("invalid_frame");
            }
            hash.update(&bytes);
            file.write_all(&bytes).map_err(|_| "storage_write_failed")?;
            remaining -= bytes.len() as u64;
            job.update(|p| p.transferred += bytes.len() as u64);
        }
        if format!("{:x}", hash.finalize()) != artifact.sha256 {
            return Err("artifact_changed");
        }
        file.sync_all().map_err(|_| "storage_flush_failed")?;
        drop(file);
        let destination = prepared.incoming.join(&artifact.name);
        crate::storage::publish_new(&temporary, &destination)
            .map_err(|_| "storage_publish_failed")?;
        std::fs::remove_file(&temporary).map_err(|_| "storage_remove_failed")?;
        channel.send(b"file_received")?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_traversal_duplicates_and_oversized_files() {
        for name in [
            "../vault.mdbx",
            "/vault.mdbx",
            "C:\\vault.mdbx",
            "changes.mdbxsync:stream",
        ] {
            assert!(validate_manifest(&Manifest {
                version: 1,
                device: None,
                vault_id: "test".into(),
                files: vec![Artifact {
                    name: name.into(),
                    size: 1,
                    sha256: "0".repeat(64)
                }]
            })
            .is_err());
        }
        let artifact = || Artifact {
            name: "changes.mdbxsync".into(),
            size: 1,
            sha256: "0".repeat(64),
        };
        assert!(validate_manifest(&Manifest {
            version: 1,
            device: None,
            vault_id: "test".into(),
            files: vec![artifact(), artifact()]
        })
        .is_err());
        let mut file = artifact();
        file.size = MAX_FILE + 1;
        assert!(validate_manifest(&Manifest {
            version: 1,
            device: None,
            vault_id: "test".into(),
            files: vec![file]
        })
        .is_err());
    }
}
