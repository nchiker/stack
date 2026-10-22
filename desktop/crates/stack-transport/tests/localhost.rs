use stack_core::library::Library;
use stack_transport::{
    identity_certificate, identity_init, listen_once, peer_enroll, peer_revoke, sync_now,
};
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
    peer_enroll(
        a,
        group,
        b_id,
        &b_cert,
        &stack_transport::fingerprint(&b_cert),
    )
    .unwrap();
    peer_enroll(
        b,
        group,
        a_id,
        &a_cert,
        &stack_transport::fingerprint(&a_cert),
    )
    .unwrap();
}

fn adopt_by_reviewed_file(a: &Path, b: &Path, group: &str, a_id: &str, b_id: &str, path: &Path) {
    Library::open(a).unwrap().sync_export(b_id, path).unwrap();
    Library::open(b)
        .unwrap()
        .sync_import(path, Some((group, &[a_id.to_owned(), b_id.to_owned()])))
        .unwrap();
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
    let temp = tempfile::Builder::new()
        .prefix("stack-lan-test-")
        .tempdir_in("/tmp")
        .unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a)
        .unwrap()
        .sync_start(std::slice::from_ref(&b_id))
        .unwrap();
    adopt_by_reviewed_file(
        &a,
        &b,
        &group,
        &a_id,
        &b_id,
        &temp.path().join("baseline.stackexchange"),
    );
    // Adoption leaves both sides with the same operations, so the session ends
    // after hello and each records the other's advertised frontier as its receipt.
    let (client, server) = run_pair(a.clone(), b.clone(), port());
    assert_eq!(client["skipped"], true);
    assert_eq!(server["skipped"], true);
    let status = Library::open(&b).unwrap().sync_status().unwrap();
    assert_eq!(status["library"], group);
    assert_eq!(status["pending_gaps"], 0);
    assert_eq!(status["acknowledged_by"][&a_id][&a_id], 1);
    let status = Library::open(&a).unwrap().sync_status().unwrap();
    assert_eq!(status["acknowledged_by"][&b_id][&a_id], 1);
    // A new local operation on one side makes the next session exchange it.
    let mut library = Library::open(&a).unwrap();
    let revision = library.revision().unwrap();
    library
        .edit_stacks(
            revision,
            stack_core::StackEdit::Create {
                name: "Replayed".into(),
                color: 0,
                visible: true,
            },
            &stack_archive::Control::default(),
        )
        .unwrap();
    drop(library);
    let (client, server) = run_pair(a.clone(), b.clone(), port());
    assert_eq!(client["skipped"], serde_json::Value::Null);
    assert_eq!(server["received"]["applied"], 1);
    assert_eq!(client["received"]["applied"], 0);
    assert_eq!(client["status"]["pending_gaps"], 0);
    let (client, server) = run_pair(a, b, port());
    assert_eq!(client["skipped"], true);
    assert_eq!(server["skipped"], true);
}

#[test]
fn unknown_or_revoked_peer_and_wrong_verified_fingerprint_are_rejected() {
    let temp = tempfile::Builder::new()
        .prefix("stack-lan-deny-")
        .tempdir_in("/tmp")
        .unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let c = temp.path().join("c");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    let (_, c_id) = initialized(&c);
    let b_cert = identity_certificate(&b).unwrap();
    assert!(peer_enroll(&a, &group, &b_id, &b_cert, &"0".repeat(64)).is_err());
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a)
        .unwrap()
        .sync_start(std::slice::from_ref(&b_id))
        .unwrap();
    let a_cert = identity_certificate(&a).unwrap();
    peer_enroll(
        &c,
        &group,
        &a_id,
        &a_cert,
        &stack_transport::fingerprint(&a_cert),
    )
    .unwrap();
    let baseline = temp.path().join("wrong-key-baseline.stackexchange");
    Library::open(&a)
        .unwrap()
        .sync_export(&b_id, &baseline)
        .unwrap();
    Library::open(&c)
        .unwrap()
        .sync_import(&baseline, Some((&group, &[a_id.clone(), c_id.clone()])))
        .unwrap();
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
    let temp = tempfile::Builder::new()
        .prefix("stack-lan-interrupt-")
        .tempdir_in("/tmp")
        .unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a)
        .unwrap()
        .sync_start(std::slice::from_ref(&b_id))
        .unwrap();
    adopt_by_reviewed_file(
        &a,
        &b,
        &group,
        &a_id,
        &b_id,
        &temp.path().join("baseline.stackexchange"),
    );
    let before = Library::open(&b).unwrap().sync_status().unwrap();
    let address = port();
    let root = b.clone();
    let listener = std::thread::spawn(move || listen_once(&root, address));
    std::thread::sleep(Duration::from_millis(80));
    let tcp = std::net::TcpStream::connect(address).unwrap();
    drop(tcp);
    assert!(listener.join().unwrap().is_err());
    assert_eq!(Library::open(&b).unwrap().sync_status().unwrap(), before);
    let (_, server) = run_pair(a.clone(), b.clone(), port());
    assert_eq!(server["skipped"], true);
    assert_eq!(
        Library::open(&b).unwrap().sync_status().unwrap()["library"],
        group
    );
}

