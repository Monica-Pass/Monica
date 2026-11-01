//! Logical password replicas; local storage IDs and decryption keys never become peer identities.
#[path = "incremental.rs"]
pub(super) mod incremental;
use super::*;
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Nonce,
};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

const MAX_STATE: usize = 64 * 1024 * 1024;
const MAX_RECORDS: usize = 100_000;
const MAX_RECORD: usize = 1024 * 1024;
const MAX_BATCH_RECORDS: usize = 256;
const MAX_BATCH_BYTES: usize = 4 * 1024 * 1024;
const MAGIC: &[u8] = b"MONICA-DEVICE-REPLICA-1\0";
type Clock = BTreeMap<String, u64>;

#[derive(Clone, PartialEq, Serialize, Deserialize)]
struct Record {
    id: String,
    clock: Clock,
    origins: BTreeSet<String>,
    writer: String,
    deleted: bool,
    data: Value,
}

#[derive(Clone, PartialEq, Serialize, Deserialize)]
struct Binding {
    record_id: String,
    scope: String,
    hash: String,
    clock: Clock,
    guard: String,
    deleted: bool,
}

#[derive(Clone, Default, PartialEq, Serialize, Deserialize)]
struct State {
    version: u32,
    node: String,
    counter: u64,
    records: BTreeMap<String, Record>,
    aliases: BTreeMap<String, String>,
    bindings: BTreeMap<String, Binding>,
    applied: BTreeMap<String, Clock>,
    #[serde(default)]
    active_scopes: Option<BTreeSet<String>>,
    // Local receive decisions, never part of Wire or the advertised content revision.
    #[serde(default)]
    ignored_deletions: BTreeMap<String, Clock>,
}

#[derive(Clone, Deserialize)]
struct Local {
    local_id: String,
    #[serde(default)]
    global_id: String,
    scope: String,
    guard: String,
    deleted: bool,
    data: Value,
}

#[derive(Serialize, Deserialize)]
struct Wire {
    version: u32,
    records: BTreeMap<String, Record>,
    aliases: BTreeMap<String, String>,
}

fn passkey_identity(record: &Record) -> Option<(String, String)> {
    if record.data["entry_type"].as_str() != Some("passkey") {
        return None;
    }
    let payload = &record.data["payload"];
    let raw = payload["credential_id"].as_str()?.trim();
    let hex = raw.replace('-', "");
    let credential =
        if raw.len() == 36 && hex.len() == 32 && hex.bytes().all(|c| c.is_ascii_hexdigit()) {
            let bytes: Vec<u8> = (0..32)
                .step_by(2)
                .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
                .collect();
            URL_SAFE_NO_PAD.encode(bytes)
        } else {
            let encoded = if raw.get(..4).is_some_and(|p| p.eq_ignore_ascii_case("b64.")) {
                &raw[4..]
            } else {
                raw
            };
            let normalized = encoded
                .trim_end_matches('=')
                .replace('+', "-")
                .replace('/', "_");
            URL_SAFE_NO_PAD
                .decode(&normalized)
                .ok()
                .filter(|b| !b.is_empty())
                .map(|b| URL_SAFE_NO_PAD.encode(b))
                .unwrap_or_else(|| raw.to_owned())
        };
    Some((payload["rp_id"].as_str()?.to_ascii_lowercase(), credential))
}

/// Filter the wire copy, never the local scan: omission is not a deletion.
/// Include cached/conflict copies in the check so an older zero-counter copy
/// cannot leak the same credential after its live counterpart has been used.
fn export_wire(state: &State, skip_nonzero_passkeys: bool) -> Wire {
    let excluded: BTreeSet<_> = if skip_nonzero_passkeys {
        state
            .records
            .values()
            .filter(|r| {
                r.data["payload"]["sign_count"]
                    .as_u64()
                    .is_some_and(|n| n > 0)
            })
            .filter_map(passkey_identity)
            .collect()
    } else {
        BTreeSet::new()
    };
    let records: BTreeMap<_, _> = state
        .records
        .iter()
        .filter(|(_, r)| !passkey_identity(r).is_some_and(|key| excluded.contains(&key)))
        .map(|(id, r)| (id.clone(), r.clone()))
        .collect();
    let aliases = state
        .aliases
        .iter()
        .filter(|(id, _)| {
            !state
                .records
                .get(&resolve(state, id))
                .and_then(passkey_identity)
                .is_some_and(|key| excluded.contains(&key))
        })
        .map(|(id, target)| (id.clone(), target.clone()))
        .collect();
    Wire {
        version: 1,
        records,
        aliases: if skip_nonzero_passkeys {
            aliases
        } else {
            state.aliases.clone()
        },
    }
}

struct Scan {
    state: State,
    scopes: BTreeSet<String>,
    seen: BTreeSet<String>,
    locals: BTreeMap<String, Local>,
}
struct Replica {
    path: PathBuf,
    key: Zeroizing<[u8; 32]>,
    state: State,
    content_revision: String,
    scan: Option<Scan>,
    // Physical snapshots from the last complete scan, never sent to peers.
    locals: BTreeMap<String, Local>,
    physical_scan: bool,
}
static REPLICAS: OnceLock<Mutex<HashMap<u64, Replica>>> = OnceLock::new();

fn replicas() -> &'static Mutex<HashMap<u64, Replica>> {
    REPLICAS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn hash(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}
