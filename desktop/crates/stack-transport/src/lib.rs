//! Explicit, foreground, two-member LAN transport for Stack exchange bundles.
//! The repository owns validation and durable apply; this module owns only the
//! authenticated, bounded, one-shot delivery of those same bundles.
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName};
use rustls::{
    ClientConfig, ClientConnection, RootCertStore, ServerConfig, ServerConnection, StreamOwned,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_core::library::Library;
use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub mod pairing;

// STACKEX1: at most 8 GiB originals, 32 MiB metadata, and 12-byte prefix.
const MAX_BUNDLE: u64 = 8 * 1024 * 1024 * 1024 + 32 * 1024 * 1024 + 12;
const MAX_CONTROL: u64 = 16 * 1024;
const CHUNK: usize = 64 * 1024;
const MAGIC: &[u8; 9] = b"STACKLAN1";
const TLS_NAME: &str = "stackdocs.local";
/// TLS 1.3 only, on every connection (sync and pairing). Android 10+ supports it.
pub(crate) const TLS_VERSIONS: &[&rustls::SupportedProtocolVersion] = &[&rustls::version::TLS13];
const TIMEOUT: Duration = Duration::from_secs(45);
// Only authenticated, group-checked peers get time for populated-library apply.
const PROCESSING_TIMEOUT: Duration = Duration::from_secs(180);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(8);

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error(transparent)]
    Archive(#[from] stack_archive::Error),
    #[error("Network session canceled; committed exchanges remain saved")]
    Canceled,
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

/// A single foreground session. Cancel also interrupts a blocked TLS read/write;
/// archive work checks the same token before publication and durable apply.
#[derive(Clone, Default)]
pub struct SessionControl {
    archive: stack_archive::Control,
    socket: Arc<Mutex<Option<TcpStream>>>,
}

impl SessionControl {
    pub fn cancel(&self) {
        self.archive.cancel();
        if let Some(socket) = self.socket.lock().expect("session socket").as_ref() {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }

    fn check(&self) -> Result<()> {
        self.archive.check().map_err(|_| Error::Canceled)
    }

    fn attach(&self, socket: &TcpStream) -> Result<SessionSocket> {
        *self.socket.lock().expect("session socket") = Some(socket.try_clone()?);
        // Handles cancellation racing with connection establishment.
        if self.check().is_err() {
            self.cancel();
            return Err(Error::Canceled);
        }
        Ok(SessionSocket(self.clone()))
    }
}

struct SessionSocket(SessionControl);
impl Drop for SessionSocket {
    fn drop(&mut self) {
        self.0.socket.lock().expect("session socket").take();
    }
}

/// Read public pairing state without opening another library writer or creating
/// credentials. Private keys and certificate bytes never enter this projection.
pub fn pairing_status(root: &Path) -> Result<Value> {
    let dir = pairing_dir(root);
    if !dir.exists() {
        return Ok(json!({"identity_initialized":false,"enrolled":false}));
    }
    let dir = private_dir(root)?;
    let certificate = dir.join("identity.der");
    let mut status = if certificate.exists() {
        json!({"identity_initialized":true,"fingerprint_sha256":fingerprint(&read_private(&certificate)?),"certificate_path":certificate})
    } else {
        json!({"identity_initialized":false})
    };
    let policy = dir.join("policy.json");
    if policy.exists() {
        let policy: Policy = serde_json::from_slice(&read_private(&policy)?)?;
        status["enrolled"] = json!(!policy.revoked && policy.version == 1);
        status["peer"] = serde_json::to_value(policy)?;
    } else {
        status["enrolled"] = json!(false);
    }
    Ok(status)
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
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
    /// Advertised only to peers that sent one first: version 1 peers reject
    /// unknown hello fields. Equal frontiers end the session after the hello.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    frontier: Option<BTreeMap<String, u64>>,
    /// Optional feature list, e.g. `["links"]`. Sent when
    /// `ANNOUNCE_LINKS` is true (D4b, live); always accepted.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    features: Option<Vec<String>>,
    /// Sent with `features` by a device that announces `links`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    enabled: Option<bool>,
}

/// D4b switch (slice 6, section 5): this build announces `features: ["links"]` and the real `enabled`.
/// Flipped last, once links can be applied, kept, resolved and purged (D5, P1, P2, D6 all shipped).
const ANNOUNCE_LINKS: bool = true;
/// How long a refused ladder level is remembered for one peer.
const REFUSAL_MEMORY: Duration = Duration::from_secs(15 * 60);

/// What the peer's hello said about optional features. Parsed on receive only.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PeerHello {
    features: Option<Vec<String>>,
    enabled: Option<bool>,
}

impl PeerHello {
    /// True when the peer announced the `links` feature.
    pub fn announces_links(&self) -> bool {
        self.features
            .as_ref()
            .is_some_and(|f| f.iter().any(|x| x == "links"))
    }
    /// The peer's reported `enabled` flag; `None` when it did not report one.
    pub fn links_enabled(&self) -> Option<bool> {
        self.enabled
    }
    /// Every feature string the peer announced (empty when none).
    pub fn features(&self) -> &[String] {
        self.features.as_deref().unwrap_or(&[])
    }
}

/// Parse a received hello frame body. Unknown fields are still refused.
pub fn parse_peer_hello(body: &[u8]) -> Result<PeerHello> {
    let hello = parse_remote_hello(body)?;
    Ok(PeerHello {
        features: hello.features,
        enabled: hello.enabled,
    })
}

const MAX_FEATURES: usize = 16;
const MAX_FEATURE_UNITS: usize = 64;

/// A received hello, with the same strict `features` rules as the phone: an explicit `null` for `features` or
/// `enabled`, a duplicate, empty or over-long entry, or more than 16 entries is refused. Unknown feature names
/// are accepted and ignored. A hello without these fields (every existing one) parses exactly as before.
fn parse_remote_hello(body: &[u8]) -> Result<Hello> {
    let hello: Hello = serde_json::from_slice(body)?;
    let raw: serde_json::Value = serde_json::from_slice(body)?;
    if raw.get("features").is_some_and(serde_json::Value::is_null)
        || raw.get("enabled").is_some_and(serde_json::Value::is_null)
    {
        return Err(Error::Protocol);
    }
    if let Some(features) = &hello.features {
        let mut seen = std::collections::BTreeSet::new();
        if features.len() > MAX_FEATURES
            || features.iter().any(|f| {
                f.is_empty() || f.encode_utf16().count() > MAX_FEATURE_UNITS || !seen.insert(f)
            })
        {
            return Err(Error::Protocol);
        }
    }
    Ok(hello)
}

/// Section 5 / M1 equal-frontier rule: skip only if both announce `links` and
/// both report enabled, or if either does not announce `links`.
pub fn equal_frontier_may_skip(
    local_announces_links: bool,
    local_enabled: bool,
    peer: &PeerHello,
) -> bool {
    if !local_announces_links || !peer.announces_links() {
        return true;
    }
    local_enabled && peer.enabled == Some(true)
}

/// Called after the hello exchange, before any bundle moves (so a D5 enable
/// op lands after the reply and before `receive_bundle`). Not called when the
/// session is skipped on equal frontiers.
pub type HelloHook<'a> = &'a mut dyn FnMut(&mut Library, &PeerHello) -> Result<()>;

/// The real hello hook, used by every default entry point and by the app: a peer that announced `links` makes this
/// side enable once (section 5, after the hello reply and before the first bundle). A failed enable is logged
/// (fixed text, no paths or secrets) and the session carries on: the hello already went out with the pre-enable
/// state (`enabled=false`), so the M1 rule cannot have skipped wrongly, and the next session retries the enable.
pub fn enable_links_on_hello(library: &mut Library, peer: &PeerHello) -> Result<()> {
    if library.sync_links_on_hello(peer.announces_links()).is_err() {
        eprintln!("Link sync could not be switched on this time; carrying on without it");
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum Level {
    FrontierFeatures,
    Frontier,
    Plain,
}

fn level_hello(local: &Hello, level: Level) -> Hello {
    let mut hello = Hello {
        version: local.version,
        group: local.group.clone(),
        replica: local.replica.clone(),
        frontier: local.frontier.clone(),
        features: local.features.clone(),
        enabled: local.enabled,
    };
    if level >= Level::Frontier {
        hello.features = None;
        hello.enabled = None;
    }
    if level >= Level::Plain {
        hello.frontier = None;
    }
    hello
}

fn ladder(local: &Hello) -> Vec<Level> {
    let mut levels = Vec::new();
    if local.features.is_some() {
        levels.push(Level::FrontierFeatures);
    }
    levels.push(Level::Frontier);
    levels.push(Level::Plain);
    levels
}

/// A refusal means the peer could not use this hello shape (closed, timed out,
/// answered garbage). Identity, policy and cancel errors are never refusals,
/// and only errors raised while waiting for the hello reply can be one.
fn is_refusal(e: &Error) -> bool {
    matches!(e, Error::Io(_) | Error::Json(_) | Error::Protocol)
}

/// An attempt failure: `refusal` is true only for a hello-phase refusal.
struct Failed {
    error: Error,
    refusal: bool,
}

impl<E: Into<Error>> From<E> for Failed {
    fn from(error: E) -> Self {
        Failed {
            error: error.into(),
            refusal: false,
        }
    }
}

fn refused() -> &'static Mutex<BTreeMap<String, (Level, Instant)>> {
    static MAP: Mutex<BTreeMap<String, (Level, Instant)>> = Mutex::new(BTreeMap::new());
    &MAP
}

/// Walk the levels within ONE attempt: the first accepted level wins; a
/// refusal moves down and is remembered (per peer) for 15 minutes. The last
/// level's error is returned as is.
fn run_ladder<T>(
    peer: &str,
    levels: &[Level],
    now: Instant,
    mut attempt: impl FnMut(Level) -> std::result::Result<T, Failed>,
) -> Result<T> {
    let floor = {
        let map = refused().lock().unwrap_or_else(|e| e.into_inner());
        match map.get(peer) {
            Some((l, at)) if now.saturating_duration_since(*at) < REFUSAL_MEMORY => Some(*l),
            _ => None,
        }
    };
    let mut start = levels
        .iter()
        .position(|l| floor.is_none_or(|f| *l >= f))
        .unwrap_or(levels.len() - 1);
    loop {
        let level = levels[start];
        match attempt(level) {
            Err(f) if f.refusal && start + 1 < levels.len() => {
                start += 1;
                refused()
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .insert(peer.to_owned(), (levels[start], now));
            }
            Ok(v) => return Ok(v),
            Err(f) => return Err(f.error),
        }
    }
}

pub fn fingerprint(cert_der: &[u8]) -> String {
    format!("{:x}", Sha256::digest(cert_der))
}

fn pairing_dir(root: &Path) -> PathBuf {
    root.join("pairing")
}

fn private_dir(root: &Path) -> Result<PathBuf> {
    let dir = pairing_dir(root);
    if !dir.exists() {
        fs::create_dir(&dir)?;
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
    }
    let meta = dir.symlink_metadata()?;
    if !meta.is_dir() || meta.file_type().is_symlink() || meta.permissions().mode() & 0o077 != 0 {
        return Err(Error::Policy(
            "Pairing directory must be private (mode 0700)",
        ));
    }
    Ok(dir)
}

fn read_private(path: &Path) -> Result<Vec<u8>> {
    let meta = path.symlink_metadata()?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.permissions().mode() & 0o077 != 0 {
        return Err(Error::Policy(
            "Credential must be a private regular file (mode 0600)",
        ));
    }
    if meta.len() > MAX_CONTROL {
        return Err(Error::Policy("Credential too large"));
    }
    Ok(fs::read(path)?)
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    let dir = path
        .parent()
        .ok_or(Error::Policy("Credential directory missing"))?;
    let temp = dir.join(format!(".credential-{}.tmp", std::process::id()));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temp)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&temp, path)?;
        File::open(dir)?.sync_all()?;
        Ok(())
    })();
    let _ = fs::remove_file(temp);
    result
}

