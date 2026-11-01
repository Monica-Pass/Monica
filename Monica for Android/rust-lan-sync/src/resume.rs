//! Paired-only ciphertext checkpoints. Offers travel inside a fresh Noise
//! channel and are checked against the sender's immutable ciphertext source.
//! This module never persists a content key, plaintext, or Noise state.
use super::{Result, FILE_NAMES, MAX_FILE};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashSet,
    fs::{File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::{Duration, SystemTime},
};

const TTL: Duration = Duration::from_secs(600);
const MAX_PARTIALS: usize = 8;
const MAX_WRITE: usize = 64 * 1024;
static OWNERS: OnceLock<Mutex<HashSet<PathBuf>>> = OnceLock::new();

fn owners() -> &'static Mutex<HashSet<PathBuf>> {
    OWNERS.get_or_init(|| Mutex::new(HashSet::new()))
}

fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

fn field(hash: &mut Sha256, value: &[u8]) {
    hash.update((value.len() as u64).to_be_bytes());
    hash.update(value);
}

pub(super) struct Scope(String);
impl Scope {
    /// Only call after Noise and expected-device / pair-ID validation. The
    /// opaque checkpoint namespace changes when the pairing secret changes.
    pub(super) fn paired(peer: &str, pair: &str, key: &[u8; 32]) -> Result<Self> {
        if !identifier(peer) || !identifier(pair) {
            return Err("invalid_device");
        }
        let mut hash = Sha256::new();
        hash.update(b"monica-paired-resume/1");
        field(&mut hash, peer.as_bytes());
        field(&mut hash, pair.as_bytes());
        field(&mut hash, key);
        Ok(Self(format!("{:x}", hash.finalize())))
    }
}

#[derive(Clone)]
pub(super) struct Descriptor {
    name: String,
    size: u64,
    sha256: String,
}
impl Descriptor {
    pub(super) fn new(name: &str, size: u64, sha256: &str) -> Result<Self> {
        if !FILE_NAMES.contains(&name)
            || size == 0
            || size > MAX_FILE
            || sha256.len() != 64
            || !sha256.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err("invalid_manifest");
        }
        Ok(Self {
            name: name.to_owned(),
            size,
            sha256: sha256.to_ascii_lowercase(),
        })
    }

    fn digest(&self) -> String {
        let mut hash = Sha256::new();
        hash.update(b"monica-resume-artifact/1");
        field(&mut hash, self.name.as_bytes());
        field(&mut hash, &self.size.to_be_bytes());
        field(&mut hash, self.sha256.as_bytes());
        format!("{:x}", hash.finalize())
    }
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Offer {
    pub(super) offset: u64,
    pub(super) prefix_sha256: String,
}

fn regular(path: &Path) -> Result<std::fs::Metadata> {
    let metadata = path.symlink_metadata().map_err(|_| "storage_read_failed")?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err("invalid_destination");
    }
    Ok(metadata)
}

fn owned_name(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| {
            name.len() == 134
                && name.is_ascii()
                && name.as_bytes()[64] == b'-'
                && name.ends_with(".part")
                && name[..64]
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                && name[65..129]
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
}

fn scan(root: &Path) -> Result<Vec<(PathBuf, std::fs::Metadata)>> {
    let mut files = Vec::new();
    for entry in std::fs::read_dir(root).map_err(|_| "storage_read_failed")? {
        let path = entry.map_err(|_| "storage_read_failed")?.path();
        if !owned_name(&path) {
            continue;
        }
        // Do not follow or delete an unexpected symlink/directory.
        let metadata = regular(&path)?;
        files.push((path, metadata));
        if files.len() > MAX_PARTIALS {
            return Err("resume_limit");
        }
    }
    Ok(files)
}

fn expired(metadata: &std::fs::Metadata, now: SystemTime) -> bool {
    metadata
        .modified()
        .ok()
        .and_then(|modified| now.duration_since(modified).ok())
        .is_none_or(|age| age >= TTL)
}

/// Maintenance must be invoked on app startup and after cancelled workers have
/// retired. TTL always controls reuse; deleting from disk requires a live app.
pub(super) fn prune(root: &Path) -> Result<()> {
    cleanup(root, false)
}

/// Explicit lock/forget cleanup removes only inactive, protocol-owned partials.
/// A still-running worker keeps its lease until cancellation has completed.
pub(super) fn clear(root: &Path) -> Result<()> {
    cleanup(root, true)
}

fn cleanup(root: &Path, clear: bool) -> Result<()> {
    if !root.is_absolute() {
        return Err("invalid_destination");
    }
    let metadata = match root.symlink_metadata() {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(_) => return Err("storage_read_failed"),
    };
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err("invalid_destination");
    }
    let root = root.canonicalize().map_err(|_| "storage_read_failed")?;
    let leases = owners().lock().map_err(|_| "unavailable")?;
    let now = SystemTime::now();
    for (path, metadata) in scan(&root)? {
        if !leases.contains(&path) && (clear || expired(&metadata, now)) {
            std::fs::remove_file(path).map_err(|_| "storage_remove_failed")?;
        }
    }
    Ok(())
}