fn fingerprint(deleted: bool, data: &Value) -> Result<String> {
    if deleted && data.get("trashed").and_then(Value::as_bool) != Some(true) {
        return Ok(hash(b"deleted-password"));
    }
    let bytes = Zeroizing::new(serde_json::to_vec(&(deleted, data)).map_err(|_| "invalid_record")?);
    Ok(hash(&bytes))
}
fn uuid(bytes: &[u8]) -> String {
    let mut digest = Sha256::digest(bytes);
    digest[6] = (digest[6] & 15) | 64;
    digest[8] = (digest[8] & 63) | 128;
    let hex = format!("{:x}", digest);
    format!(
        "{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}
fn resolve(state: &State, id: &str) -> String {
    let mut id = id.to_owned();
    for _ in 0..128 {
        match state.aliases.get(&id) {
            Some(next) if next < &id => id = next.clone(),
            _ => break,
        }
    }
    id
}
fn dominates(left: &Clock, right: &Clock) -> bool {
    right
        .iter()
        .all(|(node, count)| left.get(node).copied().unwrap_or(0) >= *count)
}
fn union(left: &Clock, right: &Clock) -> Clock {
    let mut clock = left.clone();
    for (node, count) in right {
        clock
            .entry(node.clone())
            .and_modify(|old| *old = (*old).max(*count))
            .or_insert(*count);
    }
    clock
}
fn validate_record(record: &Record) -> Result<()> {
    if !peering::identifier(&record.id)
        || !peering::identifier(&record.writer)
        || record.clock.is_empty()
        || record.clock.len() > 64
        || record.origins.is_empty()
        || record.origins.len() > 64
        || record
            .clock
            .iter()
            .any(|(node, n)| !peering::identifier(node) || *n == 0)
        || record.origins.iter().any(|node| !peering::identifier(node))
        || !record.data.is_object()
        || serde_json::to_vec(&record.data)
            .map_err(|_| "invalid_record")?
            .len()
            > MAX_RECORD
    {
        return Err("invalid_record");
    }
    Ok(())
}

fn merge_record(state: &mut State, mut incoming: Record, depth: usize) -> Result<()> {
    if depth > 8 || state.records.len() >= MAX_RECORDS {
        return Err("replica_limit");
    }
    incoming.id = resolve(state, &incoming.id);
    let id = incoming.id.clone();
    let Some(current) = state.records.get(&id).cloned() else {
        state.records.insert(id, incoming);
        return Ok(());
    };
    let same = fingerprint(current.deleted, &current.data)?
        == fingerprint(incoming.deleted, &incoming.data)?;
    let incoming_newer = dominates(&incoming.clock, &current.clock);
    let current_newer = dominates(&current.clock, &incoming.clock);
    if current_newer && !incoming_newer {
        return Ok(());
    }
    if incoming_newer && !current_newer {
        state.records.insert(id, incoming);
        return Ok(());
    }
    if current.clock == incoming.clock && !same {
        return Err("conflicting_revision");
    }
    let clock = union(&current.clock, &incoming.clock);
    let origins: BTreeSet<_> = current.origins.union(&incoming.origins).cloned().collect();
    let (mut winner, loser) =
        if (incoming.deleted, &incoming.writer) > (current.deleted, &current.writer) {
            (incoming, current)
        } else {
            (current, incoming)
        };
    winner.clock = clock.clone();
    winner.origins = origins.clone();
    state.records.insert(id.clone(), winner);
    if !same && !loser.deleted {
        let mut copy = loser.clone();
        let content_hash = fingerprint(false, &copy.data)?;
        copy.id = uuid(format!("conflict:{id}:{content_hash}").as_bytes());
        copy.clock = clock;
        copy.origins = origins;
        if let Some(title) = copy.data.get_mut("title") {
            *title = Value::String(format!(
                "{} [Sync conflict]",
                title.as_str().unwrap_or("Password")
            ));
        }
        merge_record(state, copy, depth + 1)?;
    }
    Ok(())
}

fn attach_alias(state: &mut State, left: &str, right: &str) -> Result<()> {
    let left = resolve(state, left);
    let right = resolve(state, right);
    if left == right {
        return Ok(());
    }
    let (canonical, alias) = if left < right {
        (left, right)
    } else {
        (right, left)
    };
    state.aliases.insert(alias.clone(), canonical.clone());
    for target in state.aliases.values_mut() {
        if target == &alias {
            *target = canonical.clone();
        }
    }
    if let Some(mut record) = state.records.remove(&alias) {
        record.id = canonical.clone();
        merge_record(state, record, 0)?;
    }
    for binding in state.bindings.values_mut() {
        if binding.record_id == alias {
            binding.record_id = canonical.clone();
        }
    }
    if let Some(clock) = state.ignored_deletions.remove(&alias) {
        let ignored = state
            .ignored_deletions
            .entry(canonical.clone())
            .or_default();
        *ignored = union(ignored, &clock);
        if ignored.len() > 64 {
            return Err("replica_limit");
        }
    }
    if let Some(clock) = state.applied.remove(&alias) {
        state.applied.entry(canonical).or_insert(clock);
    }
    Ok(())
}

fn reconcile(state: &mut State, local: Local) -> Result<()> {
    if local.local_id.is_empty()
        || local.local_id.len() > 256
        || local.scope.is_empty()
        || local.scope.len() > 128
        || local.guard.len() > 256
        || !local.data.is_object()
        || serde_json::to_vec(&local.data)
            .map_err(|_| "invalid_record")?
            .len()
            > MAX_RECORD
    {
        return Err("invalid_record");
    }
    let content_hash = fingerprint(local.deleted, &local.data)?;
    let binding = state.bindings.get(&local.local_id).cloned();
    let id = if let Some(binding) = &binding {
        resolve(state, &binding.record_id)
    } else if !local.global_id.is_empty() {
        if !peering::identifier(&local.global_id) {
            return Err("invalid_record");
        }
        resolve(state, &local.global_id)
    } else {
        state
            .records
            .values()
            .find(|record| {
                !record.deleted
                    && !record.origins.contains(&state.node)
                    && !state
                        .bindings
                        .values()
                        .any(|binding| binding.record_id == record.id)
                    && fingerprint(false, &record.data).ok().as_deref()
                        == Some(content_hash.as_str())
            })
            .map(|record| record.id.clone())
            .unwrap_or_else(|| uuid(peering::random_id().as_bytes()))
    };
    if binding.as_ref().is_some_and(|binding| {
        binding.hash == content_hash
            && state.records.get(&id).is_some_and(|record| {
                binding.clock == record.clock
                    && state
                        .applied
                        .get(&id)
                        .is_some_and(|applied| dominates(applied, &record.clock))
            })
    }) {
        let known = state.bindings.get_mut(&local.local_id).unwrap();
        known.guard = local.guard;
        known.scope = local.scope;
        return Ok(());
    }
    let current = state.records.get(&id).cloned();
    if current.as_ref().is_some_and(|record| {
        fingerprint(record.deleted, &record.data).ok().as_deref() == Some(content_hash.as_str())
    }) {
        let record = current.unwrap();
        state.applied.insert(id.clone(), record.clock.clone());
        state.bindings.insert(
            local.local_id,
            Binding {
                record_id: id,
                scope: local.scope,
                hash: content_hash,
                clock: record.clock,
                guard: local.guard,
                deleted: local.deleted,
            },
        );
        return Ok(());
    }
    if let Some(binding) = &binding {
        if binding.hash == content_hash {
            let known = state.bindings.get_mut(&local.local_id).unwrap();
            known.guard = local.guard;
            known.scope = local.scope;
            return Ok(());
        }
    }
    state.counter = state.counter.checked_add(1).ok_or("replica_limit")?;
    let mut clock = binding
        .as_ref()
        .map(|binding| binding.clock.clone())
        .unwrap_or_default();
    clock.insert(state.node.clone(), state.counter);
    let record = Record {
        id: id.clone(),
        clock: clock.clone(),
        origins: current
            .map(|r| r.origins)
            .unwrap_or_else(|| BTreeSet::from([state.node.clone()])),
        writer: state.node.clone(),
        deleted: local.deleted,
        data: if local.deleted && local.data.get("trashed").and_then(Value::as_bool) != Some(true) {
            json!({})
        } else {
            local.data
        },
    };
    validate_record(&record)?;
    merge_record(state, record, 0)?;
    state.applied.insert(id.clone(), clock.clone());
    state.bindings.insert(
        local.local_id,
        Binding {
            record_id: id,
            scope: local.scope,
            hash: content_hash,
            clock,
            guard: local.guard,
            deleted: local.deleted,
        },
    );
    Ok(())
}

fn seal(plain: &[u8], key: &[u8; 32]) -> Result<Vec<u8>> {
    let mut nonce = [0; 12];
    rand::rngs::OsRng.fill_bytes(&mut nonce);
    let ciphertext = ChaCha20Poly1305::new(key.into())
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plain,
                aad: MAGIC,
            },
        )
        .map_err(|_| "replica_crypto_failed")?;
    let mut result = Vec::with_capacity(MAGIC.len() + 12 + ciphertext.len());
    result.extend_from_slice(MAGIC);
    result.extend_from_slice(&nonce);
    result.extend_from_slice(&ciphertext);
    Ok(result)
}
fn unseal(sealed: &[u8], key: &[u8; 32]) -> Result<Zeroizing<Vec<u8>>> {
    if sealed.len() < MAGIC.len() + 28
        || sealed.len() > MAX_STATE + 1024
        || !sealed.starts_with(MAGIC)
    {
        return Err("invalid_replica");
    }
    let n = MAGIC.len();
    ChaCha20Poly1305::new(key.into())
        .decrypt(
            Nonce::from_slice(&sealed[n..n + 12]),
            Payload {
                msg: &sealed[n + 12..],
                aad: MAGIC,
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| "replica_authentication_failed")
}
fn write(path: &Path, bytes: &[u8], replace: bool) -> Result<()> {
    let parent = path.parent().ok_or("invalid_path")?;
    std::fs::create_dir_all(parent).map_err(|_| "storage_directory_failed")?;
    let temporary = parent.join(format!(".replica-{}.tmp", peering::random_id()));
    let result = (|| {
        use std::io::Write;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| "storage_create_failed")?;
        file.write_all(bytes).map_err(|_| "storage_write_failed")?;
        file.sync_all().map_err(|_| "storage_flush_failed")?;
        drop(file);
        if replace {
            std::fs::rename(&temporary, path).map_err(|_| "storage_replace_failed")?;
        } else {
            crate::storage::publish_new(&temporary, path).map_err(|_| "storage_publish_failed")?;
        }
        Ok(())
    })();
    let _ = std::fs::remove_file(temporary);
    result
}
fn save(replica: &Replica) -> Result<()> {
    let plain = Zeroizing::new(serde_json::to_vec(&replica.state).map_err(|_| "invalid_replica")?);
    if plain.len() > MAX_STATE {
        return Err("replica_limit");
    }
    write(&replica.path, &seal(&plain, &replica.key)?, true)
}

