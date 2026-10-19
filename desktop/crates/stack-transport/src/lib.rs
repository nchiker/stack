//! Explicit, foreground, two-member LAN transport for Stack exchange bundles.
//! The repository owns validation and durable apply; this module owns only the
//! authenticated, bounded, one-shot delivery of those same bundles.
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection, StreamOwned};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_core::library::Library;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant};

const MAX_BUNDLE: u64 = 64 * 1024 * 1024;
const MAX_CONTROL: u64 = 16 * 1024;
const MAGIC: &[u8; 9] = b"STACKLAN1";
const TLS_NAME: &str = "stackdocs.local";
const TIMEOUT: Duration = Duration::from_secs(45);

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Policy(&'static str),
    #[error("peer authentication failed")]
    Peer,
    #[error("protocol message was invalid")]
    Protocol,
    #[error("{0}")]
    Io(#[from] std::io::Error),
    #[error("{0}")]
    Tls(#[from] rustls::Error),
    #[error("{0}")]
    Json(#[from] serde_json::Error),
    #[error("{0}")]
    Library(#[from] stack_core::library::Error),
}
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Policy {
    version: u32,
    group: String,
    peer_replica: String,
    fingerprint: String,
    revoked: bool,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Hello {
    version: u32,
    group: String,
    replica: String,
}

pub fn fingerprint(cert_der: &[u8]) -> String {
    format!("{:x}", Sha256::digest(cert_der))
}

fn pairing_dir(root: &Path) -> PathBuf { root.join("pairing") }

fn private_dir(root: &Path) -> Result<PathBuf> {
    let dir = pairing_dir(root);
    if !dir.exists() {
        fs::create_dir(&dir)?;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
    }
    let meta = dir.symlink_metadata()?;
    if !meta.is_dir() || meta.file_type().is_symlink() || meta.permissions().mode() & 0o077 != 0 {
        return Err(Error::Policy("Pairing directory must be private (mode 0700)"));
    }
    Ok(dir)
}

fn read_private(path: &Path) -> Result<Vec<u8>> {
    let meta = path.symlink_metadata()?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.permissions().mode() & 0o077 != 0 {
        return Err(Error::Policy("Credential must be a private regular file (mode 0600)"));
    }
    if meta.len() > MAX_CONTROL { return Err(Error::Policy("Credential too large")); }
    Ok(fs::read(path)?)
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    let dir = path.parent().ok_or(Error::Policy("Credential directory missing"))?;
    let temp = dir.join(format!(".credential-{}.tmp", std::process::id()));
    let result = (|| {
        let mut file = OpenOptions::new().write(true).create_new(true).mode(0o600).open(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&temp, path)?;
        File::open(dir)?.sync_all()?;
        Ok(())
    })();
    let _ = fs::remove_file(temp);
    result
}

/// Create a locally protected, fresh X.509 identity. OpenSSL is invoked as a
/// maintained certificate generator; TLS and chain validation use rustls.
pub fn identity_init(root: &Path) -> Result<String> {
    let dir = private_dir(root)?;
    let cert = dir.join("identity.der");
    let key = dir.join("identity-key.der");
    if cert.exists() || key.exists() { return Err(Error::Policy("Identity already exists")); }
    let temporary = tempfile::Builder::new().prefix("identity-").tempdir_in(&dir)?;
    let pem_key = temporary.path().join("key.pem");
    let pem_cert = temporary.path().join("cert.pem");
    let extension = "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth,clientAuth\nsubjectAltName=DNS:stackdocs.local";
    let status = Command::new("openssl")
        .args(["req", "-new", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "3650", "-subj", "/CN=Stack paired device", "-addext", extension, "-keyout"])
        .arg(&pem_key).arg("-out").arg(&pem_cert).stdout(Stdio::null()).stderr(Stdio::null()).status()?;
    if !status.success() { return Err(Error::Policy("OpenSSL identity generation failed")); }
    let status = Command::new("openssl").args(["x509", "-in"]).arg(&pem_cert)
        .args(["-outform", "DER", "-out"]).arg(&cert).stdout(Stdio::null()).stderr(Stdio::null()).status()?;
    if !status.success() { return Err(Error::Policy("OpenSSL certificate conversion failed")); }
    let status = Command::new("openssl").args(["pkcs8", "-topk8", "-nocrypt", "-in"])
        .arg(&pem_key).args(["-outform", "DER", "-out"]).arg(&key).stdout(Stdio::null()).stderr(Stdio::null()).status()?;
    if !status.success() { return Err(Error::Policy("OpenSSL key conversion failed")); }
    fs::set_permissions(&cert, fs::Permissions::from_mode(0o600))?;
    fs::set_permissions(&key, fs::Permissions::from_mode(0o600))?;
    File::open(&cert)?.sync_all()?;
    File::open(&key)?.sync_all()?;
    File::open(&dir)?.sync_all()?;
    Ok(fingerprint(&read_private(&cert)?))
}

pub fn identity_certificate(root: &Path) -> Result<Vec<u8>> {
    read_private(&private_dir(root)?.join("identity.der"))
}

/// Enrollment follows an out-of-band comparison of the displayed SHA-256
/// fingerprint. A discovered address or unverified certificate cannot enroll.
pub fn peer_enroll(root: &Path, group: &str, peer_replica: &str, cert_der: &[u8], verified: &str) -> Result<()> {
    let dir = private_dir(root)?;
    let own = fingerprint(&identity_certificate(root)?);
    let pin = fingerprint(cert_der);
    if cert_der.is_empty() || cert_der.len() as u64 > MAX_CONTROL || pin != verified.to_ascii_lowercase() || pin == own {
        return Err(Error::Policy("Verified peer fingerprint does not match certificate"));
    }
    if group.is_empty() || peer_replica.is_empty() || group.len() > 128 || peer_replica.len() > 128 {
        return Err(Error::Policy("Invalid group or replica identifier"));
    }
    let status = Library::open(root)?.sync_status()?;
    if status["replica"] == peer_replica { return Err(Error::Policy("Cannot enroll own replica")); }
    if status["adopted"] == true && (status["library"] != group || status["members"].as_array().is_none_or(|m| m.len() != 2 || !m.iter().any(|r| r == peer_replica))) {
        return Err(Error::Policy("Transport requires the frozen two-member group"));
    }
    let policy_path = dir.join("policy.json");
    if policy_path.exists() { return Err(Error::Policy("Peer already enrolled; revoke and create a fresh identity to re-pair")); }
    // rustls parses this as a trust anchor at connection setup, rejecting bad DER.
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(cert_der.to_vec()))?;
    write_private(&dir.join("peer.der"), cert_der)?;
    write_private(&policy_path, &serde_json::to_vec(&Policy {version: 1, group: group.into(), peer_replica: peer_replica.into(), fingerprint: pin, revoked: false})?)?;
    Ok(())
}

pub fn peer_revoke(root: &Path) -> Result<()> {
    let dir = private_dir(root)?;
    let mut policy: Policy = serde_json::from_slice(&read_private(&dir.join("policy.json"))?)?;
    policy.revoked = true;
    write_private(&dir.join("policy.json"), &serde_json::to_vec(&policy)?)?;
    Ok(())
}

fn credentials(root: &Path) -> Result<(Policy, Vec<CertificateDer<'static>>, PrivateKeyDer<'static>, RootCertStore)> {
    let dir = private_dir(root)?;
    let policy: Policy = serde_json::from_slice(&read_private(&dir.join("policy.json"))?)?;
    if policy.version != 1 || policy.revoked { return Err(Error::Policy("Peer revoked or policy unsupported")); }
    let peer = read_private(&dir.join("peer.der"))?;
    if fingerprint(&peer) != policy.fingerprint { return Err(Error::Peer); }
    let cert = CertificateDer::from(read_private(&dir.join("identity.der"))?);
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(read_private(&dir.join("identity-key.der"))?));
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(peer))?;
    Ok((policy, vec![cert], key, roots))
}

fn check_group(library: &Library, policy: &Policy) -> Result<Hello> {
    let status = library.sync_status()?;
    let replica = status["replica"].as_str().ok_or(Error::Protocol)?.to_owned();
    if status["adopted"] == true {
        let members = status["members"].as_array().ok_or(Error::Protocol)?;
        if status["library"] != policy.group || members.len() != 2
            || !members.iter().any(|r| r == &replica)
            || !members.iter().any(|r| r == &policy.peer_replica) {
            return Err(Error::Policy("Network sync supports only the frozen two-member group"));
        }
    }
    Ok(Hello {version: 1, group: policy.group.clone(), replica})
}

fn auth_peer(certificates: Option<&[CertificateDer<'_>]>, policy: &Policy) -> Result<()> {
    let cert = certificates.and_then(|certs| certs.first()).ok_or(Error::Peer)?;
    if fingerprint(cert.as_ref()) != policy.fingerprint { return Err(Error::Peer); }
    Ok(())
}

fn configure_tcp(stream: &TcpStream) -> Result<()> {
    stream.set_read_timeout(Some(TIMEOUT))?;
    stream.set_write_timeout(Some(TIMEOUT))?;
    stream.set_nodelay(true)?;
    Ok(())
}

fn send_frame(stream: &mut impl Write, kind: u8, bytes: &[u8], limit: u64) -> Result<()> {
    if bytes.len() as u64 > limit { return Err(Error::Policy("Transfer size limit exceeded")); }
    stream.write_all(MAGIC)?;
    stream.write_all(&[kind])?;
    stream.write_all(&(bytes.len() as u64).to_be_bytes())?;
    stream.write_all(bytes)?;
    stream.flush()?;
    Ok(())
}

fn receive_frame(stream: &mut impl Read, kind: u8, limit: u64) -> Result<Vec<u8>> {
    let mut header = [0u8; 18];
    stream.read_exact(&mut header)?;
    if &header[..9] != MAGIC || header[9] != kind { return Err(Error::Protocol); }
    let len = u64::from_be_bytes(header[10..18].try_into().map_err(|_| Error::Protocol)?);
    if len > limit { return Err(Error::Policy("Transfer size limit exceeded")); }
    let mut bytes = vec![0; len as usize];
    stream.read_exact(&mut bytes)?;
    Ok(bytes)
}

fn hello(stream: &mut (impl Read + Write), local: &Hello, policy: &Policy, initiator: bool) -> Result<()> {
    if initiator { send_frame(stream, b'H', &serde_json::to_vec(local)?, MAX_CONTROL)?; }
    let remote: Hello = serde_json::from_slice(&receive_frame(stream, b'H', MAX_CONTROL)?)?;
    if remote.version != 1 || remote.group != policy.group || remote.replica != policy.peer_replica { return Err(Error::Peer); }
    if !initiator { send_frame(stream, b'H', &serde_json::to_vec(local)?, MAX_CONTROL)?; }
    Ok(())
}

fn send_bundle(stream: &mut impl Write, library: &Library, root: &Path, peer: &str) -> Result<()> {
    let temp = tempfile::Builder::new().prefix("lan-send-").tempdir_in(root)?;
    let path = temp.path().join("exchange.stackexchange");
    library.sync_export(peer, &path)?;
    let size = fs::metadata(&path)?.len();
    if size > MAX_BUNDLE { return Err(Error::Policy("Bundle exceeds 64 MiB LAN limit; use folder exchange")); }
    let bytes = fs::read(&path)?;
    send_frame(stream, b'B', &bytes, MAX_BUNDLE)
}

fn receive_bundle(stream: &mut (impl Read + Write), library: &mut Library, root: &Path, policy: &Policy) -> Result<Value> {
    let bytes = receive_frame(stream, b'B', MAX_BUNDLE)?;
    let temp = tempfile::Builder::new().prefix("lan-receive-").tempdir_in(root)?;
    let path = temp.path().join("exchange.stackexchange");
    let mut file = OpenOptions::new().write(true).create_new(true).mode(0o600).open(&path)?;
    file.write_all(&bytes)?;
    file.sync_all()?;
    let own = check_group(library, policy)?;
    let result = if library.sync_status()?["adopted"] == true {
        library.sync_import(&path, None)?
    } else {
        let members = vec![own.replica, policy.peer_replica.clone()];
        library.sync_import(&path, Some((&policy.group, &members)))?
    };
    send_frame(stream, b'R', &serde_json::to_vec(&result)?, MAX_CONTROL)?;
    Ok(result)
}

fn receive_receipt(stream: &mut impl Read) -> Result<Value> {
    let result: Value = serde_json::from_slice(&receive_frame(stream, b'R', MAX_CONTROL)?)?;
    if !result["durable_frontier"].is_object() { return Err(Error::Protocol); }
    Ok(result)
}

/// A single foreground connection. The caller chooses a manual address and
/// starts this action explicitly. No background listener or discovery runs.
pub fn sync_now(root: &Path, address: SocketAddr) -> Result<Value> {
    let (policy, cert, key, roots) = credentials(root)?;
    let mut library = Library::open(root)?;
    if library.sync_status()?["adopted"] != true { return Err(Error::Policy("Start or adopt the group before initiating Sync now")); }
    let local = check_group(&library, &policy)?;
    let config = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions().map_err(Error::Tls)?
        .with_root_certificates(roots).with_client_auth_cert(cert, key)?;
    let tcp = TcpStream::connect_timeout(&address, TIMEOUT)?;
    configure_tcp(&tcp)?;
    let name = ServerName::try_from(TLS_NAME).map_err(|_| Error::Protocol)?.to_owned();
    let mut tls = StreamOwned::new(ClientConnection::new(Arc::new(config), name)?, tcp);
    tls.conn.complete_io(&mut tls.sock)?;
    auth_peer(tls.conn.peer_certificates(), &policy)?;
    hello(&mut tls, &local, &policy, true)?;
    send_bundle(&mut tls, &library, root, &policy.peer_replica)?;
    let first_ack = receive_receipt(&mut tls)?;
    let received = receive_bundle(&mut tls, &mut library, root, &policy)?;
    send_bundle(&mut tls, &library, root, &policy.peer_replica)?;
    let final_ack = receive_receipt(&mut tls)?;
    Ok(json!({"peer":policy.peer_replica,"first_ack":first_ack,"received":received,"final_ack":final_ack,"status":library.sync_status()?}))
}

/// One bounded foreground listener; returns after one authenticated session.
/// Binding is explicit. Unknown TLS clients cannot read any library bytes.
pub fn listen_once(root: &Path, address: SocketAddr) -> Result<Value> {
    let (policy, cert, key, roots) = credentials(root)?;
    let mut library = Library::open(root)?;
    let local = check_group(&library, &policy)?;
    let verifier = rustls::server::WebPkiClientVerifier::builder_with_provider(Arc::new(roots), Arc::new(rustls::crypto::ring::default_provider()))
        .build().map_err(|_| Error::Policy("Invalid enrolled client certificate"))?;
    let config = ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions().map_err(Error::Tls)?
        .with_client_cert_verifier(verifier).with_single_cert(cert, key)?;
    let listener = TcpListener::bind(address)?;
    listener.set_nonblocking(true)?;
    let until = Instant::now() + TIMEOUT;
    let tcp = loop {
        match listener.accept() {
            Ok((stream, _)) => break stream,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < until => std::thread::sleep(Duration::from_millis(25)),
            Err(e) => return Err(e.into()),
        }
    };
    configure_tcp(&tcp)?;
    let mut tls = StreamOwned::new(ServerConnection::new(Arc::new(config))?, tcp);
    tls.conn.complete_io(&mut tls.sock)?;
    auth_peer(tls.conn.peer_certificates(), &policy)?;
    hello(&mut tls, &local, &policy, false)?;
    let first = receive_bundle(&mut tls, &mut library, root, &policy)?;
    send_bundle(&mut tls, &library, root, &policy.peer_replica)?;
    let peer_ack = receive_receipt(&mut tls)?;
    let final_receipt = receive_bundle(&mut tls, &mut library, root, &policy)?;
    Ok(json!({"peer":policy.peer_replica,"received":first,"peer_ack":peer_ack,"final_receipt":final_receipt,"status":library.sync_status()?}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn framing_rejects_oversize_wrong_kind_and_truncation() {
        let mut oversized = Vec::new();
        oversized.extend_from_slice(MAGIC);
        oversized.push(b'B');
        oversized.extend_from_slice(&(MAX_BUNDLE + 1).to_be_bytes());
        assert!(matches!(receive_frame(&mut oversized.as_slice(), b'B', MAX_BUNDLE), Err(Error::Policy(_))));
        let mut wrong = Vec::new();
        send_frame(&mut wrong, b'R', b"{}", MAX_CONTROL).unwrap();
        assert!(matches!(receive_frame(&mut wrong.as_slice(), b'B', MAX_CONTROL), Err(Error::Protocol)));
        wrong.pop();
        assert!(receive_frame(&mut wrong.as_slice(), b'R', MAX_CONTROL).is_err());
    }

    #[test]
    fn authenticated_corrupt_bundle_rejected_then_clean_retry_adopts() {
        let temp = tempfile::Builder::new().prefix("stack-lan-corrupt-").tempdir_in("/tmp").unwrap();
        let a = temp.path().join("a");
        let b = temp.path().join("b");
        let al = Library::open(&a).unwrap();
        let a_status = al.sync_status().unwrap();
        let group = al.status().unwrap().library_id;
        let a_replica = a_status["replica"].as_str().unwrap().to_owned();
        drop(al);
        let bl = Library::open(&b).unwrap();
        let b_replica = bl.sync_status().unwrap()["replica"].as_str().unwrap().to_owned();
        drop(bl);
        identity_init(&a).unwrap();
        identity_init(&b).unwrap();
        let a_cert = identity_certificate(&a).unwrap();
        let b_cert = identity_certificate(&b).unwrap();
        peer_enroll(&a, &group, &b_replica, &b_cert, &fingerprint(&b_cert)).unwrap();
        peer_enroll(&b, &group, &a_replica, &a_cert, &fingerprint(&a_cert)).unwrap();
        Library::open(&a).unwrap().sync_start(&[b_replica]).unwrap();
        let (policy, cert, key, roots) = credentials(&a).unwrap();
        let local = check_group(&Library::open(&a).unwrap(), &policy).unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        drop(listener);
        let server_root = b.clone();
        let server = std::thread::spawn(move || listen_once(&server_root, address));
        std::thread::sleep(Duration::from_millis(80));
        let config = ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions().unwrap()
            .with_root_certificates(roots).with_client_auth_cert(cert, key).unwrap();
        let tcp = TcpStream::connect(address).unwrap();
        configure_tcp(&tcp).unwrap();
        let name = ServerName::try_from(TLS_NAME).unwrap().to_owned();
        let mut tls = StreamOwned::new(ClientConnection::new(Arc::new(config), name).unwrap(), tcp);
        tls.conn.complete_io(&mut tls.sock).unwrap();
        auth_peer(tls.conn.peer_certificates(), &policy).unwrap();
        hello(&mut tls, &local, &policy, true).unwrap();
        send_frame(&mut tls, b'B', b"corrupt exchange", MAX_BUNDLE).unwrap();
        drop(tls);
        assert!(server.join().unwrap().is_err());
        assert_eq!(Library::open(&b).unwrap().sync_status().unwrap()["adopted"], false);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        drop(listener);
        let server = std::thread::spawn(move || listen_once(&b, address));
        std::thread::sleep(Duration::from_millis(80));
        assert!(sync_now(&a, address).is_ok());
        assert!(server.join().unwrap().is_ok());
    }
}