#[test]
fn enrolled_empty_listener_requires_reviewed_file_adoption() {
    let temp = tempfile::Builder::new()
        .prefix("stack-lan-consent-")
        .tempdir_in("/tmp")
        .unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a)
        .unwrap()
        .sync_start(std::slice::from_ref(&b_id))
        .unwrap();
    let before = Library::open(&b).unwrap().sync_status().unwrap();
    assert!(listen_once(&b, port()).is_err());
    assert_eq!(Library::open(&b).unwrap().sync_status().unwrap(), before);
}

#[test]
fn revocation_while_listener_waits_blocks_session_without_apply() {
    let temp = tempfile::Builder::new()
        .prefix("stack-lan-revoke-")
        .tempdir_in("/tmp")
        .unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a)
        .unwrap()
        .sync_start(std::slice::from_ref(&b_id))
        .unwrap();
    adopt_by_reviewed_file(
        &a,
        &b,
        &group,
        &a_id,
        &b_id,
        &temp.path().join("baseline.stackexchange"),
    );
    let before = Library::open(&b).unwrap().sync_status().unwrap();
    let address = port();
    let root = b.clone();
    let listener = std::thread::spawn(move || listen_once(&root, address));
    std::thread::sleep(Duration::from_millis(80));
    peer_revoke(&b).unwrap();
    assert!(sync_now(&a, address).is_err());
    assert!(listener.join().unwrap().is_err());
    assert_eq!(Library::open(&b).unwrap().sync_status().unwrap(), before);
}

#[test]
fn native_owner_cancels_waiting_and_blocked_handshake_without_changing_the_library() {
    use stack_transport::{SessionControl, listen_once_library};
    use std::time::Instant;
    let temp = tempfile::Builder::new()
        .prefix("stack-lan-cancel-")
        .tempdir_in("/tmp")
        .unwrap();
    let a = temp.path().join("a");
    let b = temp.path().join("b");
    let (group, a_id) = initialized(&a);
    let (_, b_id) = initialized(&b);
    pair(&a, &b, &group, &a_id, &b_id);
    Library::open(&a)
        .unwrap()
        .sync_start(std::slice::from_ref(&b_id))
        .unwrap();
    adopt_by_reviewed_file(&a, &b, &group, &a_id, &b_id, &temp.path().join("baseline"));
    let before = Library::open(&b).unwrap().sync_status().unwrap();
    for handshake in [false, true] {
        let address = port();
        let control = SessionControl::default();
        let token = control.clone();
        let root = b.clone();
        let (finished, wait) = std::sync::mpsc::channel();
        let server = std::thread::spawn(move || {
            let mut library = Library::open(&root).unwrap();
            let result = listen_once_library(&mut library, address, &token);
            // Cancellation leaves the same owner usable for subsequent commands.
            let unchanged = library.sync_status().unwrap();
            finished.send((result.is_err(), unchanged)).unwrap();
        });
        std::thread::sleep(Duration::from_millis(80));
        let stalled_client = handshake.then(|| std::net::TcpStream::connect(address).unwrap());
        if handshake {
            std::thread::sleep(Duration::from_millis(80));
        }
        let start = Instant::now();
        control.cancel();
        let (rejected, status) = wait
            .recv_timeout(Duration::from_secs(2))
            .expect("cancel must interrupt accept and TLS reads");
        assert!(rejected);
        assert!(start.elapsed() < Duration::from_secs(2));
        assert_eq!(status, before);
        drop(stalled_client);
        server.join().unwrap();
    }
}