/// Create a locally protected, fresh X.509 identity, generated in-process (rcgen
/// on ring, the same provider rustls uses): ECDSA P-256 / SHA-256, CN "Stack
/// paired device", SAN DNS:stackdocs.local, basicConstraints CA:FALSE,
/// keyUsage digitalSignature, extendedKeyUsage serverAuth+clientAuth, ten years.
pub fn identity_init(root: &Path) -> Result<String> {
    let dir = private_dir(root)?;
    let cert = dir.join("identity.der");
    let key = dir.join("identity-key.der");
    if cert.exists() || key.exists() {
        return Err(Error::Policy("Identity already exists"));
    }
    let (cert_der, key_der) = new_identity()?;
    write_private(&key, &key_der)?;
    write_private(&cert, &cert_der)?;
    Ok(fingerprint(&read_private(&cert)?))
}

/// Certificate and PKCS#8 key (both DER) for a new paired-device identity.
fn new_identity() -> Result<(Vec<u8>, Vec<u8>)> {
    use rcgen::{
        CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, IsCa, KeyPair,
        KeyUsagePurpose,
    };
    let failed = |_| Error::Policy("Identity generation failed");
    let key_pair = KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(failed)?;
    let mut params = CertificateParams::new(vec![TLS_NAME.to_owned()]).map_err(failed)?;
    let mut name = DistinguishedName::new();
    name.push(DnType::CommonName, "Stack paired device");
    params.distinguished_name = name;
    params.is_ca = IsCa::ExplicitNoCa;
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![
        ExtendedKeyUsagePurpose::ServerAuth,
        ExtendedKeyUsagePurpose::ClientAuth,
    ];
    let now = stack_core::clock::now();
    params.not_before = now.into();
    params.not_after = (now + std::time::Duration::from_secs(3650 * 24 * 60 * 60)).into();
    let certificate = params.self_signed(&key_pair).map_err(failed)?;
    Ok((certificate.der().to_vec(), key_pair.serialize_der()))
}