fn commit(replica: &mut Replica, state: State) -> Result<()> {
    if replica.state == state {
        return Ok(());
    }
    let plain = Zeroizing::new(serde_json::to_vec(&state).map_err(|_| "invalid_replica")?);
    if plain.len() > MAX_STATE {
        return Err("replica_limit");
    }
    let content_revision = revision(&state)?;
    write(&replica.path, &seal(&plain, &replica.key)?, true)?;
    replica.state = state;
    replica.content_revision = content_revision;
    Ok(())
}
fn revision(state: &State) -> Result<String> {
    let plain = Zeroizing::new(
        serde_json::to_vec(&(&state.records, &state.aliases)).map_err(|_| "invalid_replica")?,
    );
    Ok(hash(&plain))
}

type BindingIndex<'a> = BTreeMap<String, Vec<(&'a String, &'a Binding)>>;

fn binding_index(state: &State) -> BindingIndex<'_> {
    let mut index = BindingIndex::new();
    for (id, binding) in &state.bindings {
        index
            .entry(resolve(state, &binding.record_id))
            .or_default()
            .push((id, binding));
    }
    index
}

fn needs_apply(state: &State, record: &Record) -> bool {
    !state
        .applied
        .get(&record.id)
        .is_some_and(|clock| dominates(clock, &record.clock))
}

fn binding_active(state: &State, binding: &Binding) -> bool {
    state
        .active_scopes
        .as_ref()
        .is_none_or(|scopes| scopes.contains(&binding.scope))
}

fn has_pending(state: &State) -> bool {
    let mut candidates = state
        .records
        .values()
        .filter(|record| needs_apply(state, record))
        .peekable();
    if candidates.peek().is_none() {
        return false;
    }
    let index = binding_index(state);
    candidates.any(|record| {
        index.get(&record.id).is_none_or(|bindings| {
            bindings
                .iter()
                .any(|(_, binding)| binding_active(state, binding))
        })
    })
}

fn finish_scan(mut scan: Scan) -> Result<State> {
    let live: BTreeSet<String> = scan
        .seen
        .iter()
        .filter_map(|id| scan.state.bindings.get(id))
        .filter(|binding| !binding.deleted)
        .map(|binding| resolve(&scan.state, &binding.record_id))
        .collect();
    for (local_id, binding) in scan.state.bindings.clone() {
        if !scan.scopes.contains(&binding.scope)
            || scan.seen.contains(&local_id)
            || (binding.deleted
                && scan
                    .state
                    .records
                    .get(&resolve(&scan.state, &binding.record_id))
                    .is_none_or(|r| r.data.get("trashed").and_then(Value::as_bool) != Some(true)))
        {
            continue;
        }
        let record_id = resolve(&scan.state, &binding.record_id);
        // A move or projection rebuild can replace the storage ID in the same scan.
        // Retire the old binding, never emit a deletion for a record still present.
        if live.contains(&record_id) {
            scan.state.bindings.remove(&local_id);
            continue;
        }
        if let Some(record) = scan.state.records.get(&record_id).cloned() {
            reconcile(
                &mut scan.state,
                Local {
                    local_id,
                    global_id: record.id,
                    scope: binding.scope,
                    guard: String::new(),
                    deleted: true,
                    data: json!({}),
                },
            )?;
        }
    }
    scan.state.active_scopes = Some(scan.scopes);
    Ok(scan.state)
}

fn acknowledge(state: &mut State, value: &Value) -> Result<()> {
    let id = resolve(state, string(value, "record_id")?);
    let clock: Clock =
        serde_json::from_value(value.get("clock").cloned().ok_or("invalid_request")?)
            .map_err(|_| "invalid_request")?;
    let record = state.records.get(&id).ok_or("invalid_record")?.clone();
    if !dominates(&record.clock, &clock) {
        return Err("invalid_revision");
    }
    if state
        .applied
        .get(&id)
        .is_some_and(|known| known != &clock && dominates(known, &clock))
    {
        return Ok(());
    }
    if let Some(local_id) = value
        .get("local_id")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())
    {
        let hash = if let Some(data) = value.get("data") {
            fingerprint(record.deleted, data)?
        } else {
            string(value, "hash")?.to_owned()
        };
        let scope = string(value, "scope")?.to_owned();
        let guard = value
            .get("guard")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        if local_id.len() > 256 || scope.len() > 128 || guard.len() > 256 {
            return Err("invalid_record");
        }
        state
            .bindings
            .retain(|local, binding| local == local_id || binding.record_id != id);
        state.bindings.insert(
            local_id.into(),
            Binding {
                record_id: id.clone(),
                scope,
                hash,
                clock: clock.clone(),
                guard,
                deleted: record.deleted,
            },
        );
    }
    state.applied.insert(id, clock);
    Ok(())
}

fn destructive(record: &Record) -> bool {
    record.deleted || record.data.get("trashed").and_then(Value::as_bool) == Some(true)
}

fn review_count(state: &State) -> usize {
    let index = binding_index(state);
    state
        .records
        .values()
        .filter(|record| {
            destructive(record)
                && needs_apply(state, record)
                && index
                    .get(&record.id)
                    .is_none_or(|items| items.iter().any(|(_, b)| binding_active(state, b)))
        })
        .count()
}

fn ignore_deletion(state: &mut State, record: &Record) -> Result<()> {
    if state.ignored_deletions.len() >= MAX_RECORDS
        && !state.ignored_deletions.contains_key(&record.id)
    {
        return Err("replica_limit");
    }
    let ignored = state
        .ignored_deletions
        .entry(record.id.clone())
        .or_default();
    *ignored = union(ignored, &record.clock);
    if ignored.len() > 64 {
        return Err("replica_limit");
    }
    Ok(())
}