fn hash_prefix(
    file: &mut File,
    length: u64,
    check: &mut dyn FnMut() -> Result<()>,
) -> Result<String> {
    file.seek(SeekFrom::Start(0))
        .map_err(|_| "storage_read_failed")?;
    let mut remaining = length;
    let mut hash = Sha256::new();
    let mut buffer = [0u8; MAX_WRITE];
    while remaining > 0 {
        check()?;
        let maximum = remaining.min(MAX_WRITE as u64) as usize;
        let count = file
            .read(&mut buffer[..maximum])
            .map_err(|_| "storage_read_failed")?;
        if count == 0 {
            return Err("artifact_changed");
        }
        hash.update(&buffer[..count]);
        remaining -= count as u64;
    }
    check()?;
    Ok(format!("{:x}", hash.finalize()))
}

/// The sender independently verifies the offered prefix. A malformed, corrupt,
/// or stale offer restarts at zero; a changed sender source fails closed.
pub(super) fn accept_offset(
    source: &Path,
    artifact: &Descriptor,
    offer: &Offer,
    check: &mut dyn FnMut() -> Result<()>,
) -> Result<u64> {
    check()?;
    if regular(source)?.len() != artifact.size {
        return Err("artifact_changed");
    }
    if offer.offset > artifact.size
        || offer.prefix_sha256.len() != 64
        || !offer.prefix_sha256.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Ok(0);
    }
    let mut file = File::open(source).map_err(|_| "storage_read_failed")?;
    let actual = hash_prefix(&mut file, offer.offset, check)?;
    if hash_prefix(&mut file, artifact.size, check)? != artifact.sha256 {
        return Err("artifact_changed");
    }
    Ok(if actual.eq_ignore_ascii_case(&offer.prefix_sha256) {
        offer.offset
    } else {
        0
    })
}

pub(super) struct Partial {
    root: PathBuf,
    path: PathBuf,
    artifact: Descriptor,
    file: File,
    offset: u64,
}

