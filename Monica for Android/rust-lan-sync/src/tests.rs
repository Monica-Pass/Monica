use super::*;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

#[test]
fn receive_only_listener_receives_bundle_and_assets_without_sending() {
    let _guard = serial_sessions();
    for sender_mode in ["send_only", "bidirectional"] {
        let root = TestDirectory::new();
        let outgoing = root.directory("sender-out");
        let sender_in = root.directory("sender-in");
        let receiver_in = root.directory("receiver-in");
        for name in ["changes.mdbxsync", "assets.monica"] {
            std::fs::write(outgoing.join(name), format!("synthetic {name}")).unwrap();
        }
        let receiver = call(json!({
            "operation":"start", "protocol":2, "listen_address":"127.0.0.1",
            "vault_id":"shared", "incoming_directory":receiver_in,
            "device":{"device_id":"receiver", "name":"Receiver", "content_type":"vault",
                "vault_content_version":2, "sync_mode":"receive_only"}
        }));
        let sender = call(json!({
            "operation":"connect", "protocol":2,
            "invitation":format!("monica-lan:2:127.0.0.1:{}:{}", receiver["port"], receiver["code"].as_str().unwrap()),
            "vault_id":"shared", "incoming_directory":sender_in, "outgoing_directory":outgoing,
            "content_key":peering::random_key(),
            "device":{"device_id":"sender", "name":"Sender", "content_type":"vault",
                "vault_content_version":2, "sync_mode":sender_mode}
        }));
        let handles = [
            receiver["handle"].as_u64().unwrap(),
            sender["handle"].as_u64().unwrap(),
        ];
        let results = [wait(handles[0]), wait(handles[1])];
        for handle in handles {
            call(json!({"operation":"release", "handle":handle}));
        }
        for result in &results {
            assert_eq!(result.phase, "completed", "{:?}", result.error);
        }
        for name in ["changes.mdbxsync", "assets.monica"] {
            assert_eq!(
                std::fs::read(outgoing.join(name)).unwrap(),
                std::fs::read(receiver_in.join(name)).unwrap()
            );
        }
        assert_eq!(std::fs::read_dir(sender_in).unwrap().count(), 0);
        let sender_peer = serde_json::to_value(results[1].peer.as_ref().unwrap()).unwrap();
        assert_eq!(sender_peer["receive_content"], false);
        assert_eq!(sender_peer["pairing"]["content_key"], "");
        let receiver_peer = serde_json::to_value(results[0].peer.as_ref().unwrap()).unwrap();
        assert_eq!(receiver_peer["send_content"], false);
    }
}

#[test]
fn send_capable_listeners_still_require_a_sync_bundle() {
    let _guard = serial_sessions();
    for protocol in [1, 2] {
        for mode in [None, Some("send_only"), Some("bidirectional")] {
            let root = TestDirectory::new();
            let mut device = json!({"device_id":"fixture", "name":"Fixture", "content_type":"vault", "vault_content_version":2});
            if let Some(mode) = mode {
                device["sync_mode"] = json!(mode);
            }
            assert_eq!(request(json!({"operation":"start", "protocol":protocol,
                "incoming_directory":root.directory("incoming"), "vault_id":"shared", "device":device})).unwrap_err(), "missing_sync_bundle");
        }
    }
    // Supplying a directory still requires valid artifacts, even in receive-only mode.
    let root = TestDirectory::new();
    assert_eq!(request(json!({"operation":"start", "protocol":2, "vault_id":"shared",
        "incoming_directory":root.directory("incoming"), "outgoing_directory":root.directory("empty-out"),
        "device":{"device_id":"receiver", "name":"Receiver", "content_type":"vault", "vault_content_version":2,
            "sync_mode":"receive_only"}})).unwrap_err(), "missing_sync_bundle");
}