pub fn identity_certificate(root: &Path) -> Result<Vec<u8>> {
    read_private(&private_dir(root)?.join("identity.der"))
}

/// Enrollment follows an out-of-band comparison of the displayed SHA-256
/// fingerprint. A discovered address or unverified certificate cannot enroll.
pub fn peer_enroll(
    root: &Path,
    group: &str,
    peer_replica: &str,
    cert_der: &[u8],
    verified: &str,
) -> Result<()> {
    peer_enroll_library(
        &Library::open(root)?,
        group,
        peer_replica,
        cert_der,
        verified,
    )
}

/// Native applications reuse their serialized repository owner.
pub fn peer_enroll_library(
    library: &Library,
    group: &str,
    peer_replica: &str,
    cert_der: &[u8],
    verified: &str,
) -> Result<()> {
    let root = library.root();
    let dir = private_dir(root)?;
    let own = fingerprint(&identity_certificate(root)?);
    let pin = fingerprint(cert_der);
    if cert_der.is_empty()
        || cert_der.len() as u64 > MAX_CONTROL
        || pin != verified.to_ascii_lowercase()
        || pin == own
    {
        return Err(Error::Policy(
            "Verified peer fingerprint does not match certificate",
        ));
    }
    if group.is_empty() || peer_replica.is_empty() || group.len() > 128 || peer_replica.len() > 128
    {
        return Err(Error::Policy("Invalid group or replica identifier"));
    }
    let status = library.sync_status()?;
    if status["replica"] == peer_replica {
        return Err(Error::Policy("Cannot enroll own replica"));
    }
    if status["adopted"] == true
        && (status["library"] != group
            || status["members"]
                .as_array()
                .is_none_or(|m| m.len() != 2 || !m.iter().any(|r| r == peer_replica)))
    {
        return Err(Error::Policy(
            "Transport requires the frozen two-member group",
        ));
    }
    let policy_path = dir.join("policy.json");
    if policy_path.exists() {
        return Err(Error::Policy(
            "Peer already enrolled; revoke and create a fresh identity to re-pair",
        ));
    }
    // rustls parses this as a trust anchor at connection setup, rejecting bad DER.
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(cert_der.to_vec()))?;
    write_private(&dir.join("peer.der"), cert_der)?;
    write_private(
        &policy_path,
        &serde_json::to_vec(&Policy {
            version: 1,
            group: group.into(),
            peer_replica: peer_replica.into(),
            fingerprint: pin,
            revoked: false,
        })?,
    )?;
    Ok(())
}

pub fn peer_revoke(root: &Path) -> Result<()> {
    let dir = private_dir(root)?;
    let mut policy: Policy = serde_json::from_slice(&read_private(&dir.join("policy.json"))?)?;
    policy.revoked = true;
    write_private(&dir.join("policy.json"), &serde_json::to_vec(&policy)?)?;
    Ok(())
}

fn credentials(
    root: &Path,
) -> Result<(
    Policy,
    Vec<CertificateDer<'static>>,
    PrivateKeyDer<'static>,
    RootCertStore,
)> {
    let dir = private_dir(root)?;
    let policy: Policy = serde_json::from_slice(&read_private(&dir.join("policy.json"))?)?;
    if policy.version != 1 || policy.revoked {
        return Err(Error::Policy("Peer revoked or policy unsupported"));
    }
    let peer = read_private(&dir.join("peer.der"))?;
    if fingerprint(&peer) != policy.fingerprint {
        return Err(Error::Peer);
    }
    let cert = CertificateDer::from(read_private(&dir.join("identity.der"))?);
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(read_private(
        &dir.join("identity-key.der"),
    )?));
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(peer))?;
    Ok((policy, vec![cert], key, roots))
}

fn ensure_policy_current(root: &Path, expected: &Policy) -> Result<()> {
    let dir = private_dir(root)?;
    let current: Policy = serde_json::from_slice(&read_private(&dir.join("policy.json"))?)?;
    if current.revoked || current != *expected {
        return Err(Error::Policy(
            "Paired peer changed or revoked during session",
        ));
    }
    if fingerprint(&read_private(&dir.join("peer.der"))?) != expected.fingerprint {
        return Err(Error::Peer);
    }
    Ok(())
}

fn check_group(library: &Library, policy: &Policy) -> Result<Hello> {
    let status = library.sync_summary()?;
    let replica = status["replica"]
        .as_str()
        .ok_or(Error::Protocol)?
        .to_owned();
    if status["adopted"] != true {
        return Err(Error::Policy(
            "Review and adopt the baseline by file before enabling LAN sync",
        ));
    }
    let members = status["members"].as_array().ok_or(Error::Protocol)?;
    if status["library"] != policy.group
        || members.len() != 2
        || !members.iter().any(|r| r == &replica)
        || !members.iter().any(|r| r == &policy.peer_replica)
    {
        return Err(Error::Policy(
            "Network sync supports only the frozen two-member group",
        ));
    }
    Ok(Hello {
        version: 1,
        group: policy.group.clone(),
        replica,
        frontier: Some(serde_json::from_value(status["frontier"].clone())?),
        features: ANNOUNCE_LINKS.then(|| vec!["links".to_owned()]),
        enabled: if ANNOUNCE_LINKS {
            Some(library.sync_links_enabled()?)
        } else {
            None
        },
    })
}