impl Partial {
    pub(super) fn open(
        root: &Path,
        scope: &Scope,
        artifact: Descriptor,
        check: &mut dyn FnMut() -> Result<()>,
    ) -> Result<Self> {
        check()?;
        if !root.is_absolute() {
            return Err("invalid_destination");
        }
        if !root.exists() {
            let created = match std::fs::create_dir(root) {
                Ok(()) => true,
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => false,
                Err(_) => return Err("storage_directory_failed"),
            };
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                if created {
                    std::fs::set_permissions(root, std::fs::Permissions::from_mode(0o700))
                        .map_err(|_| "storage_directory_failed")?;
                }
            }
            #[cfg(not(unix))]
            let _ = created;
        }
        let metadata = root.symlink_metadata().map_err(|_| "storage_read_failed")?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err("invalid_destination");
        }
        let root = root.canonicalize().map_err(|_| "storage_read_failed")?;
        let path = root.join(format!("{}-{}.part", scope.0, artifact.digest()));
        let mut leases = owners().lock().map_err(|_| "unavailable")?;
        if leases.contains(&path) {
            return Err("resume_busy");
        }
        // Cleanup never touches unowned files or another active transfer.
        let now = SystemTime::now();
        for (candidate, metadata) in scan(&root)? {
            if !leases.contains(&candidate) && expired(&metadata, now) {
                std::fs::remove_file(candidate).map_err(|_| "storage_remove_failed")?;
            }
        }
        let files = scan(&root)?;
        if !path.exists() && files.len() >= MAX_PARTIALS {
            return Err("resume_limit");
        }
        let mut options = OpenOptions::new();
        options.read(true).write(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = if path.exists() {
            regular(&path)?;
            options.open(&path)
        } else {
            options.create_new(true).open(&path)
        }
        .map_err(|_| "storage_create_failed")?;
        let offset = file.metadata().map_err(|_| "storage_read_failed")?.len();
        file.seek(SeekFrom::Start(offset))
            .map_err(|_| "storage_read_failed")?;
        leases.insert(path.clone());
        drop(leases);
        let mut partial = Self {
            root,
            path,
            artifact,
            file,
            offset,
        };
        if offset > partial.artifact.size {
            partial.restart(0)?;
        }
        Ok(partial)
    }

    pub(super) fn offer(&mut self, check: &mut dyn FnMut() -> Result<()>) -> Result<Offer> {
        check()?;
        self.file.sync_all().map_err(|_| "storage_flush_failed")?;
        let prefix_sha256 = hash_prefix(&mut self.file, self.offset, check)?;
        Ok(Offer {
            offset: self.offset,
            prefix_sha256,
        })
    }

    /// Accept only the exact offered position or an explicit full restart.
    pub(super) fn restart(&mut self, offset: u64) -> Result<()> {
        if offset != 0 && offset != self.offset {
            return Err("invalid_resume");
        }
        if offset == 0 {
            self.file.set_len(0).map_err(|_| "storage_write_failed")?;
        }
        self.offset = offset;
        self.file
            .seek(SeekFrom::Start(offset))
            .map_err(|_| "storage_write_failed")?;
        Ok(())
    }

    pub(super) fn append(
        &mut self,
        bytes: &[u8],
        check: &mut dyn FnMut() -> Result<()>,
    ) -> Result<()> {
        check()?;
        if bytes.is_empty()
            || bytes.len() > MAX_WRITE
            || self
                .offset
                .checked_add(bytes.len() as u64)
                .is_none_or(|next| next > self.artifact.size)
        {
            return Err("invalid_frame");
        }
        let _leases = owners().lock().map_err(|_| "unavailable")?;
        let total = scan(&self.root)?
            .iter()
            .try_fold(0u64, |total, (_, metadata)| {
                total.checked_add(metadata.len()).ok_or("resume_limit")
            })?;
        if total
            .checked_add(bytes.len() as u64)
            .is_none_or(|next| next > MAX_FILE)
        {
            return Err("resume_limit");
        }
        check()?;
        self.file
            .write_all(bytes)
            .map_err(|_| "storage_write_failed")?;
        self.offset += bytes.len() as u64;
        Ok(())
    }

    pub(super) fn finish(
        mut self,
        destination: &Path,
        check: &mut dyn FnMut() -> Result<()>,
    ) -> Result<()> {
        check()?;
        let parent = destination.parent().ok_or("invalid_destination")?;
        let parent_metadata = parent
            .symlink_metadata()
            .map_err(|_| "storage_read_failed")?;
        if !destination.is_absolute()
            || !parent_metadata.is_dir()
            || parent_metadata.file_type().is_symlink()
            || destination.file_name().and_then(|name| name.to_str())
                != Some(self.artifact.name.as_str())
            || self.offset != self.artifact.size
            || self
                .file
                .metadata()
                .map_err(|_| "storage_read_failed")?
                .len()
                != self.artifact.size
        {
            return Err("invalid_resume");
        }
        if hash_prefix(&mut self.file, self.artifact.size, check)? != self.artifact.sha256 {
            return Err("artifact_changed");
        }
        self.file.sync_all().map_err(|_| "storage_flush_failed")?;
        check()?;
        crate::storage::publish_new(&self.path, destination)
            .map_err(|_| "storage_publish_failed")?;
        // Do not remove the published destination on a checkpoint cleanup error.
        std::fs::remove_file(&self.path).map_err(|_| "storage_remove_failed")?;
        Ok(())
    }

    pub(super) fn discard(self) -> Result<()> {
        std::fs::remove_file(&self.path).map_err(|_| "storage_remove_failed")
    }
}