#[test]
fn device_direction_limits_real_bundle_and_asset_transmission() {
    let _guard = serial_sessions();
    let modes = [
        Some("send_only"),
        Some("bidirectional"),
        Some("receive_only"),
        None,
    ];
    for host_mode in modes {
        for peer_mode in modes {
            let root = TestDirectory::new();
            let host_out = root.directory("host-out");
            let peer_out = root.directory("peer-out");
            let host_in = root.directory("host-in");
            let peer_in = root.directory("peer-in");
            for (path, bundle, asset) in [
                (
                    &host_out,
                    b"synthetic host vault".as_slice(),
                    b"synthetic host attachment".as_slice(),
                ),
                (
                    &peer_out,
                    b"synthetic peer vault".as_slice(),
                    b"synthetic peer attachment".as_slice(),
                ),
            ] {
                std::fs::write(path.join("changes.mdbxsync"), bundle).unwrap();
                std::fs::write(path.join("assets.monica"), asset).unwrap();
            }
            let device = |id, mode: Option<&str>| {
                let mut value = json!({"device_id":id,"name":id,"content_type":"vault","vault_content_version":2});
                if let Some(mode) = mode {
                    value["sync_mode"] = json!(mode);
                }
                value
            };
            let host = call(
                json!({"operation":"start","protocol":2,"listen_address":"127.0.0.1",
                "vault_id":"shared","outgoing_directory":host_out,"incoming_directory":host_in,
                "content_key":peering::random_key(),"device":device("host",host_mode)}),
            );
            let invitation = format!(
                "monica-lan:2:127.0.0.1:{}:{}",
                host["port"],
                host["code"].as_str().unwrap()
            );
            let peer = call(
                json!({"operation":"connect","protocol":2,"invitation":invitation,
                "vault_id":"shared","outgoing_directory":peer_out,"incoming_directory":peer_in,
                "content_key":peering::random_key(),"device":device("peer",peer_mode)}),
            );
            let handles = [
                host["handle"].as_u64().unwrap(),
                peer["handle"].as_u64().unwrap(),
            ];
            let results = [wait(handles[0]), wait(handles[1])];
            for handle in handles {
                call(json!({"operation":"release","handle":handle}));
            }
            let host_mode_name = host_mode.unwrap_or("bidirectional");
            let peer_mode_name = peer_mode.unwrap_or("bidirectional");
            let send = host_mode_name != "receive_only" && peer_mode_name != "send_only";
            let receive = host_mode_name != "send_only" && peer_mode_name != "receive_only";
            let unsupported = (host_mode.is_none() && peer_mode_name != "bidirectional")
                || (peer_mode.is_none() && host_mode_name != "bidirectional");
            let expected_error = if unsupported {
                Some("sync_direction_unsupported")
            } else if !send && !receive {
                Some("sync_direction_conflict")
            } else {
                None
            };
            for result in &results {
                assert_eq!(result.error, expected_error, "{host_mode:?}/{peer_mode:?}");
                assert_eq!(
                    result.phase,
                    if expected_error.is_some() {
                        "failed"
                    } else {
                        "completed"
                    }
                );
            }
            for (source, destination, allowed) in
                [(&host_out, &peer_in, send), (&peer_out, &host_in, receive)]
            {
                for name in ["changes.mdbxsync", "assets.monica"] {
                    if expected_error.is_none() && allowed {
                        assert_eq!(
                            std::fs::read(source.join(name)).unwrap(),
                            std::fs::read(destination.join(name)).unwrap()
                        );
                    } else {
                        assert!(
                            !destination.join(name).exists(),
                            "forbidden {name}: {host_mode:?}/{peer_mode:?}"
                        );
                    }
                }
            }
            if expected_error.is_none() {
                for (result, sent, received) in
                    [(&results[0], send, receive), (&results[1], receive, send)]
                {
                    let info = serde_json::to_value(result.peer.as_ref().unwrap()).unwrap();
                    // Earlier clients omit these fields and remain bidirectional.
                    assert_eq!(info["send_content"], json!(sent));
                    assert_eq!(info["receive_content"], json!(received));
                    if !received {
                        assert_eq!(info["pairing"]["content_key"], "");
                    }
                }
            }
        }
    }
}