// Keeping a physical local item is a real local decision, not an acknowledgement
// pretending that the item was deleted. This also repairs tombstones left pending
// by older interrupted receives. Its new clock dominates the rejected deletion.
fn retain_pending(state: &mut State, locals: &BTreeMap<String, Local>) -> Result<()> {
    let live: Vec<_> = locals
        .values()
        .filter(|local| {
            !local.deleted && local.data.get("trashed").and_then(Value::as_bool) != Some(true)
        })
        .cloned()
        .collect();
    for local in live {
        let Some(binding) = state.bindings.get(&local.local_id).cloned() else {
            continue;
        };
        if !binding_active(state, &binding) {
            continue;
        }
        let id = resolve(state, &binding.record_id);
        let Some(mut record) = state.records.get(&id).cloned() else {
            continue;
        };
        if !destructive(&record) || !needs_apply(state, &record) {
            continue;
        }
        state.counter = state
            .counter
            .max(record.clock.get(&state.node).copied().unwrap_or(0))
            .checked_add(1)
            .ok_or("replica_limit")?;
        record.clock.insert(state.node.clone(), state.counter);
        record.writer = state.node.clone();
        record.deleted = false;
        record.data = local.data.clone();
        validate_record(&record)?;
        state.applied.insert(id.clone(), record.clock.clone());
        state.bindings.insert(
            local.local_id,
            Binding {
                record_id: id.clone(),
                scope: local.scope,
                guard: local.guard,
                hash: fingerprint(false, &local.data)?,
                clock: record.clock.clone(),
                deleted: false,
            },
        );
        state.records.insert(id, record);
    }
    // A complete physical scan proves that remaining active tombstones have no
    // live local item to retain. Do not import cached unbound trash from an old
    // interrupted receive, and never mark a retained live item as deleted.
    let index = binding_index(state);
    let absent: Vec<_> = state
        .records
        .values()
        .filter(|r| {
            destructive(r)
                && needs_apply(state, r)
                && index
                    .get(&r.id)
                    .is_none_or(|bs| bs.iter().any(|(_, b)| binding_active(state, b)))
        })
        .map(|r| (r.clone(), index.contains_key(&r.id)))
        .collect();
    for (record, bound) in absent {
        ignore_deletion(state, &record)?;
        if bound {
            state.applied.insert(record.id, record.clock);
        } else {
            state.records.remove(&record.id);
            state.applied.remove(&record.id);
        }
    }
    Ok(())
}

fn incoming_state(baseline: &State, value: &Value, omit: bool) -> Result<State> {
    let path = Path::new(string(value, "path")?);
    if std::fs::metadata(path)
        .map_err(|_| "storage_read_failed")?
        .len()
        > (MAX_STATE + 1024) as u64
    {
        return Err("replica_limit");
    }
    let bytes = std::fs::read(path).map_err(|_| "storage_read_failed")?;
    let key = peering::secret_key(string(value, "content_key")?)?;
    let wire: Wire =
        serde_json::from_slice(&unseal(&bytes, &key)?).map_err(|_| "invalid_replica")?;
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
    let mut state = baseline.clone();
    for (alias, canonical) in &wire.aliases {
        if !peering::identifier(alias) || !peering::identifier(canonical) || alias <= canonical {
            return Err("invalid_alias");
        }
        attach_alias(&mut state, alias, canonical)?;
    }
    for mut record in wire.records.into_values() {
        record.id = resolve(&state, &record.id);
        state.counter = state
            .counter
            .max(record.clock.get(&state.node).copied().unwrap_or(0));
        if destructive(&record)
            && (omit
                || state
                    .ignored_deletions
                    .get(&record.id)
                    .is_some_and(|ignored| dominates(ignored, &record.clock)))
        {
            if omit {
                ignore_deletion(&mut state, &record)?;
            }
            continue;
        }
        // Root metadata belongs to its source vault, even when two vaults have the same tags.
        if !state.records.contains_key(&record.id)
            && !record.deleted
            && record.data.get("entry_type").and_then(Value::as_str) != Some("vault-metadata")
        {
            let f = fingerprint(false, &record.data)?;
            let duplicate = state
                .records
                .values()
                .find(|existing| {
                    !existing.deleted
                        && existing.origins.is_disjoint(&record.origins)
                        && fingerprint(false, &existing.data).ok().as_deref() == Some(f.as_str())
                })
                .map(|existing| existing.id.clone());
            if let Some(duplicate) = duplicate {
                attach_alias(&mut state, &record.id, &duplicate)?;
                record.id = resolve(&state, &record.id);
            }
        }
        state.counter = state
            .counter
            .max(record.clock.get(&state.node).copied().unwrap_or(0));
        merge_record(&mut state, record, 0)?;
    }
    Ok(state)
}

