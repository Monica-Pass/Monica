//! Real authenticated TCP coverage, independent of the checkpoint unit tests.
use super::*;
use crate::tests::{call, serial_sessions, wait, TestDirectory};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    net::{Shutdown, TcpStream},
    time::Instant,
};

const PAIR: &str = "resume-fixture-pair";

fn device(id: &str, mode: &str, resume: u32) -> Value {
    json!({"device_id":id,"name":id,"pair_id":PAIR,"content_type":"vault",
        "vault_content_version":2,"sync_mode":mode,"resume_sync":resume})
}

fn receiver(incoming: &Path, spool: &Path, key: &str, acknowledge: bool) -> Value {
    let value = json!({"operation":"start","protocol":2,"listen_address":"127.0.0.1",
        "vault_id":"resume-shared-vault","incoming_directory":incoming,"resume_root":spool,
        "trusted_key":key,"expected_device_id":"resume-sender",
        "device":device("resume-receiver","receive_only",1)});
    if acknowledge {
        call(value)
    } else {
        request(value).unwrap()
    }
}

fn sender(
    port: u16,
    outgoing: &Path,
    incoming: &Path,
    spool: &Path,
    key: &str,
    resume: u32,
    acknowledge: bool,
) -> Value {
    let value = json!({"operation":"connect","protocol":2,
        "invitation":format!("monica-lan:2:127.0.0.1:{port}:{key}"),
        "vault_id":"resume-shared-vault","outgoing_directory":outgoing,"incoming_directory":incoming,
        "resume_root":spool,"expected_device_id":"resume-receiver","content_key":peering::random_key(),
        "device":device("resume-sender","send_only",resume)});
    if acknowledge {
        call(value)
    } else {
        request(value).unwrap()
    }
}

/// Cuts the real encrypted connection at a deterministic wire-byte boundary,
/// rather than racing a timer against loopback transfer completion.
fn dropping_relay(receiver_port: u16, after: usize) -> (u16, std::thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let worker = std::thread::spawn(move || {
        let (mut downstream, _) = listener.accept().unwrap();
        let mut upstream = TcpStream::connect((Ipv4Addr::LOCALHOST, receiver_port)).unwrap();
        let mut upstream_read = upstream.try_clone().unwrap();
        let mut downstream_write = downstream.try_clone().unwrap();
        let reverse = std::thread::spawn(move || {
            let _ = std::io::copy(&mut upstream_read, &mut downstream_write);
        });
        let mut remaining = after;
        let mut buffer = [0; 4096];
        while remaining > 0 {
            let maximum = remaining.min(buffer.len());
            let count = downstream.read(&mut buffer[..maximum]).unwrap_or(0);
            if count == 0 || upstream.write_all(&buffer[..count]).is_err() {
                break;
            }
            remaining -= count;
        }
        let _ = upstream.shutdown(Shutdown::Both);
        let _ = downstream.shutdown(Shutdown::Both);
        reverse.join().unwrap();
    });
    (port, worker)
}

fn artifact_bytes() -> Vec<u8> {
    (0..512 * 1024).map(|n| (n % 251) as u8).collect()
}

#[test]
fn interrupted_noise_transfer_resumes_exact_ciphertext_without_early_acknowledgement() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let out = root.directory("out");
    let bytes = artifact_bytes();
    std::fs::write(out.join("changes.mdbxsync"), &bytes).unwrap();
    let receiver_spool = root.directory("receiver-spool");
    let sender_spool = root.directory("sender-spool");
    let first_in = root.directory("first-receiver-in");
    let key = peering::random_key();
    let host = receiver(&first_in, &receiver_spool, &key, false);
    let (port, relay) = dropping_relay(host["port"].as_u64().unwrap() as u16, 160 * 1024);
    let peer = sender(
        port,
        &out,
        &root.directory("first-sender-in"),
        &sender_spool,
        &key,
        1,
        false,
    );
    let handles = [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ];
    for handle in handles {
        let result = wait(handle);
        assert_eq!(result.phase, "failed");
        assert_eq!(
            job(handle).unwrap().applied.load(Ordering::Acquire),
            0,
            "a partial transport never means successful application"
        );
    }
    relay.join().unwrap();
    assert!(!first_in.join("changes.mdbxsync").exists());
    let checkpoints: Vec<_> = std::fs::read_dir(&receiver_spool)
        .unwrap()
        .map(|e| e.unwrap())
        .collect();
    assert_eq!(checkpoints.len(), 1);
    let retained = checkpoints[0].metadata().unwrap().len();
    assert!(retained > 0 && retained < bytes.len() as u64);
    for handle in handles {
        call(json!({"operation":"release","handle":handle}));
    }

    let second_in = root.directory("second-receiver-in");
    let host = receiver(&second_in, &receiver_spool, &key, true);
    let peer = sender(
        host["port"].as_u64().unwrap() as u16,
        &out,
        &root.directory("second-sender-in"),
        &sender_spool,
        &key,
        1,
        true,
    );
    for handle in [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ] {
        let result = wait(handle);
        assert_eq!(result.phase, "completed", "{:?}", result.error);
        assert_eq!(
            result.transferred,
            bytes.len() as u64 - retained,
            "only bytes after the verified checkpoint should cross the new Noise channel"
        );
        call(json!({"operation":"release","handle":handle}));
    }
    assert_eq!(
        std::fs::read(second_in.join("changes.mdbxsync")).unwrap(),
        bytes
    );
    assert_eq!(std::fs::read_dir(receiver_spool).unwrap().count(), 0);
}

