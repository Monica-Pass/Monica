//! Authenticated, stateless inventories. They describe exact exported versions,
//! not successful physical application; the normal pending/apply receipt remains mandatory.
use super::*;
use std::io::{Read, Seek, SeekFrom, Write};
use zeroize::Zeroize;

// The maximum validated IDs/digests/maps below serialize to less than 48 MiB.
// Match the replica limit rather than refusing a legal large snapshot mid-sync.
pub(crate) const MAX_INVENTORY: usize = MAX_STATE;
const ASSET_MAGIC: &[u8] = b"MONICA-DEVICE-ASSETS-1\0";
const ASSET_HEADER: usize = 73;
const ASSET_CHUNK: usize = 512 * 1024;
static CACHE_PUBLICATION: std::sync::Mutex<()> = std::sync::Mutex::new(());

fn cache_publication(job: &Job) -> Result<std::sync::MutexGuard<'static, ()>> {
    loop {
        job.check()?;
        match CACHE_PUBLICATION.try_lock() {
            Ok(guard) => {
                job.check()?;
                return Ok(guard);
            }
            Err(std::sync::TryLockError::WouldBlock) => {
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            Err(std::sync::TryLockError::Poisoned(_)) => return Err("unavailable"),
        }
    }
}

#[derive(Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Inventory {
    version: u32,
    records: BTreeMap<String, String>,
    aliases: BTreeMap<String, String>,
    assets: BTreeSet<String>,
}

impl Inventory {
    pub(crate) fn empty() -> Self {
        Self {
            version: 1,
            ..Self::default()
        }
    }

    pub(crate) fn parse(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_INVENTORY {
            return Err("replica_limit");
        }
        let inventory: Self = serde_json::from_slice(bytes).map_err(|_| "invalid_inventory")?;
        if inventory.version != 1
            || inventory.records.len() > MAX_RECORDS
            || inventory.aliases.len() > MAX_RECORDS * 2
            || inventory.assets.len() > MAX_RECORDS
            || inventory
                .records
                .iter()
                .any(|(id, digest)| !peering::identifier(id) || !digest_valid(digest))
            || inventory.aliases.iter().any(|(id, target)| {
                !peering::identifier(id) || !peering::identifier(target) || id <= target
            })
            || inventory.assets.iter().any(|id| !digest_valid(id))
        {
            return Err("invalid_inventory");
        }
        Ok(inventory)
    }

    pub(crate) fn bytes(&self) -> Result<Zeroizing<Vec<u8>>> {
        let bytes = Zeroizing::new(serde_json::to_vec(self).map_err(|_| "invalid_inventory")?);
        if bytes.len() > MAX_INVENTORY {
            return Err("replica_limit");
        }
        Ok(bytes)
    }
}

fn digest_valid(value: &str) -> bool {
    value.len() == 64 && value.bytes().all(|c| c.is_ascii_hexdigit())
}

struct SensitiveWire(Wire);
impl std::ops::Deref for SensitiveWire {
    type Target = Wire;
    fn deref(&self) -> &Wire {
        &self.0
    }
}
impl std::ops::DerefMut for SensitiveWire {
    fn deref_mut(&mut self) -> &mut Wire {
        &mut self.0
    }
}
impl Drop for SensitiveWire {
    fn drop(&mut self) {
        self.0
            .records
            .values_mut()
            .for_each(|record| wipe(&mut record.data));
    }
}