pub(super) fn request(value: &Value) -> Result<Value> {
    let operation = string(value, "operation")?;
    let mut sessions = replicas().lock().map_err(|_| "unavailable")?;
    if operation == "replica_open" {
        if sessions.len() >= 4 {
            return Err("too_many_replicas");
        }
        let path = PathBuf::from(string(value, "path")?);
        let key = peering::secret_key(string(value, "key")?)?;
        let node = string(value, "device_id")?;
        if !peering::identifier(node) {
            return Err("invalid_device");
        }
        let state = if path.exists() {
            if std::fs::metadata(&path)
                .map_err(|_| "storage_read_failed")?
                .len()
                > (MAX_STATE + 1024) as u64
            {
                return Err("replica_limit");
            }
            let bytes = std::fs::read(&path).map_err(|_| "storage_read_failed")?;
            serde_json::from_slice::<State>(&unseal(&bytes, &key)?)
                .map_err(|_| "invalid_replica")?
        } else {
            State {
                version: 1,
                node: node.into(),
                ..State::default()
            }
        };
        if state.version != 1 || state.node != node {
            return Err("different_replica");
        }
        let handle = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let replica = Replica {
            path,
            key,
            content_revision: revision(&state)?,
            state,
            scan: None,
            locals: BTreeMap::new(),
            physical_scan: false,
        };
        save(&replica)?;
        sessions.insert(handle, replica);
        return Ok(json!({"handle":handle}));
    }
    let handle = value
        .get("handle")
        .and_then(Value::as_u64)
        .ok_or("invalid_request")?;
    if operation == "replica_close" {
        sessions.remove(&handle);
        return Ok(json!({}));
    }
    let replica = sessions.get_mut(&handle).ok_or("replica_closed")?;
    match operation {
        "replica_scan_begin" => {
            let scopes: BTreeSet<String> =
                serde_json::from_value(value.get("scopes").cloned().ok_or("invalid_request")?)
                    .map_err(|_| "invalid_request")?;
            if scopes.len() > 256
                || scopes
                    .iter()
                    .any(|scope| scope.is_empty() || scope.len() > 128)
            {
                return Err("invalid_scope");
            }
            replica.physical_scan = false;
            replica.scan = Some(Scan {
                state: replica.state.clone(),
                scopes,
                seen: BTreeSet::new(),
                locals: BTreeMap::new(),
            });
            Ok(json!({}))
        }
        "replica_scan_batch" => {
            let entries: Vec<Local> =
                serde_json::from_value(value.get("entries").cloned().ok_or("invalid_request")?)
                    .map_err(|_| "invalid_record")?;
            if entries.len() > MAX_BATCH_RECORDS {
                return Err("replica_limit");
            }
            let scan = replica.scan.as_mut().ok_or("scan_not_started")?;
            let mut staged = scan.state.clone();
            let mut seen = scan.seen.clone();
            let mut locals = BTreeMap::new();
            for local in entries {
                if !scan.scopes.contains(&local.scope) || !seen.insert(local.local_id.clone()) {
                    return Err("invalid_scope");
                }
                reconcile(&mut staged, local.clone())?;
                locals.insert(local.local_id.clone(), local);
            }
            scan.state = staged;
            scan.seen = seen;
            scan.locals.extend(locals);
            Ok(json!({}))
        }
        "replica_scan_end" => {
            let scan = replica.scan.take().ok_or("scan_not_started")?;
            let locals = scan.locals.clone();
            commit(replica, finish_scan(scan)?)?;
            replica.locals = locals;
            replica.physical_scan = true;
            Ok(json!({"revision":replica.content_revision}))
        }
        "replica_revision" => {
            Ok(json!({"revision":replica.content_revision,"pending":has_pending(&replica.state)}))
        }
        "replica_local" => {
            let ids = value
                .get("local_ids")
                .and_then(Value::as_array)
                .ok_or("invalid_request")?;
            if ids.len() > MAX_BATCH_RECORDS {
                return Err("replica_limit");
            }
            let mut items = Vec::new();
            for id in ids {
                let id = id.as_str().ok_or("invalid_request")?;
                if let Some(binding) = replica.state.bindings.get(id) {
                    let global = resolve(&replica.state, &binding.record_id);
                    if let Some(record) = replica.state.records.get(&global) {
                        let mut item =
                            json!({"local_id":id,"global_id":global,"deleted":record.deleted});
                        if value.get("include_data").and_then(Value::as_bool) == Some(true) {
                            item["data"] = record.data.clone();
                        }
                        items.push(item);
                    }
                }
            }
            Ok(json!({"items":items}))
        }
        "replica_resolve" => {
            let ids = value
                .get("ids")
                .and_then(Value::as_array)
                .ok_or("invalid_request")?;
            if ids.len() > MAX_BATCH_RECORDS {
                return Err("replica_limit");
            }
            let scope = value.get("scope").and_then(Value::as_str);
            let index = binding_index(&replica.state);
            let mut items = Vec::new();
            for id in ids {
                let id = id.as_str().ok_or("invalid_request")?;
                if !peering::identifier(id) {
                    return Err("invalid_request");
                }
                let global = resolve(&replica.state, id);
                let binding = index.get(&global).and_then(|items| {
                    items.iter().copied().find(|(_, b)| {
                        binding_active(&replica.state, b)
                            && scope.is_none_or(|scope| b.scope == scope)
                    })
                });
                let mut item = json!({"id":id,"global_id":global,"local_id":binding.map(|(id,_)|id),"scope":binding.map(|(_,b)|&b.scope)});
                if value.get("include_data").and_then(Value::as_bool) == Some(true) {
                    if let Some(record) = replica.state.records.get(&global) {
                        item["data"] = record.data.clone();
                        item["deleted"] = json!(record.deleted);
                    }
                }
                items.push(item);
            }
            Ok(json!({"items":items}))
        }
        "replica_rebind" => {
            let items = value
                .get("items")
                .and_then(Value::as_array)
                .ok_or("invalid_request")?;
            if items.len() > MAX_BATCH_RECORDS {
                return Err("replica_limit");
            }
            let mut state = replica.state.clone();
            for item in items {
                let old = string(item, "old_local_id")?;
                let new = string(item, "new_local_id")?;
                let scope = string(item, "scope")?;
                if new.len() > 256 || new.is_empty() {
                    return Err("invalid_record");
                }
                if let Some(binding) = state
                    .bindings
                    .get(old)
                    .filter(|b| b.scope == scope)
                    .cloned()
                {
                    if !state.bindings.contains_key(new) {
                        state.bindings.remove(old);
                        state.bindings.insert(new.into(), binding);
                    }
                }
            }
            commit(replica, state)?;
            Ok(json!({}))
        }
        "replica_export" => {
            let wire = export_wire(
                &replica.state,
                value["skip_nonzero_passkeys"].as_bool().unwrap_or(false),
            );
            let asset_ids: BTreeSet<_> = wire
                .records
                .values()
                .flat_map(|r| r.data["assets"].as_array().into_iter().flatten())
                .filter_map(|a| a["id"].as_str())
                .collect();
            let plain = Zeroizing::new(serde_json::to_vec(&wire).map_err(|_| "invalid_replica")?);
            if plain.len() > MAX_STATE {
                return Err("replica_limit");
            }
            let content_key = peering::random_key();
            let key = peering::secret_key(&content_key)?;
            write(
                Path::new(string(value, "path")?),
                &seal(&plain, &key)?,
                false,
            )?;
            Ok(
                json!({"content_key":content_key,"revision":replica.content_revision,"pending":has_pending(&replica.state),"asset_ids":asset_ids}),
            )
        }
        "replica_merge_preview" | "replica_merge" => {
            let preview = incoming_state(&replica.state, value, false)?;
            let review_revision = hash(&Zeroizing::new(
                serde_json::to_vec(&preview).map_err(|_| "invalid_replica")?,
            ));
            let deletions = review_count(&preview);
            if operation == "replica_merge_preview" {
                return Ok(json!({"deletions":deletions, "review_revision":review_revision}));
            }
            if value
                .get("review_revision")
                .and_then(Value::as_str)
                .is_some_and(|r| r != review_revision)
            {
                return Err("review_changed");
            }
            let omit = match value.get("deletion_policy").and_then(Value::as_str) {
                None | Some("include") => false,
                Some("omit") => true,
                _ => return Err("invalid_request"),
            };
            if omit && !replica.physical_scan {
                return Err("scan_required");
            }
            let mut state = if omit {
                incoming_state(&replica.state, value, true)?
            } else {
                preview
            };
            if omit {
                retain_pending(&mut state, &replica.locals)?;
            }
            commit(replica, state)?;
            Ok(json!({"revision":replica.content_revision}))
        }
        "replica_pending" => {
            let mut pending = Vec::new();
            let mut bytes = 0;
            let mut next_cursor = None;
            let after = value.get("after").and_then(Value::as_str).unwrap_or("");
            if !after.is_empty() && !peering::identifier(after) {
                return Err("invalid_request");
            }
            let index = binding_index(&replica.state);
            let total = replica
                .state
                .records
                .values()
                .filter(|record| {
                    needs_apply(&replica.state, record)
                        && index.get(&record.id).is_none_or(|items| {
                            items.iter().any(|(_, b)| binding_active(&replica.state, b))
                        })
                })
                .count();
            let mut records: Vec<_> = replica.state.records.values().collect();
            if value.get("dependency_order").and_then(Value::as_bool) == Some(true) {
                records.sort_by_cached_key(|record| {
                    (
                        if record.data.get("entry_type").and_then(Value::as_str) == Some("folder") {
                            if record.deleted {
                                2
                            } else {
                                0
                            }
                        } else {
                            1
                        },
                        record.id.clone(),
                    )
                });
            }
            for record in records {
                if record.id.as_str() <= after || !needs_apply(&replica.state, record) {
                    continue;
                }
                let bindings = index.get(&record.id);
                let binding = bindings.and_then(|items| {
                    items
                        .iter()
                        .copied()
                        .find(|(_, binding)| binding_active(&replica.state, binding))
                });
                if bindings.is_some() && binding.is_none() {
                    continue; // An unavailable source is not a deletion or a new local copy.
                }
                let item = json!({"record":record,"local_id":binding.map(|(id,_)|id),"scope":binding.map(|(_,b)|&b.scope),"expected_guard":binding.map(|(_,b)|&b.guard)});
                bytes += serde_json::to_vec(&item)
                    .map_err(|_| "invalid_record")?
                    .len();
                if bytes > MAX_BATCH_BYTES || pending.len() >= MAX_BATCH_RECORDS {
                    next_cursor = pending
                        .last()
                        .and_then(|item: &Value| item["record"]["id"].as_str())
                        .map(str::to_owned);
                    break;
                }
                pending.push(item);
            }
            Ok(json!({"items":pending,"next_cursor":next_cursor,"total":total}))
        }
        "replica_applied" => {
            let mut state = replica.state.clone();
            acknowledge(&mut state, value)?;
            commit(replica, state)?;
            Ok(json!({}))
        }
        "replica_applied_batch" => {
            let items = value
                .get("items")
                .and_then(Value::as_array)
                .ok_or("invalid_request")?;
            if items.len() > MAX_BATCH_RECORDS {
                return Err("replica_limit");
            }
            let mut state = replica.state.clone();
            for item in items {
                acknowledge(&mut state, item)?;
            }
            commit(replica, state)?;
            Ok(json!({}))
        }
        _ => Err("invalid_request"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state(node: &str) -> State {
        State {
            version: 1,
            node: node.into(),
            ..State::default()
        }
    }
    fn local(id: &str, global: &str, password: &str) -> Local {
        Local {
            local_id: id.into(),
            global_id: global.into(),
            scope: "passwords".into(),
            guard: "version-1".into(),
            deleted: false,
            data: json!({"title":"Synthetic login","password":password}),
        }
    }

    fn receive_file(wire: Wire) -> (PathBuf, Value) {
        let root = std::env::temp_dir().join(format!("monica-review-{}", peering::random_id()));
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join("changes.mdbxsync");
        let content_key = peering::random_key();
        let key = peering::secret_key(&content_key).unwrap();
        std::fs::write(
            &path,
            seal(&serde_json::to_vec(&wire).unwrap(), &key).unwrap(),
        )
        .unwrap();
        (
            root,
            json!({"path":path.to_str().unwrap(),"content_key":content_key}),
        )
    }

    #[test]
    fn receive_preview_does_not_commit_and_omit_keeps_content_while_accepting_updates() {
        for soft in [false, true] {
            let mut target = state("phone");
            reconcile(&mut target, local("one", "one", "keep me")).unwrap();
            reconcile(&mut target, local("two", "two", "old")).unwrap();
            let before = serde_json::to_vec(&target).unwrap();
            let mut source = target.clone();
            source.node = "browser".into();
            let mut deleted = source.records["one"].clone();
            deleted.clock.insert("browser".into(), 1);
            deleted.writer = "browser".into();
            deleted.deleted = true;
            deleted.data = if soft {
                json!({"title":"Trashed", "trashed":true})
            } else {
                json!({})
            };
            merge_record(&mut source, deleted, 0).unwrap();
            let mut update = source.records["two"].clone();
            update.clock.insert("browser".into(), 2);
            update.writer = "browser".into();
            update.data["password"] = json!("updated");
            merge_record(&mut source, update, 0).unwrap();
            reconcile(&mut source, local("three", "three", "new")).unwrap();
            let (root, request) = receive_file(export_wire(&source, false));
            let preview = incoming_state(&target, &request, false).unwrap();
            assert_eq!(review_count(&preview), 1);
            assert_eq!(serde_json::to_vec(&target).unwrap(), before);
            let mut kept = incoming_state(&target, &request, true).unwrap();
            retain_pending(
                &mut kept,
                &BTreeMap::from([("one".into(), local("one", "one", "keep me"))]),
            )
            .unwrap();
            assert_eq!(review_count(&kept), 0);
            let reopened: State =
                serde_json::from_slice(&serde_json::to_vec(&kept).unwrap()).unwrap();
            assert_eq!(
                review_count(&incoming_state(&reopened, &request, false).unwrap()),
                0,
                "the same deletion must not prompt again after keeping and reopening"
            );
            assert!(!serde_json::to_value(export_wire(&kept, false))
                .unwrap()
                .as_object()
                .unwrap()
                .contains_key("ignored_deletions"));
            let mut later = source.records["one"].clone();
            later.clock.insert("browser".into(), 100);
            source.records.insert("one".into(), later);
            let (later_root, later_request) = receive_file(export_wire(&source, false));
            assert_eq!(
                review_count(&incoming_state(&reopened, &later_request, false).unwrap()),
                1,
                "a newer deletion must ask again"
            );
            std::fs::remove_dir_all(later_root).unwrap();
            assert!(!kept.records["one"].deleted);
            assert_eq!(kept.records["one"].data["password"], "keep me");
            assert_eq!(kept.records["two"].data["password"], "updated");
            assert_eq!(kept.records["three"].data["password"], "new");
            assert!(preview.records["one"].deleted);
            std::fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn retain_repairs_old_pending_tombstones_without_fake_delete_acknowledgements() {
        let mut s = state("phone");
        let physical = local("local", "entry", "preserved");
        reconcile(&mut s, physical.clone()).unwrap();
        let mut deletion = s.records["entry"].clone();
        deletion.clock.insert("browser".into(), 9);
        deletion.writer = "browser".into();
        deletion.deleted = true;
        deletion.data = json!({});
        merge_record(&mut s, deletion.clone(), 0).unwrap();
        assert_eq!(review_count(&s), 1);
        retain_pending(&mut s, &BTreeMap::from([("local".into(), physical)])).unwrap();
        assert_eq!(review_count(&s), 0);
        assert!(!s.bindings["local"].deleted);
        assert_eq!(s.records["entry"].data["password"], "preserved");
        assert!(dominates(&s.records["entry"].clock, &deletion.clock));
        assert!(!needs_apply(&s, &s.records["entry"]));
        let restored_revision = revision(&s).unwrap();
        merge_record(&mut s, deletion, 0).unwrap();
        assert_eq!(revision(&s).unwrap(), restored_revision);
        assert!(!export_wire(&s, false).records["entry"].deleted);
    }

    #[test]
    fn omit_never_imports_unbound_trash_and_preview_authenticates_before_any_changes() {
        let mut source = state("browser");
        let mut item = local("deleted", "deleted", "trash");
        item.deleted = true;
        item.data["trashed"] = json!(true);
        reconcile(&mut source, item).unwrap();
        let target = state("phone");
        let (root, request) = receive_file(export_wire(&source, false));
        assert_eq!(
            review_count(&incoming_state(&target, &request, false).unwrap()),
            1
        );
        assert!(incoming_state(&target, &request, true)
            .unwrap()
            .records
            .is_empty());
        std::fs::write(request["path"].as_str().unwrap(), b"tampered").unwrap();
        assert!(incoming_state(&target, &request, false).is_err());
        assert!(target.records.is_empty());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn counter_filter_excludes_cached_copies_aliases_and_assets_without_mutating_replica() {
        let mut s = state("source");
        let mut key = local("local-key", "key", "unused");
        key.data = json!({"title":"Passkey", "entry_type":"passkey", "payload":{
            "credential_id":"00000000-0000-0000-0000-000000000001", "rp_id":"example.test", "sign_count":0},
            "assets":[{"id":"private-asset"}]});
        reconcile(&mut s, key).unwrap();
        let mut cached = s.records["key"].clone();
        cached.id = "old-copy".into();
        cached.data["title"] = json!("[Sync conflict] Passkey");
        cached.data["payload"]["credential_id"] = json!("b64.AAAAAAAAAAAAAAAAAAAAAQ");
        cached.data["payload"]["rp_id"] = json!("EXAMPLE.TEST");
        s.records.insert(cached.id.clone(), cached);
        s.aliases.insert("z-key-alias".into(), "key".into());
        reconcile(&mut s, local("local-password", "password", "synthetic")).unwrap();
        assert_eq!(export_wire(&s, true).records.len(), 3);
        s.records.get_mut("key").unwrap().data["payload"]["sign_count"] = json!(7);
        let before = serde_json::to_vec(&s).unwrap();
        let filtered = export_wire(&s, true);
        assert_eq!(
            filtered.records.keys().cloned().collect::<Vec<_>>(),
            vec!["password"]
        );
        assert!(filtered.aliases.is_empty());
        assert!(!filtered.records.values().any(|r| r.deleted));
        assert_eq!(serde_json::to_vec(&s).unwrap(), before);
        assert_eq!(export_wire(&s, false).records.len(), 3);
        assert_eq!(export_wire(&s, false).aliases.len(), 1);
        // Persisted caches have the same policy after restarting the process.
        let restored: State = serde_json::from_slice(&before).unwrap();
        assert_eq!(export_wire(&restored, true).records.len(), 1);
    }

    #[test]
    fn repeating_an_identical_scan_does_not_rewrite_the_encrypted_journal() {
        let root =
            std::env::temp_dir().join(format!("monica-replica-noop-{}", peering::random_id()));
        let path = root.join("state.replica");
        let opened = request(&json!({"operation":"replica_open", "path":path,
            "device_id":"source", "key":peering::random_key()}))
        .unwrap();
        let handle = opened["handle"].as_u64().unwrap();
        let scan = || {
            request(
                &json!({"operation":"replica_scan_begin", "handle":handle, "scopes":["passwords"]}),
            )
            .unwrap();
            request(&json!({"operation":"replica_scan_batch", "handle":handle,
                "entries":[{"local_id":"local", "global_id":"entry", "scope":"passwords", "guard":"same-version",
                    "deleted":false, "data":{"title":"Synthetic", "password":"synthetic"}}]})).unwrap();
            request(&json!({"operation":"replica_scan_end", "handle":handle})).unwrap();
        };
        scan();
        let first = std::fs::read(&path).unwrap();
        scan();
        let second = std::fs::read(&path).unwrap();
        request(&json!({"operation":"replica_close", "handle":handle})).unwrap();
        std::fs::remove_dir_all(root).unwrap();
        assert!(
            first == second,
            "An unchanged scan must not encrypt and flush another journal"
        );
    }

    #[test]
    fn pending_pages_cover_all_records_without_repeating_deferred_dependencies() {
        let root =
            std::env::temp_dir().join(format!("monica-replica-pages-{}", peering::random_id()));
        std::fs::create_dir_all(&root).unwrap();
        let mut source = state("source");
        for index in 0..300 {
            reconcile(
                &mut source,
                local(
                    &format!("local-{index:03}"),
                    &format!("entry-{index:03}"),
                    "synthetic",
                ),
            )
            .unwrap();
        }
        let encoded_key = peering::random_key();
        let key = peering::secret_key(&encoded_key).unwrap();
        let wire = Wire {
            version: 1,
            records: source.records,
            aliases: source.aliases,
        };
        let incoming = root.join("changes.mdbxsync");
        std::fs::write(
            &incoming,
            seal(&serde_json::to_vec(&wire).unwrap(), &key).unwrap(),
        )
        .unwrap();
        let opened = request(
            &json!({"operation":"replica_open", "path":root.join("target.replica"),
            "device_id":"target", "key":peering::random_key()}),
        )
        .unwrap();
        let handle = opened["handle"].as_u64().unwrap();
        request(&json!({"operation":"replica_merge", "handle":handle, "path":incoming, "content_key":encoded_key})).unwrap();
        let first = request(&json!({"operation":"replica_pending", "handle":handle})).unwrap();
        let second = request(
            &json!({"operation":"replica_pending", "handle":handle, "after":first["next_cursor"]}),
        )
        .unwrap();
        request(&json!({"operation":"replica_close", "handle":handle})).unwrap();
        std::fs::remove_dir_all(root).unwrap();
        let first_items = first["items"].as_array().unwrap();
        let second_items = second["items"].as_array().unwrap();
        assert_eq!(first_items.len(), 256);
        assert_eq!(second_items.len(), 44);
        assert!(second["next_cursor"].is_null());
        let ids: BTreeSet<_> = first_items
            .iter()
            .chain(second_items)
            .map(|item| item["record"]["id"].as_str().unwrap())
            .collect();
        assert_eq!(ids.len(), 300);
    }

    #[test]
    fn reading_unchanged_local_passwords_does_not_create_new_revisions() {
        let mut s = state("desktop");
        reconcile(&mut s, local("local1", "entry1", "synthetic-secret")).unwrap();
        let before = revision(&s).unwrap();
        reconcile(&mut s, local("local1", "entry1", "synthetic-secret")).unwrap();
        assert_eq!(revision(&s).unwrap(), before);
        assert_eq!(s.counter, 1);
    }

    #[test]
    fn a_remote_edit_does_not_look_like_a_new_local_edit_before_it_is_applied() {
        let mut s = state("desktop");
        reconcile(&mut s, local("local1", "entry1", "old")).unwrap();
        let mut remote = s.records["entry1"].clone();
        remote.clock.insert("phone".into(), 1);
        remote.writer = "phone".into();
        remote.data["password"] = json!("remote-edit");
        merge_record(&mut s, remote, 0).unwrap();
        reconcile(&mut s, local("local1", "entry1", "old")).unwrap();
        assert_eq!(s.records["entry1"].data["password"], "remote-edit");
        assert!(!dominates(&s.applied["entry1"], &s.records["entry1"].clock));
    }

    #[test]
    fn concurrent_password_edits_keep_both_values_and_converge() {
        let mut a = state("desktop");
        reconcile(&mut a, local("a1", "entry1", "original")).unwrap();
        let mut b = state("phone");
        merge_record(&mut b, a.records["entry1"].clone(), 0).unwrap();
        reconcile(&mut b, local("b1", "entry1", "original")).unwrap();
        reconcile(&mut a, local("a1", "entry1", "desktop-edit")).unwrap();
        reconcile(&mut b, local("b1", "entry1", "phone-edit")).unwrap();
        let from_a = a.records["entry1"].clone();
        let from_b = b.records["entry1"].clone();
        merge_record(&mut a, from_b, 0).unwrap();
        merge_record(&mut b, from_a, 0).unwrap();
        assert_eq!(revision(&a).unwrap(), revision(&b).unwrap());
        assert_eq!(a.records.len(), 2);
        let values: BTreeSet<_> = a
            .records
            .values()
            .map(|r| r.data["password"].as_str().unwrap())
            .collect();
        assert_eq!(values, BTreeSet::from(["desktop-edit", "phone-edit"]));
    }

    #[test]
    fn stale_snapshots_cannot_resurrect_a_deleted_password() {
        let mut s = state("desktop");
        reconcile(&mut s, local("a1", "entry1", "old")).unwrap();
        let old = s.records["entry1"].clone();
        let mut removed = local("a1", "entry1", "old");
        removed.deleted = true;
        reconcile(&mut s, removed).unwrap();
        merge_record(&mut s, old, 0).unwrap();
        assert!(s.records["entry1"].deleted);
    }

    #[test]
    fn local_changes_during_a_remote_apply_are_preserved_as_conflicts() {
        let mut s = state("desktop");
        reconcile(&mut s, local("a1", "entry1", "old")).unwrap();
        let mut remote = s.records["entry1"].clone();
        remote.clock.insert("phone".into(), 1);
        remote.writer = "phone".into();
        remote.data["password"] = json!("remote-new");
        merge_record(&mut s, remote, 0).unwrap();
        reconcile(&mut s, local("a1", "entry1", "local-new")).unwrap();
        let values: BTreeSet<_> = s
            .records
            .values()
            .map(|r| r.data["password"].as_str().unwrap())
            .collect();
        assert_eq!(values, BTreeSet::from(["local-new", "remote-new"]));
    }

    #[test]
    fn snapshots_are_encrypted_and_authenticated_with_independent_local_keys() {
        let key = peering::secret_key(&peering::random_key()).unwrap();
        let other = peering::secret_key(&peering::random_key()).unwrap();
        let plain = b"synthetic-password-that-must-not-be-written-in-plaintext";
        let mut encrypted = seal(plain, &key).unwrap();
        assert!(!encrypted.windows(plain.len()).any(|w| w == plain));
        assert_eq!(&**unseal(&encrypted, &key).unwrap(), plain);
        assert!(unseal(&encrypted, &other).is_err());
        let last = encrypted.len() - 1;
        encrypted[last] ^= 1;
        assert!(unseal(&encrypted, &key).is_err());
    }

    #[test]
    fn moving_a_record_to_a_new_local_id_does_not_publish_a_tombstone() {
        let mut s = state("phone");
        reconcile(&mut s, local("room1", "entry1", "secret")).unwrap();
        let before = revision(&s).unwrap();
        reconcile(&mut s, local("native1", "entry1", "secret")).unwrap();
        let scan = Scan {
            state: s,
            scopes: BTreeSet::from(["passwords".into()]),
            locals: BTreeMap::new(),
            seen: BTreeSet::from(["native1".into()]),
        };
        let after = finish_scan(scan).unwrap();
        assert!(!after.records["entry1"].deleted);
        assert_eq!(before, revision(&after).unwrap());
        assert!(!after.bindings.contains_key("room1"));
    }

    #[test]
    fn unavailable_scopes_are_preserved_until_scanned_again() {
        let mut s = state("phone");
        reconcile(&mut s, local("room1", "entry1", "secret")).unwrap();
        let after = finish_scan(Scan {
            state: s,
            scopes: BTreeSet::from(["another-source".into()]),
            seen: BTreeSet::new(),
            locals: BTreeMap::new(),
        })
        .unwrap();
        assert!(!after.records["entry1"].deleted);
        let after = finish_scan(Scan {
            state: after,
            scopes: BTreeSet::from(["passwords".into()]),
            seen: BTreeSet::new(),
            locals: BTreeMap::new(),
        })
        .unwrap();
        assert!(after.records["entry1"].deleted);
        assert_eq!(after.records["entry1"].data, json!({}));
    }

    #[test]
    fn alias_chains_collapse_and_survive_new_canonical_ids() {
        let mut s = state("desktop");
        reconcile(&mut s, local("local", "z-entry", "secret")).unwrap();
        attach_alias(&mut s, "z-entry", "m-entry").unwrap();
        attach_alias(&mut s, "m-entry", "a-entry").unwrap();
        assert_eq!(resolve(&s, "z-entry"), "a-entry");
        assert_eq!(s.aliases["z-entry"], "a-entry");
        assert_eq!(s.bindings["local"].record_id, "a-entry");
        assert_eq!(s.records.len(), 1);
        assert_eq!(s.records["a-entry"].data["password"], "secret");
    }

    #[test]
    fn returning_sources_advertise_pending_changes_even_when_the_wire_revision_is_unchanged() {
        let mut s = state("phone");
        reconcile(&mut s, local("local", "entry", "old")).unwrap();
        let mut remote = s.records["entry"].clone();
        remote.writer = "desktop".into();
        remote.clock.insert("desktop".into(), 1);
        remote.data["password"] = json!("new");
        merge_record(&mut s, remote, 0).unwrap();
        let wire_revision = revision(&s).unwrap();
        s.active_scopes = Some(BTreeSet::from(["unavailable".into()]));
        assert!(!has_pending(&s));
        s.active_scopes = Some(BTreeSet::from(["passwords".into()]));
        assert!(has_pending(&s));
        assert_eq!(wire_revision, revision(&s).unwrap());
    }

    #[test]
    fn failed_persistence_does_not_publish_staged_changes() {
        let root =
            std::env::temp_dir().join(format!("monica-replica-test-{}", peering::random_id()));
        std::fs::create_dir_all(&root).unwrap();
        let mut replica = Replica {
            path: root.clone(),
            key: peering::secret_key(&peering::random_key()).unwrap(),
            state: state("desktop"),
            content_revision: String::new(),
            scan: None,
            locals: BTreeMap::new(),
            physical_scan: false,
        };
        let mut staged = replica.state.clone();
        reconcile(&mut staged, local("local", "entry", "secret")).unwrap();
        assert!(commit(&mut replica, staged).is_err());
        assert!(replica.state.records.is_empty());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn saved_journal_recovers_bindings_clocks_and_pending_remote_changes() {
        let root =
            std::env::temp_dir().join(format!("monica-replica-test-{}", peering::random_id()));
        let path = root.join("state.replica");
        let key = peering::secret_key(&peering::random_key()).unwrap();
        let mut replica = Replica {
            path: path.clone(),
            key,
            state: state("desktop"),
            content_revision: String::new(),
            scan: None,
            locals: BTreeMap::new(),
            physical_scan: false,
        };
        let mut staged = replica.state.clone();
        reconcile(&mut staged, local("local", "entry", "old")).unwrap();
        let mut remote = staged.records["entry"].clone();
        remote.clock.insert("phone".into(), 1);
        remote.writer = "phone".into();
        remote.data["password"] = json!("new");
        merge_record(&mut staged, remote, 0).unwrap();
        commit(&mut replica, staged).unwrap();
        let stored = std::fs::read(&path).unwrap();
        let loaded: State =
            serde_json::from_slice(&unseal(&stored, &replica.key).unwrap()).unwrap();
        assert_eq!(
            revision(&loaded).unwrap(),
            revision(&replica.state).unwrap()
        );
        assert_eq!(revision(&loaded).unwrap(), replica.content_revision);
        assert_eq!(loaded.bindings["local"].guard, "version-1");
        assert!(!dominates(
            &loaded.applied["entry"],
            &loaded.records["entry"].clock
        ));
        std::fs::remove_dir_all(root).unwrap();
    }
}