#[test]
fn qr_invitation_tries_the_next_interface_when_the_first_is_unreachable() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let out = root.directory("out");
    std::fs::write(out.join("changes.mdbxsync"), b"synthetic peer data").unwrap();
    let host = call(json!({
        "operation":"start", "protocol":2, "listen_address":"127.0.0.1",
        "vault_id":"same", "outgoing_directory":out, "incoming_directory":root.directory("host-in"),
        "device":{"device_id":"host","name":"Host"}
    }));
    let invitation = format!(
        "monica-lan:2:127.0.0.2,127.0.0.1:{}:{}",
        host["port"],
        host["code"].as_str().unwrap()
    );
    let client_result = request(json!({
        "operation":"connect", "protocol":2, "invitation":invitation,
        "vault_id":"same", "outgoing_directory":out, "incoming_directory":root.directory("client-in"),
        "device":{"device_id":"client","name":"Client"}
    }));
    let host_handle = host["handle"].as_u64().unwrap();
    if client_result.is_err() {
        call(json!({"operation":"release","handle":host_handle}));
    }
    let client = client_result.expect(
        "Scanning a QR with multiple local interfaces must connect through the reachable address",
    );
    let client_handle = client["handle"].as_u64().unwrap();
    let task = job(client_handle).unwrap();
    std::thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !task.cancel.load(Ordering::Acquire) {
            if task.progress.lock().unwrap().phase == "received" {
                task.applied.store(1, Ordering::Release);
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
    });
    for handle in [host_handle, client_handle] {
        let result = wait(handle);
        assert_eq!(result.phase, "completed", "{:?}", result.error);
        if handle == client_handle {
            assert_eq!(result.connection_attempt, 2);
        }
        call(json!({"operation":"release","handle":handle}));
    }
}

#[test]
fn default_qr_includes_all_local_interfaces_and_does_not_require_a_manual_choice() {
    let addresses = [
        "10.30.250.111".parse().unwrap(),
        "172.17.183.68".parse().unwrap(),
    ];
    let links = invitation_links(2, &addresses, 1234, "synthetic-key");
    assert_eq!(
        links[0],
        "monica-lan:2:10.30.250.111,172.17.183.68:1234:synthetic-key"
    );
    assert_eq!(
        invitation_links(1, &addresses, 1234, "synthetic-key").len(),
        2
    );
}

static SESSION_TESTS: Mutex<()> = Mutex::new(());

#[test]
fn unicast_discovery_without_multicast_returns_every_active_pairing_listener() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let out = root.directory("out");
    std::fs::write(out.join("changes.mdbxsync"), b"synthetic").unwrap();
    let mut handles = Vec::new();
    for index in 0..2 {
        let host = call(json!({
            "operation":"start","protocol":2,"vault_id":"same","outgoing_directory":out,
            "incoming_directory":root.directory(&format!("in-{index}")),
            "device":{"device_id":format!("device-{index}"),"name":"Fixture"}
        }));
        handles.push(host["handle"].as_u64().unwrap());
    }
    std::thread::sleep(Duration::from_millis(100));
    let result =
        request_ok(json!({"operation":"discover","targets":["127.0.0.1"],"multicast":false}));
    let devices = result["devices"].as_array().unwrap();
    for id in ["device-0", "device-1"] {
        assert!(
            devices.iter().any(|device| device["device_id"] == id),
            "Known-address discovery missed an active listener"
        );
        assert!(
            devices
                .iter()
                .any(|device| device["device_id"] == id && device["address"] == "127.0.0.1"),
            "Known-address discovery must work with multicast disabled"
        );
    }
    for handle in handles {
        call(json!({"operation":"release","handle":handle}));
    }
}