fn auth_peer(certificates: Option<&[CertificateDer<'_>]>, policy: &Policy) -> Result<()> {
    let cert = certificates
        .and_then(|certs| certs.first())
        .ok_or(Error::Peer)?;
    if fingerprint(cert.as_ref()) != policy.fingerprint {
        return Err(Error::Peer);
    }
    Ok(())
}

fn configure_tcp(stream: &TcpStream) -> Result<()> {
    stream.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
    stream.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
    stream.set_nodelay(true)?;
    Ok(())
}

fn configure_data_tcp(stream: &TcpStream) -> Result<()> {
    stream.set_read_timeout(Some(TIMEOUT))?;
    stream.set_write_timeout(Some(TIMEOUT))?;
    Ok(())
}

fn send_frame(stream: &mut impl Write, kind: u8, bytes: &[u8], limit: u64) -> Result<()> {
    if bytes.len() as u64 > limit {
        return Err(Error::Policy("Transfer size limit exceeded"));
    }
    send_header(stream, kind, bytes.len() as u64)?;
    stream.write_all(bytes)?;
    stream.flush()?;
    Ok(())
}

fn receive_frame(stream: &mut impl Read, kind: u8, limit: u64) -> Result<Vec<u8>> {
    let len = receive_header(stream, kind, limit)?;
    let mut bytes = vec![0; len as usize];
    stream.read_exact(&mut bytes)?;
    Ok(bytes)
}

fn send_header(stream: &mut impl Write, kind: u8, len: u64) -> Result<()> {
    stream.write_all(MAGIC)?;
    stream.write_all(&[kind])?;
    stream.write_all(&len.to_be_bytes())?;
    Ok(())
}

fn receive_header(stream: &mut impl Read, kind: u8, limit: u64) -> Result<u64> {
    let mut header = [0u8; 18];
    stream.read_exact(&mut header)?;
    if &header[..9] != MAGIC || header[9] != kind {
        return Err(Error::Protocol);
    }
    let len = u64::from_be_bytes(header[10..18].try_into().map_err(|_| Error::Protocol)?);
    if len > limit {
        return Err(Error::Policy("Transfer size limit exceeded"));
    }
    Ok(len)
}

fn copy_exact(
    input: &mut impl Read,
    output: &mut impl Write,
    mut remaining: u64,
    control: &SessionControl,
) -> Result<()> {
    let mut chunk = [0u8; CHUNK];
    while remaining > 0 {
        control.check()?;
        let size = usize::try_from(remaining.min(CHUNK as u64)).map_err(|_| Error::Protocol)?;
        let read = input.read(&mut chunk[..size])?;
        if read == 0 {
            return Err(std::io::Error::from(std::io::ErrorKind::UnexpectedEof).into());
        }
        output.write_all(&chunk[..read])?;
        remaining -= read as u64;
    }
    Ok(())
}

/// Outcome of the hello exchange.
struct HelloResult {
    /// Shared frontier when the session ends here (nothing to exchange).
    skip: Option<BTreeMap<String, u64>>,
    peer: PeerHello,
}

/// Each side already holds every operation the other has when both advertised
/// the same frontier, so no bundle is exchanged (subject to the M1 rule).
fn hello(
    stream: &mut (impl Read + Write),
    local: &Hello,
    policy: &Policy,
    initiator: bool,
) -> Result<HelloResult> {
    if initiator {
        send_frame(stream, b'H', &serde_json::to_vec(local)?, MAX_CONTROL)?;
    }
    let remote = parse_remote_hello(&receive_frame(stream, b'H', MAX_CONTROL)?)?;
    if remote.version != 1 || remote.group != policy.group || remote.replica != policy.peer_replica
    {
        return Err(Error::Peer);
    }
    // The server answers in kind: frontier only to a client that sent one,
    // features only to a client that announced them.
    let advertised = Hello {
        version: local.version,
        group: local.group.clone(),
        replica: local.replica.clone(),
        frontier: if initiator || remote.frontier.is_some() {
            local.frontier.clone()
        } else {
            None
        },
        features: if initiator || remote.features.is_some() {
            local.features.clone()
        } else {
            None
        },
        enabled: if initiator || remote.features.is_some() {
            local.enabled
        } else {
            None
        },
    };
    if !initiator {
        send_frame(stream, b'H', &serde_json::to_vec(&advertised)?, MAX_CONTROL)?;
    }
    let known = |map: &BTreeMap<String, u64>| {
        map.iter()
            .filter(|(_, sequence)| **sequence > 0)
            .map(|(replica, sequence)| (replica.clone(), *sequence))
            .collect::<BTreeMap<_, _>>()
    };
    let peer = PeerHello {
        features: remote.features,
        enabled: remote.enabled,
    };
    let local_links = advertised
        .features
        .as_ref()
        .is_some_and(|f| f.iter().any(|x| x == "links"));
    let may_skip = equal_frontier_may_skip(local_links, advertised.enabled == Some(true), &peer);
    let skip = match (advertised.frontier, remote.frontier) {
        (Some(ours), Some(theirs)) if may_skip && known(&ours) == known(&theirs) => Some(ours),
        _ => None,
    };
    Ok(HelloResult { skip, peer })
}

/// Ends a session whose hello found nothing to exchange.
fn skipped(
    library: &mut Library,
    policy: &Policy,
    frontier: &BTreeMap<String, u64>,
) -> Result<Value> {
    library.sync_acknowledge_equal(&policy.peer_replica, frontier)?;
    Ok(json!({"peer":policy.peer_replica,"skipped":true}))
}

fn send_bundle(
    stream: &mut impl Write,
    library: &Library,
    root: &Path,
    policy: &Policy,
    control: &SessionControl,
) -> Result<()> {
    control.check()?;
    ensure_policy_current(root, policy)?;
    let temp = tempfile::Builder::new()
        .prefix("lan-send-")
        .tempdir_in(root)?;
    let path = temp.path().join("exchange.stackexchange");
    library.sync_export_control(&policy.peer_replica, &path, &control.archive)?;
    let size = fs::metadata(&path)?.len();
    if size > MAX_BUNDLE {
        return Err(Error::Policy("Exchange exceeds STACKEX1 LAN limit"));
    }
    let mut file = File::open(&path)?;
    ensure_policy_current(root, policy)?;
    send_header(stream, b'B', size)?;
    let mut remaining = size;
    let mut chunk = [0u8; CHUNK];
    while remaining > 0 {
        control.check()?;
        ensure_policy_current(root, policy)?;
        let count = usize::try_from(remaining.min(CHUNK as u64)).map_err(|_| Error::Protocol)?;
        let read = file.read(&mut chunk[..count])?;
        if read == 0 {
            return Err(std::io::Error::from(std::io::ErrorKind::UnexpectedEof).into());
        }
        stream.write_all(&chunk[..read])?;
        remaining -= read as u64;
    }
    stream.flush()?;
    Ok(())
}

fn receive_bundle(
    stream: &mut (impl Read + Write),
    library: &mut Library,
    root: &Path,
    policy: &Policy,
    control: &SessionControl,
) -> Result<Value> {
    control.check()?;
    let size = receive_header(stream, b'B', MAX_BUNDLE)?;
    // The library writer is held, so any existing staging directory was left by
    // an interrupted session; remove it rather than accumulating large bundles.
    for entry in std::fs::read_dir(root)?.flatten() {
        if entry
            .file_name()
            .to_string_lossy()
            .starts_with("lan-receive-")
            && entry.file_type().is_ok_and(|kind| kind.is_dir())
        {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
    let temp = tempfile::Builder::new()
        .prefix("lan-receive-")
        .tempdir_in(root)?;
    let path = temp.path().join("exchange.stackexchange");
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)?;
    copy_exact(stream, &mut file, size, control)?;
    file.sync_all()?;
    ensure_policy_current(root, policy)?;
    check_group(library, policy)?;
    control.check()?;
    let result = library.sync_import_control(&path, None, &control.archive)?;
    send_frame(stream, b'R', &serde_json::to_vec(&result)?, MAX_CONTROL)?;
    Ok(result)
}

fn receive_receipt(stream: &mut impl Read) -> Result<Value> {
    let result: Value = serde_json::from_slice(&receive_frame(stream, b'R', MAX_CONTROL)?)?;
    if !result["durable_frontier"].is_object() {
        return Err(Error::Protocol);
    }
    Ok(result)
}

/// A single foreground connection. The caller chooses a manual address and
/// starts this action explicitly. No background listener or discovery runs.
pub fn sync_now(root: &Path, address: SocketAddr) -> Result<Value> {
    sync_now_library(
        &mut Library::open(root)?,
        address,
        &SessionControl::default(),
    )
}

/// Run on the application's repository worker, retaining its sole writer lock.
pub fn sync_now_library(
    library: &mut Library,
    address: SocketAddr,
    control: &SessionControl,
) -> Result<Value> {
    sync_now_library_with(library, address, control, &mut enable_links_on_hello)
}

/// As `sync_now_library`, calling `on_hello` once the hello is accepted.
pub fn sync_now_library_with(
    library: &mut Library,
    address: SocketAddr,
    control: &SessionControl,
    on_hello: HelloHook<'_>,
) -> Result<Value> {
    control.check()?;
    let local = check_group(library, &credentials(library.root())?.0)?;
    let levels = ladder(&local);
    let peer = credentials(library.root())?.0.peer_replica;
    run_ladder(&peer, &levels, Instant::now(), |level| {
        sync_attempt(
            library,
            address,
            control,
            &level_hello(&local, level),
            &mut *on_hello,
        )
    })
}

fn sync_attempt(
    library: &mut Library,
    address: SocketAddr,
    control: &SessionControl,
    local: &Hello,
    on_hello: HelloHook<'_>,
) -> std::result::Result<Value, Failed> {
    control.check()?;
    let root = library.root().to_owned();
    let root = root.as_path();
    let (policy, cert, key, roots) = credentials(root)?;
    let config =
        ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_protocol_versions(TLS_VERSIONS)
            .map_err(Error::Tls)?
            .with_root_certificates(roots)
            .with_client_auth_cert(cert, key)?;
    let tcp = TcpStream::connect_timeout(&address, HANDSHAKE_TIMEOUT)?;
    let _socket = control.attach(&tcp)?;
    configure_tcp(&tcp)?;
    let name = ServerName::try_from(TLS_NAME)
        .map_err(|_| Error::Protocol)?
        .to_owned();
    let mut tls = StreamOwned::new(ClientConnection::new(Arc::new(config), name)?, tcp);
    tls.conn.complete_io(&mut tls.sock)?;
    auth_peer(tls.conn.peer_certificates(), &policy)?;
    ensure_policy_current(root, &policy)?;
    configure_data_tcp(&tls.sock)?;
    let answered = hello(&mut tls, local, &policy, true).map_err(|error| Failed {
        refusal: is_refusal(&error),
        error,
    })?;
    if let Some(frontier) = answered.skip {
        return Ok(skipped(library, &policy, &frontier)?);
    }
    on_hello(library, &answered.peer)?;
    tls.sock.set_read_timeout(Some(PROCESSING_TIMEOUT))?;
    send_bundle(&mut tls, library, root, &policy, control)?;
    let first_ack = receive_receipt(&mut tls)?;
    let received = receive_bundle(&mut tls, library, root, &policy, control)?;
    send_bundle(&mut tls, library, root, &policy, control)?;
    let final_ack = receive_receipt(&mut tls)?;
    ensure_policy_current(root, &policy)?;
    Ok(
        json!({"peer":policy.peer_replica,"first_ack":first_ack,"received":received,"final_ack":final_ack,"status":library.sync_status()?}),
    )
}

/// One bounded foreground listener; returns after one authenticated session.
/// Binding is explicit. Unknown TLS clients cannot read any library bytes.
pub fn listen_once(root: &Path, address: SocketAddr) -> Result<Value> {
    listen_once_library(
        &mut Library::open(root)?,
        address,
        &SessionControl::default(),
    )
}

/// Cancellation is immediate while waiting for a peer or blocked on TLS I/O.
pub fn listen_once_library(
    library: &mut Library,
    address: SocketAddr,
    control: &SessionControl,
) -> Result<Value> {
    control.check()?;
    let root = library.root().to_owned();
    let (policy, _, _, _) = credentials(&root)?;
    check_group(library, &policy)?;
    let listener = TcpListener::bind(address)?;
    listener.set_nonblocking(true)?;
    let until = Instant::now() + TIMEOUT;
    let tcp = loop {
        control.check()?;
        ensure_policy_current(&root, &policy)?;
        match listener.accept() {
            Ok((stream, _)) => break stream,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < until => {
                std::thread::sleep(Duration::from_millis(25))
            }
            Err(e) => return Err(e.into()),
        }
    };
    serve_accepted_library(library, tcp, control)
}

/// A persistent application listener accepts outside the library writer; idle
/// waiting therefore never blocks edits, imports or other repository work.
pub fn serve_accepted_library(
    library: &mut Library,
    tcp: TcpStream,
    control: &SessionControl,
) -> Result<Value> {
    serve_accepted_library_with(library, tcp, control, &mut enable_links_on_hello)
}

/// As `serve_accepted_library`; `on_hello` runs after the hello reply is sent
/// and before `receive_bundle` (the D5 enable-op slot).
pub fn serve_accepted_library_with(
    library: &mut Library,
    tcp: TcpStream,
    control: &SessionControl,
    on_hello: HelloHook<'_>,
) -> Result<Value> {
    control.check()?;
    let root = library.root().to_owned();
    let root = root.as_path();
    let (policy, cert, key, roots) = credentials(root)?;
    let local = check_group(library, &policy)?;
    let verifier = rustls::server::WebPkiClientVerifier::builder_with_provider(
        Arc::new(roots),
        Arc::new(rustls::crypto::ring::default_provider()),
    )
    .build()
    .map_err(|_| Error::Policy("Invalid enrolled client certificate"))?;
    let config =
        ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_protocol_versions(TLS_VERSIONS)
            .map_err(Error::Tls)?
            .with_client_cert_verifier(verifier)
            .with_single_cert(cert, key)?;
    let _socket = control.attach(&tcp)?;
    configure_tcp(&tcp)?;
    let mut tls = StreamOwned::new(ServerConnection::new(Arc::new(config))?, tcp);
    tls.conn.complete_io(&mut tls.sock)?;
    auth_peer(tls.conn.peer_certificates(), &policy)?;
    ensure_policy_current(root, &policy)?;
    configure_data_tcp(&tls.sock)?;
    let answered = hello(&mut tls, &local, &policy, false)?;
    if let Some(frontier) = answered.skip {
        return skipped(library, &policy, &frontier);
    }
    on_hello(library, &answered.peer)?;
    tls.sock.set_read_timeout(Some(PROCESSING_TIMEOUT))?;
    let first = receive_bundle(&mut tls, library, root, &policy, control)?;
    send_bundle(&mut tls, library, root, &policy, control)?;
    let peer_ack = receive_receipt(&mut tls)?;
    let final_receipt = receive_bundle(&mut tls, library, root, &policy, control)?;
    ensure_policy_current(root, &policy)?;
    Ok(
        json!({"peer":policy.peer_replica,"received":first,"peer_ack":peer_ack,"final_receipt":final_receipt,"status":library.sync_status()?}),
    )
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
        assert!(matches!(
            receive_frame(&mut oversized.as_slice(), b'B', MAX_BUNDLE),
            Err(Error::Policy(_))
        ));
        let mut wrong = Vec::new();
        send_frame(&mut wrong, b'R', b"{}", MAX_CONTROL).unwrap();
        assert!(matches!(
            receive_frame(&mut wrong.as_slice(), b'B', MAX_CONTROL),
            Err(Error::Protocol)
        ));
        wrong.pop();
        assert!(receive_frame(&mut wrong.as_slice(), b'R', MAX_CONTROL).is_err());
        let mut truncated = Vec::new();
        send_header(&mut truncated, b'B', 12).unwrap();
        truncated.extend_from_slice(b"partial");
        let mut input = truncated.as_slice();
        let length = receive_header(&mut input, b'B', MAX_BUNDLE).unwrap();
        assert!(
            copy_exact(
                &mut input,
                &mut Vec::new(),
                length,
                &SessionControl::default()
            )
            .is_err()
        );
    }

    #[test]
    fn cancel_interrupts_a_blocked_authenticated_processing_wait() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let mut client = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        let (_peer, _) = listener.accept().unwrap();
        client.set_read_timeout(Some(PROCESSING_TIMEOUT)).unwrap();
        let control = SessionControl::default();
        let _guard = control.attach(&client).unwrap();
        let read = std::thread::spawn(move || client.read(&mut [0u8; 1]));
        std::thread::sleep(Duration::from_millis(25));
        let started = Instant::now();
        control.cancel();
        let result = read.join().unwrap();
        assert!(result.is_err() || result.unwrap() == 0);
        assert!(started.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn bundle_copy_uses_bounded_chunks_without_buffering_entire_payload() {
        struct CountingReader<'a> {
            remaining: &'a [u8],
            maximum_requested: usize,
        }
        impl Read for CountingReader<'_> {
            fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
                self.maximum_requested = self.maximum_requested.max(buf.len());
                self.remaining.read(buf)
            }
        }
        let expected = vec![42u8; CHUNK * 3 + 17];
        let mut input = CountingReader {
            remaining: &expected,
            maximum_requested: 0,
        };
        let mut actual = Vec::new();
        copy_exact(
            &mut input,
            &mut actual,
            expected.len() as u64,
            &SessionControl::default(),
        )
        .unwrap();
        assert_eq!(actual, expected);
        assert_eq!(input.maximum_requested, CHUNK);
    }

    #[test]
    fn identity_is_private_p256_and_created_once() {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempfile::Builder::new()
            .prefix("stack-identity-")
            .tempdir_in("/tmp")
            .unwrap();
        let root = temp.path().join("lib");
        drop(Library::open(&root).unwrap());
        let pin = identity_init(&root).unwrap();
        let dir = private_dir(&root).unwrap();
        for name in ["identity.der", "identity-key.der"] {
            let mode = fs::metadata(dir.join(name)).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600, "{name}");
        }
        let cert = identity_certificate(&root).unwrap();
        assert_eq!(pin, fingerprint(&cert));
        // The certificate is a usable trust anchor and the key a PKCS#8 P-256 key rustls accepts.
        let mut roots = RootCertStore::empty();
        roots.add(CertificateDer::from(cert.clone())).unwrap();
        let key = read_private(&dir.join("identity-key.der")).unwrap();
        rustls::crypto::ring::sign::any_ecdsa_type(&PrivateKeyDer::Pkcs8(
            PrivatePkcs8KeyDer::from(key),
        ))
        .unwrap();
        // The SAN carries the TLS name the peers check.
        assert!(
            cert.windows(TLS_NAME.len())
                .any(|w| w == TLS_NAME.as_bytes())
        );
        assert!(
            identity_init(&root).is_err(),
            "a second identity is refused"
        );
        assert_eq!(
            identity_certificate(&root).unwrap(),
            cert,
            "the first identity is kept"
        );
        assert!(
            fs::read_dir(&dir).unwrap().all(|e| !e
                .unwrap()
                .file_name()
                .to_string_lossy()
                .ends_with(".tmp"))
        );
    }

    #[test]
    fn authenticated_corrupt_bundle_rejected_then_clean_retry_applies() {
        let temp = tempfile::Builder::new()
            .prefix("stack-lan-corrupt-")
            .tempdir_in("/tmp")
            .unwrap();
        let a = temp.path().join("a");
        let b = temp.path().join("b");
        let al = Library::open(&a).unwrap();
        let a_status = al.sync_status().unwrap();
        let group = al.status().unwrap().library_id;
        let a_replica = a_status["replica"].as_str().unwrap().to_owned();
        drop(al);
        let bl = Library::open(&b).unwrap();
        let b_replica = bl.sync_status().unwrap()["replica"]
            .as_str()
            .unwrap()
            .to_owned();
        drop(bl);
        identity_init(&a).unwrap();
        identity_init(&b).unwrap();
        let a_cert = identity_certificate(&a).unwrap();
        let b_cert = identity_certificate(&b).unwrap();
        peer_enroll(&a, &group, &b_replica, &b_cert, &fingerprint(&b_cert)).unwrap();
        peer_enroll(&b, &group, &a_replica, &a_cert, &fingerprint(&a_cert)).unwrap();
        Library::open(&a)
            .unwrap()
            .sync_start(std::slice::from_ref(&b_replica))
            .unwrap();
        let baseline = temp.path().join("reviewed-baseline.stackexchange");
        Library::open(&a)
            .unwrap()
            .sync_export(&b_replica, &baseline)
            .unwrap();
        Library::open(&b)
            .unwrap()
            .sync_import(
                &baseline,
                Some((&group, &[a_replica.clone(), b_replica.clone()])),
            )
            .unwrap();
        let before = Library::open(&b).unwrap().sync_status().unwrap();
        let (policy, cert, key, roots) = credentials(&a).unwrap();
        let mut local = check_group(&Library::open(&a).unwrap(), &policy).unwrap();
        // D4b is live: the local hello announces links with the real (here still disabled) state.
        assert_eq!(local.features.as_deref(), Some(&["links".to_owned()][..]));
        assert_eq!(local.enabled, Some(false));
        assert!(
            String::from_utf8(serde_json::to_vec(&local).unwrap())
                .unwrap()
                .contains("\"features\":[\"links\"],\"enabled\":false")
        );
        local.frontier = None;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        drop(listener);
        let server_root = b.clone();
        let server = std::thread::spawn(move || listen_once(&server_root, address));
        std::thread::sleep(Duration::from_millis(80));
        let config =
            ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(TLS_VERSIONS)
                .unwrap()
                .with_root_certificates(roots)
                .with_client_auth_cert(cert, key)
                .unwrap();
        let tcp = TcpStream::connect(address).unwrap();
        configure_tcp(&tcp).unwrap();
        let name = ServerName::try_from(TLS_NAME).unwrap().to_owned();
        let mut tls = StreamOwned::new(ClientConnection::new(Arc::new(config), name).unwrap(), tcp);
        tls.conn.complete_io(&mut tls.sock).unwrap();
        assert_eq!(
            tls.conn.protocol_version(),
            Some(rustls::ProtocolVersion::TLSv1_3)
        );
        auth_peer(tls.conn.peer_certificates(), &policy).unwrap();
        hello(&mut tls, &local, &policy, true).unwrap();
        send_frame(&mut tls, b'B', b"corrupt exchange", MAX_BUNDLE).unwrap();
        drop(tls);
        assert!(server.join().unwrap().is_err());
        // The hello announced links, so b wrote its own enable operation after the hello reply and before the
        // bundle (design section 5, M-a: it cannot wait for the bundle). That is the ONLY change: the corrupt
        // bundle applied nothing, and a's operations, receipts and gaps are as before.
        let mut after = Library::open(&b).unwrap().sync_status().unwrap();
        assert_eq!(after["links_enabled"], true);
        assert_eq!(
            after["operations"],
            before["operations"].as_u64().unwrap() + 1
        );
        for key in ["links_enabled", "operations", "frontier"] {
            after[key] = before[key].clone();
        }
        assert_eq!(after, before);
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        drop(listener);
        let server_root = b.clone();
        let server = std::thread::spawn(move || listen_once(&server_root, address));
        std::thread::sleep(Duration::from_millis(80));
        assert!(sync_now(&a, address).is_ok());
        assert!(server.join().unwrap().is_ok());

        // A revocation after a successful TLS handshake must stop a valid
        // bundle before the repository can apply it.
        let status_before_revoke = Library::open(&b).unwrap().sync_status().unwrap();
        let (policy, cert, key, roots) = credentials(&a).unwrap();
        let mut local = check_group(&Library::open(&a).unwrap(), &policy).unwrap();
        local.frontier = None;
        let config =
            ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
                .with_protocol_versions(TLS_VERSIONS)
                .unwrap()
                .with_root_certificates(roots)
                .with_client_auth_cert(cert, key)
                .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        drop(listener);
        let server_root = b.clone();
        let server = std::thread::spawn(move || listen_once(&server_root, address));
        std::thread::sleep(Duration::from_millis(80));
        let tcp = TcpStream::connect(address).unwrap();
        configure_tcp(&tcp).unwrap();
        let name = ServerName::try_from(TLS_NAME).unwrap().to_owned();
        let mut tls = StreamOwned::new(ClientConnection::new(Arc::new(config), name).unwrap(), tcp);
        tls.conn.complete_io(&mut tls.sock).unwrap();
        hello(&mut tls, &local, &policy, true).unwrap();
        peer_revoke(&b).unwrap();
        let valid = temp.path().join("valid-after-revoke.stackexchange");
        Library::open(&a)
            .unwrap()
            .sync_export(&b_replica, &valid)
            .unwrap();
        let _ = send_frame(&mut tls, b'B', &fs::read(valid).unwrap(), MAX_BUNDLE);
        drop(tls);
        assert!(server.join().unwrap().is_err());
        assert_eq!(
            Library::open(&b).unwrap().sync_status().unwrap(),
            status_before_revoke
        );
    }

    // ---- D4a: hello parsing and the ladder ----

    struct Pipe {
        input: std::io::Cursor<Vec<u8>>,
        output: Vec<u8>,
    }
    impl Read for Pipe {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            self.input.read(buf)
        }
    }
    impl Write for Pipe {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.output.write(buf)
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    fn test_policy() -> Policy {
        Policy {
            version: 1,
            group: "g".into(),
            peer_replica: "peer".into(),
            fingerprint: String::new(),
            revoked: false,
        }
    }
    fn local_hello(features: bool, enabled: bool) -> Hello {
        Hello {
            version: 1,
            group: "g".into(),
            replica: "me".into(),
            frontier: Some(BTreeMap::from([("me".to_owned(), 3)])),
            features: features.then(|| vec!["links".to_owned()]),
            enabled: features.then_some(enabled),
        }
    }
    fn remote_frame(json: &str) -> Pipe {
        let mut input = Vec::new();
        send_frame(&mut input, b'H', json.as_bytes(), MAX_CONTROL).unwrap();
        Pipe {
            input: std::io::Cursor::new(input),
            output: Vec::new(),
        }
    }
    fn sent_body(pipe: &Pipe) -> Value {
        let body = receive_frame(&mut pipe.output.as_slice(), b'H', MAX_CONTROL).unwrap();
        serde_json::from_slice(&body).unwrap()
    }
    const PEER_BASE: &str = r#""version":1,"group":"g","replica":"peer","frontier":{"me":3}"#;

    #[test]
    fn hello_round_trips_with_and_without_features() {
        let plain = local_hello(false, false);
        let bytes = serde_json::to_vec(&plain).unwrap();
        assert_eq!(
            String::from_utf8(bytes.clone()).unwrap(),
            r#"{"version":1,"group":"g","replica":"me","frontier":{"me":3}}"#
        );
        assert_eq!(parse_peer_hello(&bytes).unwrap(), PeerHello::default());
        let with = serde_json::to_vec(&local_hello(true, true)).unwrap();
        assert_eq!(
            String::from_utf8(with.clone()).unwrap(),
            r#"{"version":1,"group":"g","replica":"me","frontier":{"me":3},"features":["links"],"enabled":true}"#
        );
        let parsed = parse_peer_hello(&with).unwrap();
        assert!(parsed.announces_links());
        assert_eq!(parsed.links_enabled(), Some(true));
        assert_eq!(parsed.features(), ["links"]);
        assert!(
            parse_peer_hello(br#"{"version":1,"group":"g","replica":"p","features":[]}"#).is_ok()
        );
    }

    #[test]
    fn hello_features_follow_the_strict_phone_rules() {
        let parse = |tail: &str| {
            parse_peer_hello(
                format!(r#"{{"version":1,"group":"g","replica":"p"{tail}}}"#).as_bytes(),
            )
        };
        assert!(parse("").is_ok());
        assert!(parse(r#","features":[]"#).is_ok());
        assert!(parse(r#","features":["links","future"],"enabled":false"#).is_ok());
        assert!(parse(r#","features":["unknown"]"#).is_ok());
        let many: Vec<String> = (0..16).map(|n| format!("f{n}")).collect();
        assert!(parse(&format!(r#","features":{}"#, json!(many))).is_ok());
        let too_many: Vec<String> = (0..17).map(|n| format!("f{n}")).collect();
        assert!(parse(&format!(r#","features":{}"#, json!(too_many))).is_err());
        assert!(parse(r#","features":["links","links"]"#).is_err());
        assert!(parse(r#","features":[""]"#).is_err());
        assert!(parse(&format!(r#","features":["{}"]"#, "x".repeat(64))).is_ok());
        assert!(parse(&format!(r#","features":["{}"]"#, "x".repeat(65))).is_err());
        // 33 astral characters are 66 UTF-16 units.
        assert!(parse(&format!(r#","features":["{}"]"#, "\u{1F600}".repeat(33))).is_err());
        assert!(parse(r#","features":null"#).is_err());
        assert!(parse(r#","features":["links"],"enabled":null"#).is_err());
        assert!(parse(r#","features":["links"],"features":["links"]"#).is_err());
        // The same rules on the live receive path.
        let body = format!("{{{PEER_BASE},\"features\":[\"links\",\"links\"]}}");
        let mut pipe = remote_frame(&body);
        assert!(hello(&mut pipe, &local_hello(false, false), &test_policy(), true).is_err());
    }

    #[test]
    fn unknown_hello_field_is_still_refused() {
        let body = format!("{{{PEER_BASE},\"surprise\":1}}");
        assert!(parse_peer_hello(body.as_bytes()).is_err());
        let mut pipe = remote_frame(&body);
        assert!(matches!(
            hello(&mut pipe, &local_hello(false, false), &test_policy(), true),
            Err(Error::Json(_))
        ));
        assert!(
            parse_peer_hello(br#"{"version":1,"group":"g","replica":"p","features":"links"}"#)
                .is_err()
        );
    }

    #[test]
    fn old_peer_plain_hello_interops_and_nothing_is_announced() {
        let local = local_hello(false, false);
        let mut pipe = remote_frame(&format!("{{{PEER_BASE}}}"));
        let out = hello(&mut pipe, &local, &test_policy(), true).unwrap();
        assert_eq!(out.peer, PeerHello::default());
        assert!(out.skip.is_some(), "equal frontiers still skip");
        let sent = sent_body(&pipe);
        assert!(sent.get("features").is_none() && sent.get("enabled").is_none());
        // Server side: a client that sent no features gets none back.
        let mut pipe = remote_frame(&format!("{{{PEER_BASE}}}"));
        hello(&mut pipe, &local_hello(true, true), &test_policy(), false).unwrap();
        let sent = sent_body(&pipe);
        assert!(sent.get("features").is_none() && sent.get("enabled").is_none());
        const { assert!(ANNOUNCE_LINKS) }; // D4b is live
    }

    #[test]
    fn announced_peer_hello_is_parsed_and_skip_rule_follows_m1() {
        let on = |e: bool| PeerHello {
            features: Some(vec!["links".into()]),
            enabled: Some(e),
        };
        let none = PeerHello::default();
        assert!(equal_frontier_may_skip(false, false, &none));
        assert!(equal_frontier_may_skip(true, false, &none));
        assert!(equal_frontier_may_skip(false, true, &on(false)));
        assert!(equal_frontier_may_skip(true, true, &on(true)));
        assert!(!equal_frontier_may_skip(true, true, &on(false)));
        assert!(!equal_frontier_may_skip(true, false, &on(true)));
        // Through hello(): both announce, peer not enabled -> no skip.
        let peer_json = format!("{{{PEER_BASE},\"features\":[\"links\"],\"enabled\":false}}");
        let mut pipe = remote_frame(&peer_json);
        let out = hello(&mut pipe, &local_hello(true, true), &test_policy(), true).unwrap();
        assert!(out.peer.announces_links() && out.skip.is_none());
        let peer_json = format!("{{{PEER_BASE},\"features\":[\"links\"],\"enabled\":true}}");
        let mut pipe = remote_frame(&peer_json);
        let out = hello(&mut pipe, &local_hello(true, true), &test_policy(), true).unwrap();
        assert!(out.skip.is_some());
        // Server replies with features only to a client that announced them.
        let mut pipe = remote_frame(&peer_json);
        hello(&mut pipe, &local_hello(true, true), &test_policy(), false).unwrap();
        assert_eq!(sent_body(&pipe)["features"], json!(["links"]));
    }

    #[test]
    fn ladder_levels_strip_fields_in_order() {
        let local = local_hello(true, false);
        assert_eq!(
            ladder(&local),
            [Level::FrontierFeatures, Level::Frontier, Level::Plain]
        );
        assert_eq!(
            ladder(&local_hello(false, false)),
            [Level::Frontier, Level::Plain]
        );
        let f = level_hello(&local, Level::Frontier);
        assert!(f.features.is_none() && f.enabled.is_none() && f.frontier.is_some());
        let p = level_hello(&local, Level::Plain);
        assert!(p.frontier.is_none() && p.features.is_none());
        assert_eq!(
            level_hello(&local, Level::FrontierFeatures).features,
            local.features
        );
    }

    fn refusal() -> Failed {
        Failed {
            error: Error::Protocol,
            refusal: true,
        }
    }

    #[test]
    fn ladder_loops_to_an_old_peer_within_one_attempt_and_remembers() {
        let levels = ladder(&local_hello(true, false));
        let mut tried = Vec::new();
        let t0 = Instant::now();
        // Fake old peer: refuses anything but plain.
        let got = run_ladder("old-peer", &levels, t0, |l| {
            tried.push(l);
            if l == Level::Plain {
                Ok(7)
            } else {
                Err(refusal())
            }
        })
        .unwrap();
        assert_eq!(got, 7);
        assert_eq!(
            tried,
            [Level::FrontierFeatures, Level::Frontier, Level::Plain]
        );
        // Next attempt within 15 minutes starts at the remembered level.
        let mut tried = Vec::new();
        run_ladder("old-peer", &levels, t0 + Duration::from_secs(60), |l| {
            tried.push(l);
            Ok(0)
        })
        .unwrap();
        assert_eq!(tried, [Level::Plain]);
        // After 15 minutes the memory expires.
        let mut tried = Vec::new();
        run_ladder("old-peer", &levels, t0 + REFUSAL_MEMORY, |l| {
            tried.push(l);
            Ok(0)
        })
        .unwrap();
        assert_eq!(tried, [Level::FrontierFeatures]);
        // Non-refusal errors (and the last level's error) end the attempt.
        let mut n = 0;
        let r = run_ladder(
            "other-peer",
            &levels,
            t0,
            |_| -> std::result::Result<(), Failed> {
                n += 1;
                Err(Error::Peer.into())
            },
        );
        assert!(matches!(r, Err(Error::Peer)) && n == 1);
        let mut n = 0;
        let r = run_ladder(
            "dead-peer",
            &levels,
            t0,
            |_| -> std::result::Result<(), Failed> {
                n += 1;
                Err(refusal())
            },
        );
        assert!(matches!(r, Err(Error::Protocol)) && n == 3);
    }

    #[test]
    fn only_hello_phase_errors_and_timeouts_are_refusals() {
        assert!(is_refusal(&Error::Io(std::io::ErrorKind::TimedOut.into())));
        assert!(is_refusal(&Error::Io(
            std::io::ErrorKind::UnexpectedEof.into()
        )));
        assert!(is_refusal(&Error::Protocol));
        assert!(!is_refusal(&Error::Peer));
        assert!(!is_refusal(&Error::Canceled));
        assert!(!is_refusal(&Error::Policy("x")));
        // Errors outside the hello phase convert as non-refusals.
        assert!(!Failed::from(std::io::Error::from(std::io::ErrorKind::TimedOut)).refusal);
    }
}