fn load_wire(directory: &Path, key: &[u8; 32]) -> Result<SensitiveWire> {
    let path = directory.join("changes.mdbxsync");
    let metadata = path.symlink_metadata().map_err(|_| "storage_read_failed")?;
    if !metadata.is_file()
        || metadata.file_type().is_symlink()
        || metadata.len() > (MAX_STATE + 1024) as u64
    {
        return Err("invalid_replica");
    }
    let encrypted = std::fs::read(path).map_err(|_| "storage_read_failed")?;
    let wire = SensitiveWire(
        serde_json::from_slice(&unseal(&encrypted, key)?).map_err(|_| "invalid_replica")?,
    );
    if wire.version != 1 || wire.records.len() > MAX_RECORDS || wire.aliases.len() > MAX_RECORDS * 2
    {
        return Err("replica_limit");
    }
    for (id, record) in &wire.records {
        validate_record(record)?;
        if id != &record.id {
            return Err("invalid_record");
        }
    }
    if wire
        .aliases
        .iter()
        .any(|(a, b)| !peering::identifier(a) || !peering::identifier(b) || a <= b)
    {
        return Err("invalid_alias");
    }
    Ok(wire)
}

struct AssetRange {
    start: u64,
    end: u64,
}

/// Check every AEAD chunk and complete plaintext digest before claiming an asset
/// is available. Only original ciphertext ranges are copied to the delta archive.
fn assets(directory: &Path, key: &[u8; 32], job: &Job) -> Result<BTreeMap<String, AssetRange>> {
    let path = directory.join("assets.monica");
    if !path.exists() {
        return Ok(BTreeMap::new());
    }
    let metadata = path.symlink_metadata().map_err(|_| "asset_unavailable")?;
    if !metadata.is_file() || metadata.file_type().is_symlink() || metadata.len() > MAX_FILE {
        return Err("invalid_asset");
    }
    let mut file = std::fs::File::open(path).map_err(|_| "asset_unavailable")?;
    let mut magic = vec![0; ASSET_MAGIC.len()];
    file.read_exact(&mut magic).map_err(|_| "invalid_asset")?;
    if magic != ASSET_MAGIC {
        return Err("invalid_asset");
    }
    let mut ranges = BTreeMap::new();
    while file.stream_position().map_err(|_| "invalid_asset")? < metadata.len() {
        let start = file.stream_position().map_err(|_| "invalid_asset")?;
        let mut expected_id = String::new();
        let mut index = 0u32;
        let mut total = 0usize;
        let mut digest = Sha256::new();
        loop {
            job.check()?;
            let mut header = [0; ASSET_HEADER];
            file.read_exact(&mut header).map_err(|_| "invalid_asset")?;
            let id = std::str::from_utf8(&header[..64]).map_err(|_| "invalid_asset")?;
            let size = u32::from_le_bytes(header[68..72].try_into().unwrap()) as usize;
            let chunk_index = u32::from_le_bytes(header[64..68].try_into().unwrap());
            if !digest_valid(id) || size > ASSET_CHUNK || header[72] > 1 || chunk_index != index {
                return Err("invalid_asset");
            }
            if index == 0 {
                expected_id = id.to_owned();
            }
            if id != expected_id {
                return Err("invalid_asset");
            }
            total = total.checked_add(size).ok_or("asset_limit")?;
            if total > 64 * 1024 * 1024 {
                return Err("asset_limit");
            }
            let mut nonce = [0; 12];
            file.read_exact(&mut nonce).map_err(|_| "invalid_asset")?;
            let mut ciphertext = vec![0; size + 16];
            file.read_exact(&mut ciphertext)
                .map_err(|_| "invalid_asset")?;
            let plain = Zeroizing::new(
                ChaCha20Poly1305::new(key.into())
                    .decrypt(
                        Nonce::from_slice(&nonce),
                        Payload {
                            msg: &ciphertext,
                            aad: &header,
                        },
                    )
                    .map_err(|_| "invalid_asset")?,
            );
            digest.update(&*plain);
            if header[72] == 1 {
                break;
            }
            if size == 0 {
                return Err("invalid_asset");
            }
            index = index.checked_add(1).ok_or("asset_limit")?;
        }
        if format!("{:x}", digest.finalize()) != expected_id {
            return Err("invalid_asset");
        }
        let end = file.stream_position().map_err(|_| "invalid_asset")?;
        if ranges
            .insert(expected_id, AssetRange { start, end })
            .is_some()
            || ranges.len() > MAX_RECORDS
        {
            return Err("invalid_asset");
        }
    }
    Ok(ranges)
}

