use stack_core::library::Library;
use stack_transport::{identity_certificate, identity_init, listen_once, peer_enroll, peer_revoke, sync_now};
use std::net::{SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::time::Duration;

fn port() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.local_addr().unwrap()
}

fn initialized(root: &Path) -> (String, String) {
    let library = Library::open(root).unwrap();
    let status = library.sync_status().unwrap();
    let replica = status["replica"].as_str().unwrap().to_owned();
    let group = library.status().unwrap().library_id;
    drop(library);
    identity_init(root).unwrap();
    (group, replica)
}

fn pair(a: &Path, b: &Path, group: &str, a_id: &str, b_id: &str) {
    let a_cert = identity_certificate(a).unwrap();
    let b_cert = identity_certificate(b).unwrap();
    peer_enroll(a, group, b_id, &b_cert, &stack_transport::fingerprint(&b_cert)).unwrap();
    peer_enroll(b, group, a_id, &a_cert, &stack_transport::fingerprint(&a_cert)).unwrap();
}

fn run_pair(a: PathBuf, b: PathBuf, address: SocketAddr) -> (serde_json::Value, serde_json::Value) {
    let listener = std::thread::spawn(move || listen_once(&b, address));
    std::thread::sleep(Duration::from_millis(80));
    let client = sync_now(&a, address).unwrap();
    let server = listener.join().unwrap().unwrap();
    (client, server)
}

#[test]
fn paired_localhost_applies_and_acknowledges_then_replays_safely() {
    let temp = tempfile::Builder::new().prefix("stack-lan-test-").tempdir_in("/tmp").unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a).unwrap().sync_start(&[b_id.clone()]).unwrap();
    let (client, server) = run_pair(a.clone(), b.clone(), port());
    assert_eq!(client["status"]["library"], group);
    assert_eq!(server["status"]["library"], group);
    assert_eq!(client["status"]["pending_gaps"], 0);
    assert_eq!(server["status"]["pending_gaps"], 0);
    assert_eq!(server["status"]["acknowledged_by"][&a_id][&a_id], 1);
    let (client, server) = run_pair(a, b, port());
    assert_eq!(client["received"]["applied"], 0);
    assert_eq!(server["received"]["applied"], 0);
}

#[test]
fn unknown_or_revoked_peer_and_wrong_verified_fingerprint_are_rejected() {
    let temp = tempfile::Builder::new().prefix("stack-lan-deny-").tempdir_in("/tmp").unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let c = temp.path().join("c");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    let (_, c_id) = initialized(&c);
    let b_cert = identity_certificate(&b).unwrap();
    assert!(peer_enroll(&a, &group, &b_id, &b_cert, &"0".repeat(64)).is_err());
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a).unwrap().sync_start(&[b_id.clone()]).unwrap();
    let a_cert = identity_certificate(&a).unwrap();
    peer_enroll(&c, &group, &a_id, &a_cert, &stack_transport::fingerprint(&a_cert)).unwrap();
    Library::open(&c).unwrap().sync_start(&[a_id.clone()]).unwrap();
    let address = port();
    let root = a.clone();
    let listener = std::thread::spawn(move || listen_once(&root, address));
    std::thread::sleep(Duration::from_millis(80));
    assert!(sync_now(&c, address).is_err());
    assert!(listener.join().unwrap().is_err());
    peer_revoke(&a).unwrap();
    assert!(listen_once(&a, port()).is_err());
    assert!(sync_now(&a, port()).is_err());
    assert_ne!(b_id, c_id);
}

#[test]
fn interrupted_connection_can_reconnect_without_advancing_receipts() {
    let temp = tempfile::Builder::new().prefix("stack-lan-interrupt-").tempdir_in("/tmp").unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a).unwrap().sync_start(&[b_id]).unwrap();
    let address = port();
    let root = b.clone();
    let listener = std::thread::spawn(move || listen_once(&root, address));
    std::thread::sleep(Duration::from_millis(80));
    let tcp = std::net::TcpStream::connect(address).unwrap();
    drop(tcp);
    assert!(listener.join().unwrap().is_err());
    assert!(Library::open(&b).unwrap().sync_status().unwrap()["adopted"] == false);
    let (_, server) = run_pair(a, b, port());
    assert_eq!(server["status"]["library"], group);
}