#[test]
fn directed_broadcast_discovery_works_without_multicast_or_remembered_addresses() {
    let _guard = serial_sessions();
    let has_local_broadcast =
        if_addrs::get_if_addrs()
            .unwrap_or_default()
            .iter()
            .any(|interface| {
                matches!(&interface.addr, if_addrs::IfAddr::V4(address)
            if address.ip.is_private() && address.broadcast.is_some_and(|ip| ip.is_private()))
            });
    if !has_local_broadcast {
        return;
    } // Loopback-only hosts cannot exercise interface broadcasts.
    let root = TestDirectory::new();
    let out = root.directory("out");
    std::fs::write(out.join("changes.mdbxsync"), b"synthetic hotspot bundle").unwrap();
    let host = call(
        json!({"operation":"start", "protocol":2, "vault_id":"hotspot-test",
        "outgoing_directory":out, "incoming_directory":root.directory("in"),
        "device":{"device_id":"hotspot-broadcast", "name":"Fixture"}}),
    );
    let found = request(json!({"operation":"discover", "multicast":false, "broadcast":true}));
    call(json!({"operation":"release", "handle":host["handle"]}));
    let found = found.unwrap();
    assert!(found["devices"].as_array().unwrap().iter().any(|device| device["device_id"] == "hotspot-broadcast"),
        "Directed broadcast must discover the listener when mDNS/multicast and old addresses are unavailable");
}

fn serial_sessions() -> std::sync::MutexGuard<'static, ()> {
    SESSION_TESTS
        .lock()
        .unwrap_or_else(|error| error.into_inner())
}

struct TestDirectory(PathBuf);
impl TestDirectory {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "monica-lan-test-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&root).unwrap();
        Self(root)
    }
    fn directory(&self, name: &str) -> PathBuf {
        let path = self.0.join(name);
        std::fs::create_dir(&path).unwrap();
        path
    }
}
impl Drop for TestDirectory {
    fn drop(&mut self) {
        assert_eq!(self.0.parent(), Some(std::env::temp_dir().as_path()));
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn request_ok(value: Value) -> Value {
    let response: Value = serde_json::from_str(&request_json(&value.to_string())).unwrap();
    assert_eq!(
        response["ok"],
        true,
        "{}",
        response.get("error").unwrap_or(&Value::Null)
    );
    response["data"].clone()
}

fn call(value: Value) -> Value {
    if value["operation"] == "release" {
        let handle = value["handle"].as_u64().unwrap();
        request_ok(json!({"operation":"cancel", "handle":handle}));
        wait(handle);
    }
    let data = request_ok(value);
    if let Some(handle) = data["handle"].as_u64() {
        let task = job(handle).unwrap();
        std::thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(10);
            while Instant::now() < deadline && !task.cancel.load(Ordering::Acquire) {
                if task.progress.lock().unwrap().phase == "received" {
                    task.applied.store(1, Ordering::Release);
                    return;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
        });
    }
    data
}

fn wait(handle: u64) -> Progress {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let progress = job(handle).unwrap().progress.lock().unwrap().clone();
        if matches!(progress.phase, "completed" | "failed" | "cancelled") {
            return progress;
        }
        assert!(Instant::now() < deadline, "transfer timed out");
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn exchanges_large_native_artifacts_in_both_directions() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let host_out = root.directory("host-out");
    let peer_out = root.directory("peer-out");
    let host_in = root.directory("host-in");
    let peer_in = root.directory("peer-in");
    let host_bytes: Vec<u8> = (0..1_000_000).map(|value| (value % 251) as u8).collect();
    std::fs::write(host_out.join("changes.mdbxsync"), &host_bytes).unwrap();
    std::fs::write(peer_out.join("changes.mdbxsync"), b"synthetic peer changes").unwrap();
    let host = call(
        json!({"operation":"start","vault_id":"synthetic-vault","outgoing_directory":host_out,"incoming_directory":host_in}),
    );
    let invitation = format!(
        "monica-lan:1:127.0.0.1:{}:{}",
        host["port"],
        host["code"].as_str().unwrap()
    );
    let peer = call(
        json!({"operation":"connect","invitation":invitation,"vault_id":"synthetic-vault","outgoing_directory":peer_out,"incoming_directory":peer_in}),
    );
    for handle in [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ] {
        let result = wait(handle);
        assert_eq!(result.phase, "completed", "{:?}", result.error);
        assert!(result.total > 1_000_000);
        assert_eq!(result.transferred, result.total);
        call(json!({"operation":"release","handle":handle}));
    }
    assert_eq!(
        std::fs::read(peer_in.join("changes.mdbxsync")).unwrap(),
        host_bytes
    );
    assert_eq!(
        std::fs::read(host_in.join("changes.mdbxsync")).unwrap(),
        b"synthetic peer changes"
    );
    assert!(!peer_in.join("changes.mdbxsync.part").exists());
}

#[test]
fn new_peer_receives_bootstrap_without_sending_an_empty_vault() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic bundle").unwrap();
    std::fs::write(outgoing.join("vault.mdbx"), b"synthetic native backup").unwrap();
    let incoming = root.directory("peer-in");
    let host = call(
        json!({"operation":"start","vault_id":"synthetic-vault","outgoing_directory":outgoing,"incoming_directory":root.directory("host-in")}),
    );
    let invitation = format!(
        "monica-lan:1:127.0.0.1:{}:{}",
        host["port"],
        host["code"].as_str().unwrap()
    );
    let peer =
        call(json!({"operation":"connect","invitation":invitation,"incoming_directory":incoming}));
    for handle in [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ] {
        assert_eq!(wait(handle).phase, "completed");
        call(json!({"operation":"release","handle":handle}));
    }
    assert_eq!(
        std::fs::read(incoming.join("vault.mdbx")).unwrap(),
        b"synthetic native backup"
    );
}

#[test]
fn rejects_different_vaults_before_any_file_is_received() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic bundle").unwrap();
    let incoming = root.directory("peer-in");
    let host = call(
        json!({"operation":"start","vault_id":"vault-a","outgoing_directory":outgoing,"incoming_directory":root.directory("host-in")}),
    );
    let invitation = format!(
        "monica-lan:1:127.0.0.1:{}:{}",
        host["port"],
        host["code"].as_str().unwrap()
    );
    let peer = call(
        json!({"operation":"connect","invitation":invitation,"vault_id":"vault-b","outgoing_directory":outgoing,"incoming_directory":incoming}),
    );
    for handle in [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ] {
        let result = wait(handle);
        assert_eq!(result.phase, "failed");
        assert_eq!(result.error, Some("different_vault"));
        call(json!({"operation":"release","handle":handle}));
    }
    assert_eq!(std::fs::read_dir(incoming).unwrap().count(), 0);
}