pub(crate) struct Snapshot {
    directory: PathBuf,
    wire: SensitiveWire,
    key: Zeroizing<[u8; 32]>,
    available: BTreeMap<String, AssetRange>,
    pub(crate) inventory: Inventory,
}

fn wipe(value: &mut Value) {
    match value {
        Value::String(text) => text.zeroize(),
        Value::Array(values) => values.iter_mut().for_each(wipe),
        Value::Object(values) => values.values_mut().for_each(wipe),
        _ => {}
    }
}

impl Snapshot {
    pub(crate) fn open(directory: &Path, encoded_key: &str, job: &Job) -> Result<Self> {
        let key = peering::secret_key(encoded_key)?;
        let wire = load_wire(directory, &key)?;
        let available = assets(directory, &key, job)?;
        let mut inventory = Inventory::empty();
        for (id, record) in &wire.records {
            job.check()?;
            let plain = Zeroizing::new(serde_json::to_vec(record).map_err(|_| "invalid_record")?);
            inventory.records.insert(id.clone(), hash(&plain));
        }
        inventory.aliases = wire.aliases.clone();
        inventory.assets = available.keys().cloned().collect();
        Ok(Self {
            directory: directory.to_owned(),
            wire,
            key,
            available,
            inventory,
        })
    }

    pub(crate) fn delta(&self, peer: &Inventory, peer_id: &str, job: &Job) -> Result<PathBuf> {
        if !peering::identifier(peer_id) {
            return Err("invalid_device");
        }
        // The source directory is immutable while any transfer holds it. A cached
        // delta can therefore be reused byte-for-byte on a reconnect, with a fresh
        // Noise handshake. Cache names reveal neither device IDs nor record IDs.
        let identity =
            Zeroizing::new(serde_json::to_vec(&(peer_id, peer)).map_err(|_| "invalid_inventory")?);
        let destination = self.directory.join(format!(".delta-{}", hash(&identity)));
        let mut delta = SensitiveWire(Wire {
            version: 1,
            records: self
                .wire
                .records
                .iter()
                .filter(|(id, _)| self.inventory.records.get(*id) != peer.records.get(*id))
                .map(|(id, r)| (id.clone(), r.clone()))
                .collect(),
            aliases: self
                .wire
                .aliases
                .iter()
                .filter(|(id, target)| peer.aliases.get(*id) != Some(*target))
                .map(|(id, target)| (id.clone(), target.clone()))
                .collect(),
        });
        let selected: BTreeSet<String> = self
            .available
            .keys()
            .filter(|id| !peer.assets.contains(*id))
            .cloned()
            .collect();
        let plain = Zeroizing::new(serde_json::to_vec(&*delta).map_err(|_| "invalid_replica")?);
        delta
            .records
            .values_mut()
            .for_each(|record| wipe(&mut record.data));
        // Concurrent peers share a source snapshot. Serialize the entire cache
        // check, quota reservation, publication and failed-staging cleanup so
        // they cannot independently spend the same remaining slot or bytes.
        let _publication = cache_publication(job)?;
        if destination.exists() {
            let metadata = destination
                .symlink_metadata()
                .map_err(|_| "storage_read_failed")?;
            if !metadata.is_dir() || metadata.file_type().is_symlink() {
                return Err("invalid_destination");
            }
            let mut existing = load_wire(&destination, &self.key)?;
            let existing_plain =
                Zeroizing::new(serde_json::to_vec(&*existing).map_err(|_| "invalid_replica")?);
            existing
                .records
                .values_mut()
                .for_each(|record| wipe(&mut record.data));
            if *plain != *existing_plain
                || assets(&destination, &self.key, job)?
                    .keys()
                    .cloned()
                    .collect::<BTreeSet<_>>()
                    != selected
            {
                return Err("artifact_changed");
            }
            return Ok(destination);
        }
        // A changing remote inventory must not create unbounded encrypted cache
        // copies of one source snapshot. Existing caches remain immutable.
        let mut cache_count = 0usize;
        let mut cache_bytes = 0u64;
        for entry in std::fs::read_dir(&self.directory).map_err(|_| "storage_read_failed")? {
            let entry = entry.map_err(|_| "storage_read_failed")?;
            if !entry.file_name().to_string_lossy().starts_with(".delta-") {
                continue;
            }
            let metadata = entry
                .path()
                .symlink_metadata()
                .map_err(|_| "storage_read_failed")?;
            if !metadata.is_dir() || metadata.file_type().is_symlink() {
                return Err("invalid_destination");
            }
            cache_count += 1;
            for name in ["changes.mdbxsync", "assets.monica"] {
                if let Ok(metadata) = entry.path().join(name).symlink_metadata() {
                    if !metadata.is_file() || metadata.file_type().is_symlink() {
                        return Err("invalid_destination");
                    }
                    cache_bytes = cache_bytes
                        .checked_add(metadata.len())
                        .ok_or("replica_limit")?;
                }
            }
        }
        let prospective = plain.len() as u64
            + 1024
            + selected
                .iter()
                .map(|id| self.available[id].end - self.available[id].start)
                .sum::<u64>();
        if cache_count >= 8
            || cache_bytes
                .checked_add(prospective)
                .is_none_or(|bytes| bytes > MAX_FILE)
        {
            // A send-only source may keep the same immutable snapshot through
            // many changing peer inventories. Preserve sync availability without
            // spending cache quota: send that original encrypted full snapshot.
            // Invalid/corrupt cache checks above still fail closed.
            return Ok(self.directory.clone());
        }
        let staging = self
            .directory
            .join(format!(".delta-staging-{}", peering::random_id()));
        std::fs::create_dir(&staging).map_err(|_| "storage_directory_failed")?;
        let result = (|| {
            job.check()?;
            write(
                &staging.join("changes.mdbxsync"),
                &seal(&plain, &self.key)?,
                false,
            )?;
            if !selected.is_empty() {
                let mut input = std::fs::File::open(self.directory.join("assets.monica"))
                    .map_err(|_| "asset_unavailable")?;
                let mut output = std::fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(staging.join("assets.monica"))
                    .map_err(|_| "storage_create_failed")?;
                output
                    .write_all(ASSET_MAGIC)
                    .map_err(|_| "storage_write_failed")?;
                let mut buffer = vec![0; ASSET_CHUNK];
                for id in &selected {
                    let range = &self.available[id];
                    input
                        .seek(SeekFrom::Start(range.start))
                        .map_err(|_| "storage_read_failed")?;
                    let mut remaining = range.end - range.start;
                    while remaining > 0 {
                        job.check()?;
                        let count = remaining.min(buffer.len() as u64) as usize;
                        input
                            .read_exact(&mut buffer[..count])
                            .map_err(|_| "storage_read_failed")?;
                        output
                            .write_all(&buffer[..count])
                            .map_err(|_| "storage_write_failed")?;
                        remaining -= count as u64;
                    }
                }
                output.sync_all().map_err(|_| "storage_flush_failed")?;
            }
            job.check()?;
            std::fs::rename(&staging, &destination).map_err(|_| "storage_publish_failed")?;
            Ok(destination.clone())
        })();
        if staging.exists() {
            let _ = std::fs::remove_dir_all(staging);
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn empty_snapshot(directory: &Path) -> Snapshot {
        let key = peering::random_key();
        let decoded = peering::secret_key(&key).unwrap();
        let wire = Wire {
            version: 1,
            records: BTreeMap::new(),
            aliases: BTreeMap::new(),
        };
        write(
            &directory.join("changes.mdbxsync"),
            &seal(&serde_json::to_vec(&wire).unwrap(), &decoded).unwrap(),
            false,
        )
        .unwrap();
        Snapshot::open(directory, &key, &Job::new("checking")).unwrap()
    }

    #[test]
    fn concurrent_peers_cannot_spend_the_same_remaining_cache_slot() {
        let root = crate::tests::TestDirectory::new();
        let out = root.directory("concurrent-cache-out");
        let snapshot = empty_snapshot(&out);
        let original = std::fs::read(out.join("changes.mdbxsync")).unwrap();
        let inventory = Inventory::empty();
        for index in 0..7 {
            snapshot
                .delta(
                    &inventory,
                    &format!("existing-{index}"),
                    &Job::new("checking"),
                )
                .unwrap();
        }
        let barrier = std::sync::Barrier::new(3);
        let results = std::thread::scope(|scope| {
            let workers: Vec<_> = ["new-peer-one", "new-peer-two"]
                .into_iter()
                .map(|peer| {
                    let snapshot = &snapshot;
                    let inventory = &inventory;
                    let barrier = &barrier;
                    scope.spawn(move || {
                        barrier.wait();
                        snapshot.delta(inventory, peer, &Job::new("checking"))
                    })
                })
                .collect();
            barrier.wait();
            workers
                .into_iter()
                .map(|worker| worker.join().unwrap())
                .collect::<Vec<_>>()
        });
        assert!(results.iter().all(|result| result.is_ok()));
        assert_eq!(
            results
                .iter()
                .filter(|result| result.as_ref().unwrap() == &out)
                .count(),
            1
        );
        assert_eq!(
            results
                .iter()
                .filter(|result| result.as_ref().unwrap() != &out)
                .count(),
            1
        );
        let caches: Vec<_> = std::fs::read_dir(&out)
            .unwrap()
            .map(|entry| entry.unwrap())
            .filter(|entry| entry.file_name().to_string_lossy().starts_with(".delta-"))
            .collect();
        assert_eq!(caches.len(), 8);
        assert!(caches.iter().all(|entry| !entry
            .file_name()
            .to_string_lossy()
            .starts_with(".delta-staging-")));
        assert_eq!(
            std::fs::read(out.join("changes.mdbxsync")).unwrap(),
            original
        );
        for result in results.into_iter().flatten() {
            assert!(load_wire(&result, &snapshot.key)
                .unwrap()
                .records
                .is_empty());
        }
    }

    #[test]
    fn cancelling_a_cache_publication_waiter_creates_no_cache_or_staging() {
        let root = crate::tests::TestDirectory::new();
        let out = root.directory("cancelled-cache-out");
        let snapshot = empty_snapshot(&out);
        let held = cache_publication(&Job::new("checking")).unwrap();
        let job = Job::new("checking");
        let (started, starting) = std::sync::mpsc::channel();
        let (finished, result) = std::sync::mpsc::channel();
        std::thread::scope(|scope| {
            let worker = scope.spawn(|| {
                started.send(()).unwrap();
                finished
                    .send(snapshot.delta(&Inventory::empty(), "waiting-peer", &job))
                    .unwrap();
            });
            starting
                .recv_timeout(std::time::Duration::from_secs(1))
                .unwrap();
            assert!(matches!(
                result.recv_timeout(std::time::Duration::from_millis(50)),
                Err(std::sync::mpsc::RecvTimeoutError::Timeout)
            ));
            job.cancel.store(true, std::sync::atomic::Ordering::Release);
            assert_eq!(
                result
                    .recv_timeout(std::time::Duration::from_secs(1))
                    .unwrap(),
                Err("cancelled")
            );
            worker.join().unwrap();
        });
        assert_eq!(std::fs::read_dir(&out).unwrap().count(), 1);
        drop(held);
        assert!(snapshot
            .delta(&Inventory::empty(), "waiting-peer", &Job::new("checking"))
            .is_ok());
    }

    fn record(id: &str, version: u64, deleted: bool) -> Record {
        Record {
            id: id.into(),
            clock: BTreeMap::from([("node".into(), version)]),
            origins: BTreeSet::from(["node".into()]),
            writer: "node".into(),
            deleted,
            data: json!({"title":"Synthetic","password":if deleted { "" } else { "secret" }}),
        }
    }
    #[test]
    fn rejects_malformed_inventory_and_aliases() {
        for value in [
            json!({"version":2,"records":{},"aliases":{},"assets":[]}),
            json!({"version":1,"records":{"../x":"0"},"aliases":{},"assets":[]}),
            json!({"version":1,"records":{},"aliases":{"a":"z"},"assets":[]}),
        ] {
            assert!(Inventory::parse(&serde_json::to_vec(&value).unwrap()).is_err());
        }
    }
    #[test]
    fn exact_versions_only_omit_never_deletes_and_retry_ciphertext_is_stable() {
        let root = std::env::temp_dir().join(format!("monica-delta-{}", peering::random_id()));
        std::fs::create_dir(&root).unwrap();
        let key = peering::random_key();
        let raw_key = peering::secret_key(&key).unwrap();
        let wire = Wire {
            version: 1,
            records: BTreeMap::from([
                ("one".into(), record("one", 1, false)),
                ("two".into(), record("two", 2, true)),
            ]),
            aliases: BTreeMap::from([("z".into(), "one".into())]),
        };
        write(
            &root.join("changes.mdbxsync"),
            &seal(&serde_json::to_vec(&wire).unwrap(), &raw_key).unwrap(),
            false,
        )
        .unwrap();
        let job = Job::new("checking");
        let snapshot = Snapshot::open(&root, &key, &job).unwrap();
        let mut remote = Inventory::empty();
        remote
            .records
            .insert("one".into(), snapshot.inventory.records["one"].clone());
        let path = snapshot.delta(&remote, "peer", &job).unwrap();
        let bytes = std::fs::read(path.join("changes.mdbxsync")).unwrap();
        let delta = load_wire(&path, &raw_key).unwrap();
        assert_eq!(delta.records.len(), 1);
        assert!(delta.records["two"].deleted);
        assert_eq!(delta.aliases, wire.aliases);
        assert_eq!(snapshot.delta(&remote, "peer", &job).unwrap(), path);
        assert_eq!(std::fs::read(path.join("changes.mdbxsync")).unwrap(), bytes);
        remote.records.insert("one".into(), "0".repeat(64));
        assert_eq!(
            load_wire(&snapshot.delta(&remote, "peer", &job).unwrap(), &raw_key)
                .unwrap()
                .records
                .len(),
            2
        );
        assert!(!bytes.windows(b"secret".len()).any(|w| w == b"secret"));
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn corrupt_local_asset_never_becomes_a_claimed_available_asset() {
        let root = std::env::temp_dir().join(format!("monica-delta-{}", peering::random_id()));
        std::fs::create_dir(&root).unwrap();
        let key = peering::random_key();
        let raw = peering::secret_key(&key).unwrap();
        write(
            &root.join("changes.mdbxsync"),
            &seal(
                &serde_json::to_vec(&Wire {
                    version: 1,
                    records: BTreeMap::new(),
                    aliases: BTreeMap::new(),
                })
                .unwrap(),
                &raw,
            )
            .unwrap(),
            false,
        )
        .unwrap();
        std::fs::write(root.join("assets.monica"), b"corrupt").unwrap();
        assert!(Snapshot::open(&root, &key, &Job::new("checking")).is_err());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn equal_logical_record_still_transfers_missing_asset_and_preserves_pending_application() {
        let root = crate::tests::TestDirectory::new();
        let out = root.directory("pending-asset-out");
        let key = peering::random_key();
        let decoded = peering::secret_key(&key).unwrap();
        let bytes = vec![0xabu8; 24_000];
        let id = hash(&bytes);
        let mut live = record("one", 1, false);
        live.data["assets"] = json!([{"id":id,"size":bytes.len(),"name":"synthetic.dat"}]);
        let wire = Wire {
            version: 1,
            records: BTreeMap::from([("one".into(), live.clone())]),
            aliases: BTreeMap::new(),
        };
        write(
            &out.join("changes.mdbxsync"),
            &seal(&serde_json::to_vec(&wire).unwrap(), &decoded).unwrap(),
            false,
        )
        .unwrap();
        crate::assets::request(&json!({"operation":"asset_append","path":out.join("assets.monica"),"key":key,
            "id":id,"index":0,"final":true,"data":base64::engine::general_purpose::STANDARD.encode(&bytes)})).unwrap();
        let job = Job::new("checking");
        let snapshot = Snapshot::open(&out, &key, &job).unwrap();
        let mut remote = Inventory::empty();
        remote
            .records
            .insert("one".into(), snapshot.inventory.records["one"].clone());
        // The peer has the logical version but no verified local asset.
        let delta = snapshot.delta(&remote, "peer", &job).unwrap();
        assert!(load_wire(&delta, &decoded).unwrap().records.is_empty());
        assert_eq!(
            assets(&delta, &decoded, &job)
                .unwrap()
                .keys()
                .cloned()
                .collect::<Vec<_>>(),
            vec![id]
        );
        let baseline = State {
            version: 1,
            node: "peer".into(),
            records: wire.records,
            ..State::default()
        };
        assert!(has_pending(&baseline));
        let merged = incoming_state(
            &baseline,
            &json!({"path":delta.join("changes.mdbxsync"),"content_key":key}),
            false,
        )
        .unwrap();
        assert!(merged.records["one"] == live);
        assert!(
            merged.applied.is_empty(),
            "an inventory match must never acknowledge physical application"
        );
        assert!(has_pending(&merged));
    }

    #[test]
    fn alias_only_delta_preserves_unrelated_records_and_remaps_pending_bindings() {
        let root = crate::tests::TestDirectory::new();
        let out = root.directory("alias-only-out");
        let key = peering::random_key();
        let decoded = peering::secret_key(&key).unwrap();
        let one = record("one", 1, false);
        let two = record("two", 3, false);
        let wire = Wire {
            version: 1,
            records: BTreeMap::from([("one".into(), one.clone()), ("two".into(), two.clone())]),
            aliases: BTreeMap::from([("z-alias".into(), "one".into())]),
        };
        write(
            &out.join("changes.mdbxsync"),
            &seal(&serde_json::to_vec(&wire).unwrap(), &decoded).unwrap(),
            false,
        )
        .unwrap();
        let job = Job::new("checking");
        let snapshot = Snapshot::open(&out, &key, &job).unwrap();
        let mut old_alias = one.clone();
        old_alias.id = "z-alias".into();
        let mut remote = Inventory::empty();
        remote.records = snapshot.inventory.records.clone();
        remote.records.insert(
            "z-alias".into(),
            hash(&serde_json::to_vec(&old_alias).unwrap()),
        );
        let delta = snapshot.delta(&remote, "peer", &job).unwrap();
        let selected = load_wire(&delta, &decoded).unwrap();
        assert!(selected.records.is_empty());
        assert_eq!(selected.aliases, wire.aliases);
        let baseline = State {
            version: 1,
            node: "peer".into(),
            records: BTreeMap::from([
                ("one".into(), one.clone()),
                ("two".into(), two.clone()),
                ("z-alias".into(), old_alias),
            ]),
            bindings: BTreeMap::from([(
                "local-alias".into(),
                Binding {
                    record_id: "z-alias".into(),
                    scope: "local".into(),
                    hash: "synthetic".into(),
                    clock: one.clock.clone(),
                    guard: "synthetic".into(),
                    deleted: false,
                },
            )]),
            ..State::default()
        };
        let merged = incoming_state(
            &baseline,
            &json!({"path":delta.join("changes.mdbxsync"),"content_key":key}),
            false,
        )
        .unwrap();
        assert_eq!(merged.records.len(), 2);
        assert!(merged.records["two"] == two);
        assert_eq!(merged.bindings["local-alias"].record_id, "one");
        assert_eq!(resolve(&merged, "z-alias"), "one");
        assert!(merged.applied.is_empty());
        assert!(has_pending(&merged));
    }
}

#[cfg(test)]
#[path = "incremental_transfer_tests.rs"]
mod transfer_tests;