impl Drop for Partial {
    fn drop(&mut self) {
        // Transient failures preserve ciphertext only; TTL bounds reuse, and
        // prune/clear plus startup maintenance bound live-app disk retention.
        if let Ok(mut leases) = owners().lock() {
            leases.remove(&self.path);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Directory(PathBuf);
    impl Directory {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "monica-resume-test-{}",
                crate::peering::random_id()
            ));
            std::fs::create_dir(&path).unwrap();
            Self(path)
        }
        fn source(&self, bytes: &[u8]) -> PathBuf {
            let path = self.0.join("source");
            std::fs::write(&path, bytes).unwrap();
            path
        }
        fn root(&self) -> PathBuf {
            self.0.join(".resume")
        }
    }
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    fn ok() -> Result<()> {
        Ok(())
    }
    fn scope(key: u8) -> Scope {
        Scope::paired("peer", "pair", &[key; 32]).unwrap()
    }
    fn descriptor(bytes: &[u8]) -> Descriptor {
        Descriptor::new(
            "assets.monica",
            bytes.len() as u64,
            &format!("{:x}", Sha256::digest(bytes)),
        )
        .unwrap()
    }

    #[test]
    fn reconnect_verifies_prefix_and_publishes_exact_ciphertext() {
        let directory = Directory::new();
        let bytes = vec![0x9au8; 100_000];
        let source = directory.source(&bytes);
        let artifact = descriptor(&bytes);
        let mut first =
            Partial::open(&directory.root(), &scope(1), artifact.clone(), &mut ok).unwrap();
        first.append(&bytes[..60_000], &mut ok).unwrap();
        drop(first);
        let mut resumed =
            Partial::open(&directory.root(), &scope(1), artifact.clone(), &mut ok).unwrap();
        let offer = resumed.offer(&mut ok).unwrap();
        assert_eq!(offer.offset, 60_000);
        let offset = accept_offset(&source, &artifact, &offer, &mut ok).unwrap();
        resumed.restart(offset).unwrap();
        resumed.append(&bytes[offset as usize..], &mut ok).unwrap();
        let destination = directory.0.join("assets.monica");
        resumed.finish(&destination, &mut ok).unwrap();
        assert_eq!(std::fs::read(destination).unwrap(), bytes);
        assert!(scan(&directory.root()).unwrap().is_empty());
    }

    #[test]
    fn corrupt_prefix_restarts_instead_of_splicing_different_bytes() {
        let directory = Directory::new();
        let bytes = vec![0xf1u8; 100];
        let source = directory.source(&bytes);
        let artifact = descriptor(&bytes);
        let mut partial =
            Partial::open(&directory.root(), &scope(2), artifact.clone(), &mut ok).unwrap();
        partial.append(&[4; 50], &mut ok).unwrap();
        let offer = partial.offer(&mut ok).unwrap();
        assert_eq!(
            accept_offset(&source, &artifact, &offer, &mut ok).unwrap(),
            0
        );
        partial.restart(0).unwrap();
        partial.append(&bytes, &mut ok).unwrap();
        partial
            .finish(&directory.0.join("assets.monica"), &mut ok)
            .unwrap();
    }

    #[test]
    fn different_pair_secret_identity_and_snapshot_cannot_reuse_a_prefix() {
        let directory = Directory::new();
        let old = descriptor(&[6; 100]);
        let mut partial =
            Partial::open(&directory.root(), &scope(3), old.clone(), &mut ok).unwrap();
        partial.append(&[6; 50], &mut ok).unwrap();
        drop(partial);
        for (scope, artifact) in [
            (scope(4), old.clone()),
            (
                Scope::paired("other-peer", "pair", &[3; 32]).unwrap(),
                old.clone(),
            ),
            (
                Scope::paired("peer", "other-pair", &[3; 32]).unwrap(),
                old.clone(),
            ),
            (scope(3), descriptor(&[7; 100])),
            (scope(3), descriptor(&[6; 120])),
        ] {
            let mut partial = Partial::open(&directory.root(), &scope, artifact, &mut ok).unwrap();
            assert_eq!(partial.offer(&mut ok).unwrap().offset, 0);
            partial.discard().unwrap();
        }
    }

    #[test]
    fn fake_offset_and_full_digest_failure_never_publish() {
        let directory = Directory::new();
        let artifact = descriptor(&[8; 100]);
        let source = directory.source(&[8; 100]);
        for offer in [
            Offer {
                offset: 101,
                prefix_sha256: "0".repeat(64),
            },
            Offer {
                offset: 50,
                prefix_sha256: "secret-like-malformed".into(),
            },
        ] {
            assert_eq!(
                accept_offset(&source, &artifact, &offer, &mut ok).unwrap(),
                0
            );
        }
        let mut partial = Partial::open(&directory.root(), &scope(5), artifact, &mut ok).unwrap();
        assert_eq!(partial.restart(50).unwrap_err(), "invalid_resume");
        partial.append(&[7; 100], &mut ok).unwrap();
        let destination = directory.0.join("assets.monica");
        assert_eq!(
            partial.finish(&destination, &mut ok).unwrap_err(),
            "artifact_changed"
        );
        assert!(!destination.exists());
    }

    #[test]
    fn same_size_source_mutation_fails_before_accepting_any_offset() {
        let directory = Directory::new();
        let artifact = descriptor(&[8; 100]);
        let source = directory.source(&[8; 100]);
        let mut partial =
            Partial::open(&directory.root(), &scope(12), artifact.clone(), &mut ok).unwrap();
        partial.append(&[8; 50], &mut ok).unwrap();
        let offer = partial.offer(&mut ok).unwrap();
        std::fs::write(&source, [vec![8; 50], vec![9; 50]].concat()).unwrap();
        assert_eq!(
            accept_offset(&source, &artifact, &offer, &mut ok).unwrap_err(),
            "artifact_changed"
        );
        assert_eq!(
            accept_offset(&source, &artifact, &offer, &mut || Err("cancelled")).unwrap_err(),
            "cancelled"
        );
    }

    #[test]
    fn cancelled_work_never_writes_or_publishes_and_existing_destination_survives() {
        let directory = Directory::new();
        let bytes = [9; 100];
        let mut partial =
            Partial::open(&directory.root(), &scope(6), descriptor(&bytes), &mut ok).unwrap();
        assert_eq!(
            partial
                .append(&bytes, &mut || Err("cancelled"))
                .unwrap_err(),
            "cancelled"
        );
        assert_eq!(partial.offer(&mut ok).unwrap().offset, 0);
        partial.append(&bytes, &mut ok).unwrap();
        let destination = directory.0.join("assets.monica");
        assert_eq!(
            partial
                .finish(&destination, &mut || Err("cancelled"))
                .unwrap_err(),
            "cancelled"
        );
        assert!(!destination.exists());
        let mut partial =
            Partial::open(&directory.root(), &scope(6), descriptor(&bytes), &mut ok).unwrap();
        assert_eq!(partial.offer(&mut ok).unwrap().offset, 100);
        std::fs::write(&destination, b"prior valid encrypted artifact").unwrap();
        assert_eq!(
            partial.finish(&destination, &mut ok).unwrap_err(),
            "storage_publish_failed"
        );
        assert_eq!(
            std::fs::read(destination).unwrap(),
            b"prior valid encrypted artifact"
        );
    }

    #[test]
    fn quotas_exclusive_owner_and_unowned_files_are_preserved() {
        let directory = Directory::new();
        let first = Partial::open(
            &directory.root(),
            &scope(7),
            descriptor(&[10; 100]),
            &mut ok,
        )
        .unwrap();
        assert!(matches!(
            Partial::open(
                &directory.root(),
                &scope(7),
                descriptor(&[10; 100]),
                &mut ok
            ),
            Err("resume_busy")
        ));
        drop(first);
        std::fs::write(directory.root().join("unowned.txt"), b"preserve").unwrap();
        std::fs::write(
            directory
                .root()
                .join(format!("{}-{}.part", "中".repeat(21) + "a", "b".repeat(64))),
            b"preserve unicode",
        )
        .unwrap();
        for n in 11..18 {
            drop(
                Partial::open(&directory.root(), &scope(7), descriptor(&[n; 100]), &mut ok)
                    .unwrap(),
            );
        }
        assert!(matches!(
            Partial::open(
                &directory.root(),
                &scope(7),
                descriptor(&[20; 100]),
                &mut ok
            ),
            Err("resume_limit")
        ));
        assert_eq!(
            std::fs::read(directory.root().join("unowned.txt")).unwrap(),
            b"preserve"
        );
        assert!(Descriptor::new("../assets.monica", 1, &"0".repeat(64)).is_err());
        assert!(Descriptor::new("assets.monica", MAX_FILE + 1, &"0".repeat(64)).is_err());
        assert!(Scope::paired("../peer", "pair", &[0; 32]).is_err());
    }

    #[test]
    fn oversized_cached_partial_is_reset_before_an_offer() {
        let directory = Directory::new();
        let artifact = descriptor(&[6; 100]);
        let mut partial =
            Partial::open(&directory.root(), &scope(8), artifact.clone(), &mut ok).unwrap();
        partial.file.write_all(&[7; 150]).unwrap();
        drop(partial);
        partial = Partial::open(&directory.root(), &scope(8), artifact, &mut ok).unwrap();
        assert_eq!(partial.offer(&mut ok).unwrap().offset, 0);
    }

    #[test]
    fn expired_checkpoints_are_removed_without_touching_an_active_owner() {
        let directory = Directory::new();
        let mut partial =
            Partial::open(&directory.root(), &scope(9), descriptor(&[3; 100]), &mut ok).unwrap();
        partial.append(&[3; 40], &mut ok).unwrap();
        let old = SystemTime::now() - TTL - Duration::from_secs(5);
        partial
            .file
            .set_times(std::fs::FileTimes::new().set_modified(old))
            .unwrap();
        let active_path = partial.path.clone();
        let other =
            Partial::open(&directory.root(), &scope(9), descriptor(&[4; 100]), &mut ok).unwrap();
        assert!(active_path.exists());
        drop(other);
        drop(partial);
        let _third =
            Partial::open(&directory.root(), &scope(9), descriptor(&[5; 100]), &mut ok).unwrap();
        assert!(!active_path.exists());
    }

    #[test]
    fn aggregate_ciphertext_budget_is_enforced_before_append() {
        let directory = Directory::new();
        let mut partial = Partial::open(
            &directory.root(),
            &scope(10),
            descriptor(&[3; 100]),
            &mut ok,
        )
        .unwrap();
        let other = directory
            .root()
            .join(format!("{}-{}.part", "a".repeat(64), "b".repeat(64)));
        File::create(&other).unwrap().set_len(MAX_FILE).unwrap();
        assert_eq!(
            partial.append(&[3; 1], &mut ok).unwrap_err(),
            "resume_limit"
        );
        assert_eq!(partial.offer(&mut ok).unwrap().offset, 0);
    }

    #[test]
    fn maintenance_prunes_expired_only_and_clear_skips_active_and_unowned_files() {
        let directory = Directory::new();
        let mut expired_partial = Partial::open(
            &directory.root(),
            &scope(13),
            descriptor(&[3; 100]),
            &mut ok,
        )
        .unwrap();
        expired_partial.append(&[3; 20], &mut ok).unwrap();
        let expired_path = expired_partial.path.clone();
        expired_partial
            .file
            .set_times(
                std::fs::FileTimes::new()
                    .set_modified(SystemTime::now() - TTL - Duration::from_secs(1)),
            )
            .unwrap();
        let active = Partial::open(
            &directory.root(),
            &scope(13),
            descriptor(&[4; 100]),
            &mut ok,
        )
        .unwrap();
        let active_path = active.path.clone();
        let inactive = Partial::open(
            &directory.root(),
            &scope(13),
            descriptor(&[5; 100]),
            &mut ok,
        )
        .unwrap();
        let inactive_path = inactive.path.clone();
        drop(inactive);
        let unowned = directory.root().join("unowned.txt");
        std::fs::write(&unowned, b"preserve").unwrap();
        drop(expired_partial);
        prune(&directory.root()).unwrap();
        assert!(!expired_path.exists());
        assert!(inactive_path.exists());
        clear(&directory.root()).unwrap();
        assert!(!inactive_path.exists());
        assert!(active_path.exists());
        assert!(unowned.exists());
        drop(active);
        clear(&directory.root()).unwrap();
        assert!(!active_path.exists());
        assert_eq!(std::fs::read(unowned).unwrap(), b"preserve");
    }

    #[cfg(unix)]
    #[test]
    fn symlink_spool_and_owned_leaf_are_rejected() {
        use std::os::unix::fs::symlink;
        let directory = Directory::new();
        let target = directory.0.join("unowned");
        std::fs::create_dir(&target).unwrap();
        symlink(&target, directory.root()).unwrap();
        assert!(matches!(
            Partial::open(
                &directory.root(),
                &scope(11),
                descriptor(&[3; 100]),
                &mut ok
            ),
            Err("invalid_destination")
        ));
        assert!(std::fs::read_dir(&target).unwrap().next().is_none());
        std::fs::remove_file(directory.root()).unwrap();
        std::fs::create_dir(directory.root()).unwrap();
        let source = directory.source(b"preserve unowned ciphertext");
        let artifact = descriptor(&[3; 100]);
        let leaf = directory
            .root()
            .join(format!("{}-{}.part", scope(11).0, artifact.digest()));
        symlink(&source, leaf).unwrap();
        assert!(matches!(
            Partial::open(&directory.root(), &scope(11), artifact, &mut ok),
            Err("invalid_destination")
        ));
        assert_eq!(
            std::fs::read(source).unwrap(),
            b"preserve unowned ciphertext"
        );
    }
}