#[test]
fn wrong_pairing_secret_cannot_receive_artifacts() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic bundle").unwrap();
    let incoming = root.directory("peer-in");
    let host = call(
        json!({"operation":"start","vault_id":"vault-a","outgoing_directory":outgoing,"incoming_directory":root.directory("host-in")}),
    );
    let invitation = format!(
        "monica-lan:1:127.0.0.1:{}:{}",
        host["port"],
        URL_SAFE_NO_PAD.encode([0u8; 32])
    );
    let peer =
        call(json!({"operation":"connect","invitation":invitation,"incoming_directory":incoming}));
    assert_eq!(wait(peer["handle"].as_u64().unwrap()).phase, "failed");
    assert_eq!(std::fs::read_dir(incoming).unwrap().count(), 0);
    for handle in [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ] {
        call(json!({"operation":"release","handle":handle}));
    }
}

#[test]
fn public_addresses_and_malformed_invites_fail_without_echoing_secrets() {
    for invitation in [
        "monica-lan:1:8.8.8.8:53318:synthetic-private-code",
        "monica-lan:1:127.0.0.1:0:secret",
        "https://example.com/",
    ] {
        let response =
            request_json(&json!({"operation":"connect","invitation":invitation}).to_string());
        assert!(response.contains("\"ok\":false"));
        assert!(!response.contains(invitation));
    }
    assert!(request_json(&"x".repeat(MAX_REQUEST_BYTES + 1)).contains("request_too_large"));
}

#[test]
fn cancellation_stays_observable_until_the_worker_stops() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic bundle").unwrap();
    let host = call(
        json!({"operation":"start","vault_id":"vault-a","outgoing_directory":outgoing,"incoming_directory":root.directory("in")}),
    );
    let handle = host["handle"].as_u64().unwrap();
    let task = job(handle).unwrap();
    call(json!({"operation":"cancel","handle":handle}));
    assert!(
        job(handle).is_ok(),
        "the caller must be able to wait for file cleanup"
    );
    let deadline = Instant::now() + Duration::from_secs(2);
    while task.progress.lock().unwrap().phase != "cancelled" {
        assert!(Instant::now() < deadline);
        std::thread::sleep(Duration::from_millis(10));
    }
    call(json!({"operation":"release","handle":handle}));
    assert!(job(handle).is_err());
}

