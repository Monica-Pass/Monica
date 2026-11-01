use super::*;
use crate::tests::{call, serial_sessions, wait, TestDirectory};

fn bundle(path: &Path, changed: bool, count: usize) -> String {
    let encoded = peering::random_key();
    let key = peering::secret_key(&encoded).unwrap();
    let mut records = BTreeMap::new();
    for n in 0..count {
        let deleted = changed && n == 2;
        let version = if changed && (n == 2 || n == 3) { 2 } else { 1 };
        let id = format!("record-{n}");
        records.insert(id.clone(),Record { id, clock:BTreeMap::from([("origin".into(),version)]),origins:BTreeSet::from(["origin".into()]),writer:"origin".into(),deleted,
            data:json!({"title":format!("Synthetic {n}"),"password":if version == 2 { "changed" } else { "original" }}) });
    }
    let aliases = if changed {
        BTreeMap::from([("z-alias".into(), "record-0".into())])
    } else {
        BTreeMap::new()
    };
    write(
        &path.join("changes.mdbxsync"),
        &seal(
            &serde_json::to_vec(&Wire {
                version: 1,
                records,
                aliases,
            })
            .unwrap(),
            &key,
        )
        .unwrap(),
        false,
    )
    .unwrap();
    for bytes in [
        vec![7; 300_000],
        if changed { vec![8; 120_000] } else { vec![] },
    ] {
        if bytes.is_empty() {
            continue;
        }
        let id = hash(&bytes);
        crate::assets::request(&json!({"operation":"asset_append","path":path.join("assets.monica"),"key":encoded,"id":id,"index":0,"final":true,
            "data":base64::engine::general_purpose::STANDARD.encode(bytes)})).unwrap();
    }
    encoded
}

#[test]
fn real_tcp_delta_omits_unchanged_records_and_assets_and_legacy_falls_back() {
    let _guard = serial_sessions();
    for cap in [0, 1] {
        let root = TestDirectory::new();
        let host_out = root.directory("host-out");
        let peer_out = root.directory("peer-out");
        let host_key = bundle(&host_out, true, 1000);
        let peer_key = bundle(&peer_out, false, 1000);
        let host_in = root.directory("host-in");
        let peer_in = root.directory("peer-in");
        let pair_key = peering::random_key();
        let device = |id| json!({"device_id":id,"name":id,"pair_id":"pair","content_type":"vault","vault_content_version":2,"incremental_sync":cap});
        let host = call(
            json!({"operation":"start","protocol":2,"listen_address":"127.0.0.1","vault_id":"shared",
            "outgoing_directory":host_out,"incoming_directory":host_in,"content_key":host_key,"expected_device_id":"peer","trusted_key":pair_key,"device":device("host")}),
        );
        let peer = call(
            json!({"operation":"connect","protocol":2,"vault_id":"shared","outgoing_directory":peer_out,"incoming_directory":peer_in,
            "content_key":peer_key,"expected_device_id":"host","device":device("peer"),"invitation":format!("monica-lan:2:127.0.0.1:{}:{}",host["port"],pair_key)}),
        );
        let handles = [
            host["handle"].as_u64().unwrap(),
            peer["handle"].as_u64().unwrap(),
        ];
        let results = [wait(handles[0]), wait(handles[1])];
        for result in &results {
            assert_eq!(result.phase, "completed", "{:?}", result.error);
        }
        let received = load_wire(&peer_in, &peering::secret_key(&host_key).unwrap()).unwrap();
        if cap == 1 {
            assert_eq!(received.records.len(), 2);
            assert!(received.records["record-2"].deleted);
            assert_eq!(
                received.aliases.get("z-alias").map(String::as_str),
                Some("record-0")
            );
            assert_eq!(
                load_wire(&host_in, &peering::secret_key(&peer_key).unwrap())
                    .unwrap()
                    .records
                    .len(),
                2
            );
            assert!(!host_in.join("assets.monica").exists());
            assert_eq!(
                assets(
                    &peer_in,
                    &peering::secret_key(&host_key).unwrap(),
                    &Job::new("checking")
                )
                .unwrap()
                .len(),
                1
            );
            assert!(
                results[0].transferred < 140_000,
                "{}",
                results[0].transferred
            );
            // Applying a partial wire keeps every omitted original record.
            let baseline_wire =
                load_wire(&peer_out, &peering::secret_key(&peer_key).unwrap()).unwrap();
            let baseline = State {
                version: 1,
                node: "peer".into(),
                records: baseline_wire.records.clone(),
                ..State::default()
            };
            let merged = incoming_state(
                &baseline,
                &json!({"path":peer_in.join("changes.mdbxsync"),"content_key":host_key}),
                false,
            )
            .unwrap();
            assert_eq!(merged.records.len(), 1000);
            assert!(merged.records["record-2"].deleted);
        } else {
            assert_eq!(received.records.len(), 1000);
            assert_eq!(
                std::fs::read(host_out.join("changes.mdbxsync")).unwrap(),
                std::fs::read(peer_in.join("changes.mdbxsync")).unwrap()
            );
            assert!(results[0].transferred > 600_000);
        }
        for handle in handles {
            call(json!({"operation":"release","handle":handle}));
        }
    }
}

#[test]
fn new_peer_negotiates_full_transfer_with_capability_absent_legacy_peer() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let out = root.directory("out");
    let key = bundle(&out, false, 3);
    let trusted = peering::random_key();
    let host_in = root.directory("host-in");
    let peer_in = root.directory("peer-in");
    let host = call(
        json!({"operation":"start","protocol":2,"listen_address":"127.0.0.1","vault_id":"shared","outgoing_directory":out,"incoming_directory":host_in,
        "content_key":key,"expected_device_id":"peer","trusted_key":trusted,"device":{"device_id":"host","name":"host","pair_id":"pair","content_type":"vault","vault_content_version":2,"incremental_sync":1}}),
    );
    let peer = call(
        json!({"operation":"connect","protocol":2,"vault_id":"shared","outgoing_directory":out,"incoming_directory":peer_in,"content_key":key,"expected_device_id":"host",
        "invitation":format!("monica-lan:2:127.0.0.1:{}:{}",host["port"],trusted),"device":{"device_id":"peer","name":"peer","pair_id":"pair","content_type":"vault","vault_content_version":2}}),
    );
    for handle in [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ] {
        assert_eq!(wait(handle).phase, "completed");
        call(json!({"operation":"release","handle":handle}));
    }
    assert_eq!(
        std::fs::read(out.join("changes.mdbxsync")).unwrap(),
        std::fs::read(peer_in.join("changes.mdbxsync")).unwrap()
    );
}