#[test]
fn corrupt_checkpoint_and_legacy_peer_use_full_safe_transfer() {
    let _guard = serial_sessions();
    for capability in [0, 1] {
        let root = TestDirectory::new();
        let out = root.directory("out");
        let bytes = artifact_bytes();
        std::fs::write(out.join("changes.mdbxsync"), &bytes).unwrap();
        let key = peering::random_key();
        let decoded = peering::secret_key(&key).unwrap();
        let scope = resume::Scope::paired("resume-sender", PAIR, &decoded).unwrap();
        let descriptor = resume::Descriptor::new(
            "changes.mdbxsync",
            bytes.len() as u64,
            &format!("{:x}", Sha256::digest(&bytes)),
        )
        .unwrap();
        let receiver_spool = root.directory("receiver-spool");
        let mut partial =
            resume::Partial::open(&receiver_spool, &scope, descriptor, &mut || Ok(())).unwrap();
        partial
            .append(&[0xadu8; 32 * 1024], &mut || Ok(()))
            .unwrap();
        drop(partial);
        let incoming = root.directory("receiver-in");
        let host = receiver(&incoming, &receiver_spool, &key, true);
        let peer = sender(
            host["port"].as_u64().unwrap() as u16,
            &out,
            &root.directory("sender-in"),
            &root.directory("sender-spool"),
            &key,
            capability,
            true,
        );
        for handle in [
            host["handle"].as_u64().unwrap(),
            peer["handle"].as_u64().unwrap(),
        ] {
            let result = wait(handle);
            assert_eq!(result.phase, "completed", "{:?}", result.error);
            assert_eq!(result.transferred, bytes.len() as u64);
            call(json!({"operation":"release","handle":handle}));
        }
        assert_eq!(
            std::fs::read(incoming.join("changes.mdbxsync")).unwrap(),
            bytes
        );
        // A legacy exchange does not interpret or consume a checkpoint.
        assert_eq!(
            std::fs::read_dir(receiver_spool).unwrap().count(),
            if capability == 0 { 1 } else { 0 }
        );
    }
}

#[test]
fn cancelling_after_resumed_transport_still_requires_database_application() {
    let _guard = serial_sessions();
    let root = TestDirectory::new();
    let out = root.directory("out");
    let bytes = artifact_bytes();
    std::fs::write(out.join("changes.mdbxsync"), &bytes).unwrap();
    let key = peering::random_key();
    let receiver_spool = root.directory("receiver-spool");
    let scope =
        resume::Scope::paired("resume-sender", PAIR, &peering::secret_key(&key).unwrap()).unwrap();
    let descriptor = resume::Descriptor::new(
        "changes.mdbxsync",
        bytes.len() as u64,
        &format!("{:x}", Sha256::digest(&bytes)),
    )
    .unwrap();
    let mut partial =
        resume::Partial::open(&receiver_spool, &scope, descriptor, &mut || Ok(())).unwrap();
    partial.append(&bytes[..60 * 1024], &mut || Ok(())).unwrap();
    drop(partial);
    let incoming = root.directory("receiver-in");
    let host = receiver(&incoming, &receiver_spool, &key, false);
    let peer = sender(
        host["port"].as_u64().unwrap() as u16,
        &out,
        &root.directory("sender-in"),
        &root.directory("sender-spool"),
        &key,
        1,
        false,
    );
    let handles = [
        host["handle"].as_u64().unwrap(),
        peer["handle"].as_u64().unwrap(),
    ];
    let deadline = Instant::now() + Duration::from_secs(10);
    for handle in handles {
        loop {
            let progress = job(handle).unwrap().progress.lock().unwrap().clone();
            if progress.phase == "received" {
                break;
            }
            assert!(
                !matches!(progress.phase, "failed" | "cancelled" | "completed"),
                "{:?}",
                progress.error
            );
            assert!(Instant::now() < deadline);
            std::thread::sleep(Duration::from_millis(5));
        }
        assert_eq!(job(handle).unwrap().applied.load(Ordering::Acquire), 0);
    }
    for handle in handles {
        call(json!({"operation":"cancel","handle":handle}));
    }
    for handle in handles {
        assert_eq!(wait(handle).phase, "cancelled");
        assert_eq!(job(handle).unwrap().applied.load(Ordering::Acquire), 0);
        call(json!({"operation":"release","handle":handle}));
    }
    assert!(!incoming.join("changes.mdbxsync").exists());
}