fn unacknowledged_pair(root: &TestDirectory) -> [u64; 2] {
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic bundle").unwrap();
    let host = request_ok(json!({"operation":"start", "vault_id":"vault-a",
        "outgoing_directory":outgoing, "incoming_directory":root.directory("host-in")}));
    let invitation = format!(
        "monica-lan:1:127.0.0.1:{}:{}",
        host["port"],
        host["code"].as_str().unwrap()
    );
    let peer = request_ok(json!({"operation":"connect", "invitation":invitation,
        "vault_id":"vault-a", "outgoing_directory":outgoing, "incoming_directory":root.directory("peer-in")}));
    let handles = [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ];
    for handle in handles {
        wait_for_phase(handle, "received");
    }
    handles
}

fn wait_for_phase(handle: u64, expected: &str) {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let phase = job(handle).unwrap().progress.lock().unwrap().phase;
        if phase == expected {
            return;
        }
        assert!(
            !matches!(phase, "failed" | "cancelled" | "completed"),
            "unexpected phase {phase}"
        );
        assert!(Instant::now() < deadline, "did not reach {expected}");
        std::thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn completion_requires_both_database_merges() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let [host, peer] = unacknowledged_pair(&root);
    request_ok(json!({"operation":"acknowledge", "handle":host, "success":true}));
    wait_for_phase(host, "confirming");
    assert_eq!(
        job(peer).unwrap().progress.lock().unwrap().phase,
        "received"
    );
    request_ok(json!({"operation":"acknowledge", "handle":peer, "success":true}));
    for handle in [host, peer] {
        assert_eq!(wait(handle).phase, "completed");
        call(json!({"operation":"release", "handle":handle}));
    }
}

#[test]
fn a_peer_merge_failure_never_reports_success() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let [host, peer] = unacknowledged_pair(&root);
    request_ok(json!({"operation":"acknowledge", "handle":host, "success":true}));
    request_ok(json!({"operation":"acknowledge", "handle":peer, "success":false}));
    assert_eq!(wait(host).error, Some("peer_merge_failed"));
    assert_eq!(wait(peer).error, Some("local_merge_failed"));
    for handle in [host, peer] {
        call(json!({"operation":"release", "handle":handle}));
    }
}

#[test]
fn active_sessions_cannot_be_released_before_cleanup() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic bundle").unwrap();
    let host = request_ok(json!({"operation":"start", "vault_id":"vault-a",
        "outgoing_directory":outgoing, "incoming_directory":root.directory("in")}));
    let handle = host["handle"].as_u64().unwrap();
    let response = request_json(&json!({"operation":"release", "handle":handle}).to_string());
    assert!(response.contains("session_busy"));
    call(json!({"operation":"cancel", "handle":handle}));
    assert_eq!(wait(handle).phase, "cancelled");
    call(json!({"operation":"release", "handle":handle}));
}

