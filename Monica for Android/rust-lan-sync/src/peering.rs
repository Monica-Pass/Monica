use super::*;
use zeroize::Zeroize;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(super) enum SyncMode {
    #[default]
    Bidirectional,
    SendOnly,
    ReceiveOnly,
}

pub(super) struct SyncFlow {
    pub send: bool,
    pub receive: bool,
}

impl SyncFlow {
    pub fn negotiate(local: Option<SyncMode>, remote: Option<SyncMode>) -> Result<Self> {
        use SyncMode::*;
        let local_mode = local.unwrap_or_default();
        let remote_mode = remote.unwrap_or_default();
        if (local.is_none() && remote_mode != Bidirectional)
            || (remote.is_none() && local_mode != Bidirectional)
        {
            return Err("sync_direction_unsupported");
        }
        let flow = Self {
            send: local_mode != ReceiveOnly && remote_mode != SendOnly,
            receive: local_mode != SendOnly && remote_mode != ReceiveOnly,
        };
        if !flow.send && !flow.receive {
            return Err("sync_direction_conflict");
        }
        Ok(flow)
    }
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub(super) struct DeviceHello {
    pub device_id: String,
    pub name: String,
    #[serde(default)]
    pub revision: String,
    #[serde(default)]
    pub content_type: String,
    #[serde(default)]
    pub vault_content_version: u32,
    #[serde(default)]
    pub pair_id: String,
    #[serde(default)]
    pub interval_seconds: u32,
    #[serde(default)]
    pub settings_version: u64,
    #[serde(default)]
    pub settings_writer: String,
    #[serde(default)]
    pub sync_request: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sync_mode: Option<SyncMode>,
    /// Optional capability, exchanged only in the authenticated v2 manifest.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub incremental_sync: u32,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub resume_sync: u32,
}

impl DeviceHello {
    pub fn validate(&self) -> Result<()> {
        if !identifier(&self.device_id)
            || self.revision.len() > 128
            || !matches!(self.content_type.as_str(), "" | "vault" | "passwords")
            || self.vault_content_version > 2
            || self.settings_version > i64::MAX as u64
            || self.sync_request > i64::MAX as u64
            || self.incremental_sync > 1
            || self.resume_sync > 1
            || self.name.chars().count() > 64
            || self.name.chars().any(char::is_control)
            || (!self.pair_id.is_empty() && !identifier(&self.pair_id))
            || (!self.settings_writer.is_empty() && !identifier(&self.settings_writer))
            || !(self.interval_seconds == 0 || (60..=86_400).contains(&self.interval_seconds))
            || (self
                .sync_mode
                .is_some_and(|mode| mode != SyncMode::Bidirectional)
                && (self.content_type != "vault" || self.vault_content_version != 2))
        {
            return Err("invalid_device");
        }
        Ok(())
    }
}

fn is_zero(value: &u32) -> bool {
    *value == 0
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub(super) struct PairSecrets {
    #[serde(default)]
    pub pair_id: String,
    #[serde(default)]
    pub trusted_key: String,
    #[serde(default)]
    pub vault_password: String,
    #[serde(default)]
    pub content_key: String,
}

impl Drop for PairSecrets {
    fn drop(&mut self) {
        self.trusted_key.zeroize();
        self.vault_password.zeroize();
        self.content_key.zeroize();
    }
}

#[derive(Clone, Serialize)]
pub(super) struct PeerInfo {
    #[serde(flatten)]
    pub device: DeviceHello,
    pub pairing: PairSecrets,
    pub vault_id: String,
    pub needs_bootstrap: bool,
    pub send_content: bool,
    pub receive_content: bool,
}

pub(super) fn identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
}

pub(super) fn secret_key(encoded: &str) -> Result<Zeroizing<[u8; 32]>> {
    let decoded = Zeroizing::new(
        URL_SAFE_NO_PAD
            .decode(encoded)
            .map_err(|_| "invalid_pairing_key")?,
    );
    if decoded.len() != 32 {
        return Err("invalid_pairing_key");
    }
    let mut key = Zeroizing::new([0; 32]);
    key.copy_from_slice(&decoded);
    Ok(key)
}

pub(super) fn random_id() -> String {
    let mut bytes = [0; 16];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

pub(super) fn random_key() -> String {
    let mut bytes = Zeroizing::new([0; 32]);
    rand::rngs::OsRng.fill_bytes(bytes.as_mut());
    URL_SAFE_NO_PAD.encode(bytes.as_ref())
}