#[test]
fn device_pairing_exchanges_a_separate_trusted_key_and_bootstrap_credential() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("pair-out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic changes").unwrap();
    std::fs::write(outgoing.join("vault.mdbx"), b"synthetic vault").unwrap();
    let host = call(
        json!({"operation":"start", "protocol":2, "vault_id":"shared-vault",
        "vault_password":"synthetic-vault-password", "outgoing_directory":outgoing,
        "incoming_directory":root.directory("pair-host-in"),
        "device":{"device_id":"desktop-node","name":"Desktop"}}),
    );
    let invitation = format!(
        "monica-lan:2:127.0.0.1:{}:{}",
        host["port"],
        host["code"].as_str().unwrap()
    );
    let peer = call(
        json!({"operation":"connect", "protocol":2, "invitation":invitation,
        "incoming_directory":root.directory("pair-client-in"),
        "device":{"device_id":"phone-node","name":"Phone"}}),
    );
    let host_handle = host["handle"].as_u64().unwrap();
    let peer_handle = peer["handle"].as_u64().unwrap();
    let hosted = wait(host_handle);
    let received = wait(peer_handle);
    assert_eq!(hosted.phase, "completed", "{:?}", hosted.error);
    assert_eq!(received.phase, "completed", "{:?}", received.error);
    let host_info = hosted.peer.as_ref().unwrap();
    let peer_info = received.peer.as_ref().unwrap();
    assert_eq!(host_info.device.device_id, "phone-node");
    assert_eq!(peer_info.device.device_id, "desktop-node");
    assert!(host_info.pairing.trusted_key == peer_info.pairing.trusted_key);
    assert!(host_info.pairing.trusted_key != host["code"].as_str().unwrap());
    assert_eq!(host_info.pairing.pair_id, peer_info.pairing.pair_id);
    assert_eq!(peer_info.pairing.vault_password, "synthetic-vault-password");
    assert!(host_info.pairing.vault_password.is_empty());
    assert!(peer_info.needs_bootstrap);
    let trusted_key = host_info.pairing.trusted_key.clone();
    let pair_id = host_info.pairing.pair_id.clone();
    for handle in [host_handle, peer_handle] {
        call(json!({"operation":"release","handle":handle}));
    }

    let resumed = call(
        json!({"operation":"start", "protocol":2, "vault_id":"shared-vault",
        "trusted_key":trusted_key, "expected_device_id":"phone-node", "outgoing_directory":outgoing,
        "incoming_directory":root.directory("resume-host-in"),
        "device":{"device_id":"desktop-node","name":"Desktop","pair_id":pair_id}}),
    );
    let invitation = format!("monica-lan:2:127.0.0.1:{}:{}", resumed["port"], trusted_key);
    let client = call(
        json!({"operation":"connect", "protocol":2, "invitation":invitation,
        "vault_id":"shared-vault", "outgoing_directory":outgoing,
        "expected_device_id":"desktop-node", "incoming_directory":root.directory("resume-peer-in"),
        "device":{"device_id":"phone-node","name":"Phone","pair_id":pair_id}}),
    );
    for handle in [
        resumed["handle"].as_u64().unwrap(),
        client["handle"].as_u64().unwrap(),
    ] {
        let result = wait(handle);
        assert_eq!(result.phase, "completed", "{:?}", result.error);
        assert!(result
            .peer
            .as_ref()
            .unwrap()
            .pairing
            .vault_password
            .is_empty());
        call(json!({"operation":"release","handle":handle}));
    }
}

#[test]
fn trusted_connection_rejects_an_unexpected_device_identity() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let outgoing = root.directory("out");
    std::fs::write(outgoing.join("changes.mdbxsync"), b"synthetic changes").unwrap();
    let key = peering::random_key();
    let host = call(
        json!({"operation":"start", "protocol":2, "vault_id":"shared",
        "trusted_key":key,"expected_device_id":"expected-phone","outgoing_directory":outgoing,
        "incoming_directory":root.directory("host-in"),
        "device":{"device_id":"desktop","name":"Desktop","pair_id":"pair"}}),
    );
    let invitation = format!("monica-lan:2:127.0.0.1:{}:{}", host["port"], key);
    let peer = call(
        json!({"operation":"connect", "protocol":2,"invitation":invitation,
        "vault_id":"shared","outgoing_directory":outgoing,"incoming_directory":root.directory("peer-in"),
        "device":{"device_id":"unexpected-phone","name":"Phone","pair_id":"pair"}}),
    );
    let host_handle = host["handle"].as_u64().unwrap();
    assert_eq!(wait(host_handle).error, Some("different_device"));
    assert_eq!(
        std::fs::read_dir(root.0.join("host-in")).unwrap().count(),
        0
    );
    assert!(wait(host_handle).peer.is_none());
    for handle in [host_handle, peer["handle"].as_u64().unwrap()] {
        call(json!({"operation":"release","handle":handle}));
    }
}
