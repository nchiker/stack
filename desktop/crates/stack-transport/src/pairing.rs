//! Foreground setup: untrusted discovery, committed short-code comparison,
//! authenticated mutual consent, then the ordinary pinned TLS exchange format.
use super::*;
use std::net::UdpSocket;

const SETUP_TIME: Duration = Duration::from_secs(120);
const DISCOVERY_LIMIT: usize = 1024;

#[derive(Clone, Default)]
pub struct PairingControl {
    pub session: SessionControl,
    state: Arc<Mutex<Progress>>,
}
#[derive(Default)]
struct Progress {
    view: Value,
    accepted: Option<String>,
    baseline: Option<tempfile::NamedTempFile>,
    initiator_approved: bool,
}
impl PairingControl {
    pub fn approve_initiator(&self) {
        self.state
            .lock()
            .expect("pairing progress")
            .initiator_approved = true;
    }
    pub fn snapshot(&self) -> Value {
        self.state.lock().expect("pairing progress").view.clone()
    }
    pub fn confirm(&self, token: &str) -> bool {
        if self.session.check().is_err() {
            return false;
        }
        let mut state = self.state.lock().expect("pairing progress");
        if state.view["phase"] != "compare" || state.view["token"] != token {
            return false;
        }
        state.accepted = Some(token.to_owned());
        state.view["accepted"] = json!(true);
        true
    }
    pub fn cancel(&self) {
        self.session.cancel();
    }
    pub fn received_path(&self) -> Option<PathBuf> {
        self.state
            .lock()
            .expect("pairing progress")
            .baseline
            .as_ref()
            .map(|f| f.path().to_owned())
    }
    fn publish(&self, view: Value) {
        let mut state = self.state.lock().expect("pairing progress");
        state.accepted = None;
        state.view = view;
    }
    fn wait_confirmation(&self, token: &str, until: Instant) -> Result<()> {
        loop {
            self.session.check()?;
            if Instant::now() >= until {
                return Err(Error::Policy("Setup timed out; start a fresh invitation"));
            }
            if self
                .state
                .lock()
                .expect("pairing progress")
                .accepted
                .as_deref()
                == Some(token)
            {
                return Ok(());
            }
            std::thread::sleep(Duration::from_millis(25));
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Device {
    name: String,
    replica: String,
    certificate: String,
    group: String,
    peer: String,
    nonce: String,
}

pub fn valid_name(name: &str) -> bool {
    !name.trim().is_empty()
        && name == name.trim()
        && name.chars().count() <= 64
        && !name.chars().any(char::is_control)
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn unhex(text: &str, maximum: usize) -> Result<Vec<u8>> {
    if text.is_empty()
        || !text.len().is_multiple_of(2)
        || text.len() > maximum * 2
        || !text.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return Err(Error::Protocol);
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16).map_err(|_| Error::Protocol))
        .collect()
}
fn random_hex(size: usize) -> Result<String> {
    let mut bytes = vec![0; size];
    File::open("/dev/urandom")?.read_exact(&mut bytes)?;
    Ok(hex(&bytes))
}
fn identifier(id: &str) -> bool {
    id.len() == 36
        && id.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_hexdigit()
            }
        })
}
fn device(library: &Library, name: &str) -> Result<Device> {
    if !valid_name(name) {
        return Err(Error::Policy(
            "Use a device name of 1–64 characters without control characters",
        ));
    }
    let status = library.sync_status()?;
    let replica = status["replica"]
        .as_str()
        .ok_or(Error::Protocol)?
        .to_owned();
    let (group, peer) = if status["adopted"] == true {
        let members = status["members"].as_array().ok_or(Error::Protocol)?;
        if members.len() != 2 {
            return Err(Error::Policy("Nearby setup requires a two-device group"));
        }
        (
            status["library"]
                .as_str()
                .ok_or(Error::Protocol)?
                .to_owned(),
            members
                .iter()
                .filter_map(Value::as_str)
                .find(|id| *id != replica)
                .ok_or(Error::Protocol)?
                .to_owned(),
        )
    } else {
        (String::new(), String::new())
    };
    Ok(Device {
        name: name.into(),
        replica,
        certificate: hex(&identity_certificate(library.root())?),
        group,
        peer,
        nonce: random_hex(32)?,
    })
}
fn field(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}
fn canonical(role: u8, invite: &str, value: &Device) -> Result<Vec<u8>> {
    if !valid_name(&value.name)
        || !identifier(&value.replica)
        || value.group.is_empty() != value.peer.is_empty()
        || (!value.group.is_empty() && (!identifier(&value.group) || !identifier(&value.peer)))
    {
        return Err(Error::Protocol);
    }
    let cert = unhex(&value.certificate, MAX_CONTROL as usize)?;
    let nonce = unhex(&value.nonce, 32)?;
    if nonce.len() != 32 {
        return Err(Error::Protocol);
    }
    let mut out = b"STACKPAIR1/device".to_vec();
    out.push(role);
    for bytes in [
        invite.as_bytes(),
        value.name.as_bytes(),
        value.replica.as_bytes(),
        &cert,
        value.group.as_bytes(),
        value.peer.as_bytes(),
        &nonce,
    ] {
        field(&mut out, bytes);
    }
    Ok(out)
}
fn commitment(role: u8, invite: &str, value: &Device) -> Result<String> {
    Ok(fingerprint(&canonical(role, invite, value)?))
}
fn transcript(invite: &str, computer: &Device, phone: &Device) -> Result<String> {
    let mut bytes = b"STACKPAIR1/transcript".to_vec();
    field(&mut bytes, &canonical(b'D', invite, computer)?);
    field(&mut bytes, &canonical(b'P', invite, phone)?);
    Ok(fingerprint(&bytes))
}
fn code(digest: &str) -> Result<String> {
    let bytes = unhex(digest, 32)?;
    if bytes.len() != 32 {
        return Err(Error::Protocol);
    }
    let n = u32::from_be_bytes(bytes[..4].try_into().map_err(|_| Error::Protocol)?) % 1_000_000;
    Ok(format!("{:03} {:03}", n / 1000, n % 1000))
}
fn check_devices(local: &Device, remote: &Device) -> Result<()> {
    if local.replica == remote.replica
        || unhex(&local.certificate, MAX_CONTROL as usize)?
            == unhex(&remote.certificate, MAX_CONTROL as usize)?
    {
        return Err(Error::Peer);
    }
    if (!local.group.is_empty() && local.peer != remote.replica)
        || (!remote.group.is_empty() && remote.peer != local.replica)
        || (!local.group.is_empty() && !remote.group.is_empty() && local.group != remote.group)
    {
        return Err(Error::Policy(
            "These libraries already belong to different device groups",
        ));
    }
    Ok(())
}
fn can_resume(root: &Path, remote: &Device) -> Result<()> {
    let path = pairing_dir(root).join("policy.json");
    if path.exists() {
        let policy: Policy = serde_json::from_slice(&read_private(&path)?)?;
        if policy.version != 1
            || policy.peer_replica != remote.replica
            || policy.fingerprint != fingerprint(&unhex(&remote.certificate, MAX_CONTROL as usize)?)
        {
            return Err(Error::Policy(
                "An existing or disconnected peer cannot be replaced by nearby setup",
            ));
        }
    }
    Ok(())
}
fn enroll_verified(library: &Library, group: &str, remote: &Device) -> Result<Policy> {
    can_resume(library.root(), remote)?;
    let cert = unhex(&remote.certificate, MAX_CONTROL as usize)?;
    let path = pairing_dir(library.root()).join("policy.json");
    if path.exists() {
        let mut policy: Policy = serde_json::from_slice(&read_private(&path)?)?;
        if policy.group != group {
            return Err(Error::Peer);
        }
        if policy.revoked {
            // Called only after a fresh authenticated phone approval.
            policy.revoked = false;
            write_private(&path, &serde_json::to_vec(&policy)?)?;
        }
        ensure_policy_current(library.root(), &policy)?;
        return Ok(policy);
    }
    peer_enroll_library(library, group, &remote.replica, &cert, &fingerprint(&cert))?;
    Ok(serde_json::from_slice(&read_private(&path)?)?)
}

/// Companion labels are local presentation metadata, never authentication inputs
/// for an existing policy. The immutable policy and certificate remain authoritative.
pub fn device_labels(root: &Path) -> Result<Value> {
    let path = pairing_dir(root).join("names.json");
    if !path.exists() {
        return Ok(Value::Null);
    }
    Ok(serde_json::from_slice(&read_private(&path)?)?)
}
pub fn record_sync(root: &Path) -> Result<()> {
    let mut labels = device_labels(root)?;
    labels["last_sync"] = json!(
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    );
    write_private(
        &pairing_dir(root).join("names.json"),
        &serde_json::to_vec(&labels)?,
    )
}

pub fn mark_complete(root: &Path) -> Result<()> {
    let mut labels = device_labels(root)?;
    if labels.is_object() {
        labels["setup_pending"] = json!(false);
        write_private(
            &pairing_dir(root).join("names.json"),
            &serde_json::to_vec(&labels)?,
        )?;
    }
    Ok(())
}

fn prepare_identity(root: &Path) -> Result<()> {
    let dir = private_dir(root)?;
    let cert = dir.join("identity.der");
    let key = dir.join("identity-key.der");
    match (cert.exists(), key.exists()) {
        (false, false) => {
            identity_init(root)?;
        }
        (true, true) => {
            if identity_certificate(root)?.is_empty() || read_private(&key)?.is_empty() {
                return Err(Error::Policy(
                    "Device identity is incomplete; existing credentials were kept",
                ));
            }
        }
        _ => {
            return Err(Error::Policy(
                "Device identity is incomplete; existing credentials were kept",
            ));
        }
    }
    Ok(())
}
/// A peer cannot extend the unauthenticated bootstrap by sending one byte per
/// socket timeout. One bounded watchdog closes the session at the absolute
/// invitation deadline; it is joined before returning or transferring data.
struct Deadline {
    stop: Arc<std::sync::atomic::AtomicBool>,
    worker: Option<std::thread::JoinHandle<()>>,
}
impl Deadline {
    fn new(control: SessionControl, until: Instant) -> Self {
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let waiting = stop.clone();
        let worker = std::thread::spawn(move || {
            while !waiting.load(std::sync::atomic::Ordering::Acquire) {
                if Instant::now() >= until {
                    control.cancel();
                    break;
                }
                std::thread::sleep(
                    until
                        .saturating_duration_since(Instant::now())
                        .min(Duration::from_millis(25)),
                );
            }
        });
        Self {
            stop,
            worker: Some(worker),
        }
    }
}
impl Drop for Deadline {
    fn drop(&mut self) {
        self.stop.store(true, std::sync::atomic::Ordering::Release);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

/// One invitation, one connection attempt, no automatic retry. Discovery only
/// exists during this call. No credentials or library bytes are broadcast.
pub fn invite_library(
    library: &mut Library,
    name: &str,
    address: SocketAddr,
    addresses: &[String],
    control: &PairingControl,
) -> Result<Value> {
    control.session.check()?;
    prepare_identity(library.root())?;
    let local = device(library, name)?;
    let invite = random_hex(16)?;
    let listener = TcpListener::bind(address)?;
    listener.set_nonblocking(true)?;
    let port = listener.local_addr()?.port();
    let udp = UdpSocket::bind(SocketAddr::new(address.ip(), port))?;
    udp.set_nonblocking(true)?;
    let qr = json!({"stack_pair":1,"invite":invite,"fingerprint":fingerprint(&unhex(&local.certificate,MAX_CONTROL as usize)?),"port":port,"addresses":addresses});
    control.publish(json!({"phase":"waiting","name":name,"qr":qr.to_string(),"port":port}));
    let until = Instant::now() + SETUP_TIME;
    let mut last_reply = Instant::now() - Duration::from_secs(1);
    let tcp = loop {
        control.session.check()?;
        if Instant::now() >= until {
            return Err(Error::Policy(
                "Invitation expired; choose Connect a phone again",
            ));
        }
        let mut buffer = [0; DISCOVERY_LIMIT];
        if let Ok((n, peer)) = udp.recv_from(&mut buffer)
            && last_reply.elapsed() >= Duration::from_millis(100)
            && let Ok(request) = serde_json::from_slice::<Value>(&buffer[..n])
            && request["stack_find"] == 1
            && request["nonce"]
                .as_str()
                .is_some_and(|s| s.len() == 32 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        {
            let reply = json!({"stack_pair":1,"invite":invite,"name":name,"port":port,"nonce":request["nonce"]});
            udp.send_to(&serde_json::to_vec(&reply)?, peer)?;
            last_reply = Instant::now();
        }
        match listener.accept() {
            Ok((tcp, _)) => break tcp,
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(Duration::from_millis(25))
            }
            Err(e) => return Err(e.into()),
        }
    };
    drop(udp);
    drop(listener);
    let _socket = control.session.attach(&tcp)?;
    configure_tcp(&tcp)?;
    run_connected(library, local, &invite, tcp, until, control)
}

fn run_connected(
    library: &mut Library,
    local: Device,
    invite: &str,
    mut tcp: TcpStream,
    until: Instant,
    control: &PairingControl,
) -> Result<Value> {
    let deadline = Deadline::new(control.session.clone(), until);
    // Both commitments are fixed before either reveal. Serialized JSON is never
    // hashed directly: the shared canonical representation is length-delimited.
    let request: Value = serde_json::from_slice(&receive_frame(&mut tcp, b'I', MAX_CONTROL)?)?;
    if request["version"] != 1 || request["invite"] != invite {
        return Err(Error::Peer);
    }
    let peer_commit = request["commitment"]
        .as_str()
        .ok_or(Error::Protocol)?
        .to_owned();
    if unhex(&peer_commit, 32)?.len() != 32 {
        return Err(Error::Protocol);
    }
    send_frame(
        &mut tcp,
        b'C',
        &serde_json::to_vec(&json!({"version":1,"commitment":commitment(b'D',invite,&local)?}))?,
        MAX_CONTROL,
    )?;
    let remote: Device = serde_json::from_slice(&receive_frame(&mut tcp, b'D', MAX_CONTROL)?)?;
    if commitment(b'P', invite, &remote)? != peer_commit {
        return Err(Error::Peer);
    }
    send_frame(&mut tcp, b'D', &serde_json::to_vec(&local)?, MAX_CONTROL)?;
    check_devices(&local, &remote)?;
    can_resume(library.root(), &remote)?;
    let digest = transcript(invite, &local, &remote)?;
    control.publish(json!({"phase":"compare","token":digest,"name":remote.name,"code":code(&digest)?,"accepted":false}));
    let initiator_approved = control
        .state
        .lock()
        .expect("pairing progress")
        .initiator_approved;
    if initiator_approved {
        control.confirm(&digest);
    }
    control.wait_confirmation(&digest, until)?;
    // This temporary trust exists only for this socket. Enrollment is written
    // after both endpoints send an authenticated acceptance of this transcript.
    let cert = CertificateDer::from(identity_certificate(library.root())?);
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(read_private(
        &private_dir(library.root())?.join("identity-key.der"),
    )?));
    let mut roots = RootCertStore::empty();
    roots.add(CertificateDer::from(unhex(
        &remote.certificate,
        MAX_CONTROL as usize,
    )?))?;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let verifier = rustls::server::WebPkiClientVerifier::builder_with_provider(
        Arc::new(roots),
        provider.clone(),
    )
    .build()
    .map_err(|_| Error::Peer)?;
    let config = ServerConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()?
        .with_client_cert_verifier(verifier)
        .with_single_cert(vec![cert], key)?;
    tcp.set_read_timeout(Some(
        until
            .saturating_duration_since(Instant::now())
            .max(Duration::from_millis(1)),
    ))?;
    let mut tls = StreamOwned::new(ServerConnection::new(Arc::new(config))?, tcp);
    tls.conn.complete_io(&mut tls.sock)?;
    if tls
        .conn
        .peer_certificates()
        .and_then(|c| c.first())
        .is_none_or(|c| {
            fingerprint(c.as_ref())
                != fingerprint(
                    &unhex(&remote.certificate, MAX_CONTROL as usize).unwrap_or_default(),
                )
        })
    {
        return Err(Error::Peer);
    }
    configure_data_tcp(&tls.sock)?;
    let acceptance: Value = serde_json::from_slice(&receive_frame(&mut tls, b'V', MAX_CONTROL)?)?;
    if acceptance != json!({"transcript":digest,"accepted":true}) {
        return Err(Error::Peer);
    }
    send_frame(
        &mut tls,
        b'V',
        &serde_json::to_vec(&json!({"transcript":digest,"accepted":true}))?,
        MAX_CONTROL,
    )?;
    control.session.check()?;
    drop(deadline);
    control.session.check()?;
    // Re-read group state while holding the same serialized writer.
    let group = if !local.group.is_empty() {
        local.group.clone()
    } else if !remote.group.is_empty() {
        remote.group.clone()
    } else {
        library.sync_start(std::slice::from_ref(&remote.replica))?["library"]
            .as_str()
            .ok_or(Error::Protocol)?
            .to_owned()
    };
    let policy = enroll_verified(library, &group, &remote)?;
    write_private(
        &pairing_dir(library.root()).join("names.json"),
        &serde_json::to_vec(
            &json!({"own_name":local.name,"peer_name":remote.name,"peer_replica":remote.replica,"fingerprint":policy.fingerprint,"setup_pending":true}),
        )?,
    )?;
    control.publish(json!({"phase":"transferring","name":remote.name}));
    let sending = remote.group.is_empty();
    send_frame(
        &mut tls,
        b'G',
        &serde_json::to_vec(
            &json!({"group":group,"computer":local.replica,"phone":remote.replica,"computer_sends":sending}),
        )?,
        MAX_CONTROL,
    )?;
    if sending {
        send_bundle(&mut tls, library, library.root(), &policy, &control.session)?;
        let staged: Value = serde_json::from_slice(&receive_frame(&mut tls, b'S', MAX_CONTROL)?)?;
        if staged != json!({"staged":true}) {
            return Err(Error::Protocol);
        }
    } else if local.group.is_empty() {
        let size = receive_header(&mut tls, b'B', MAX_BUNDLE)?;
        let mut file = tempfile::Builder::new()
            .prefix("setup-receive-")
            .suffix(".stackexchange")
            .tempfile_in(private_dir(library.root())?)?;
        copy_exact(&mut tls, &mut file, size, &control.session)?;
        file.as_file().sync_all()?;
        let review = library.sync_review_adoption_control(file.path(), &control.session.archive)?;
        if review["sender"] != remote.replica
            || review["library"] != group
            || review["members"].as_array().is_none_or(|m| {
                m.len() != 2
                    || !m.iter().any(|v| v == &local.replica)
                    || !m.iter().any(|v| v == &remote.replica)
            })
        {
            return Err(Error::Peer);
        }
        // The encrypted sender is pinned; reject a bundle attributed to anyone else.
        if control
            .state
            .lock()
            .expect("pairing progress")
            .initiator_approved
        {
            library.sync_merge_connected(file.path(), &control.session.archive)?;
        } else {
            control.state.lock().expect("pairing progress").baseline = Some(file);
        }
        send_frame(
            &mut tls,
            b'S',
            &serde_json::to_vec(&json!({"staged":true}))?,
            MAX_CONTROL,
        )?;
    }
    control.publish(json!({"phase":"ready","name":remote.name}));
    Ok(
        json!({"pairing":pairing_status(library.root())?,"peer_name":remote.name,"baseline_received":control.received_path().is_some(),"notice":"Connected · Syncing automatically"}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    fn authored() -> (Device, Device, String) {
        let computer = Device {
            name: "Authored computer 🦀".into(),
            replica: "10000000-0000-4000-8000-000000000001".into(),
            certificate: "010203".into(),
            group: String::new(),
            peer: String::new(),
            nonce: "11".repeat(32),
        };
        let mut phone = computer.clone();
        phone.name = "Authored phone".into();
        phone.replica = "20000000-0000-4000-8000-000000000002".into();
        phone.certificate = "040506".into();
        phone.nonce = "22".repeat(32);
        (computer, phone, "33".repeat(16))
    }
    #[test]
    fn pairing_transcript_is_canonical_role_bound_and_stale_approval_is_rejected() {
        let (computer, phone, invite) = authored();
        let digest = transcript(&invite, &computer, &phone).unwrap();
        println!(
            "PAIRING_CANONICAL_VECTOR={}",
            json!({"computer_commitment":commitment(b'D',&invite,&computer).unwrap(),"phone_commitment":commitment(b'P',&invite,&phone).unwrap(),"transcript":digest,"code":code(&digest).unwrap()})
        );
        assert_ne!(
            commitment(b'D', &invite, &computer).unwrap(),
            commitment(b'P', &invite, &computer).unwrap()
        );
        let mut changed = phone.clone();
        changed.name = "Other phone".into();
        assert_ne!(digest, transcript(&invite, &computer, &changed).unwrap());
        changed = phone.clone();
        changed.nonce = "44".repeat(32);
        assert_ne!(digest, transcript(&invite, &computer, &changed).unwrap());
        let control = PairingControl::default();
        control.publish(json!({"phase":"compare","token":digest}));
        assert!(!control.confirm("stale"));
        assert!(control.confirm(&digest));
        control.publish(json!({"phase":"compare","token":"new"}));
        assert!(!control.confirm(&digest));
        control.cancel();
        assert!(control.session.check().is_err());
        assert!(!valid_name("fake\ncomputer"));
        assert!(!valid_name(" "));
        assert!(valid_name("Charlie's computer 🦀"));
    }
    #[test]
    fn frozen_groups_and_existing_or_revoked_peers_cannot_be_replaced() {
        let (mut computer, mut phone, _) = authored();
        check_devices(&computer, &phone).unwrap();
        computer.group = "30000000-0000-4000-8000-000000000003".into();
        computer.peer = phone.replica.clone();
        check_devices(&computer, &phone).unwrap();
        phone.group = "40000000-0000-4000-8000-000000000004".into();
        phone.peer = computer.replica.clone();
        assert!(check_devices(&computer, &phone).is_err());
        let root = tempfile::tempdir().unwrap();
        let dir = private_dir(root.path()).unwrap();
        let mut policy = Policy {
            version: 1,
            group: computer.group.clone(),
            peer_replica: phone.replica.clone(),
            fingerprint: fingerprint(&unhex(&phone.certificate, MAX_CONTROL as usize).unwrap()),
            revoked: false,
        };
        write_private(
            &dir.join("policy.json"),
            &serde_json::to_vec(&policy).unwrap(),
        )
        .unwrap();
        assert!(can_resume(root.path(), &phone).is_ok());
        policy.revoked = true;
        write_private(
            &dir.join("policy.json"),
            &serde_json::to_vec(&policy).unwrap(),
        )
        .unwrap();
        assert!(
            can_resume(root.path(), &phone).is_ok(),
            "same pinned identity may request fresh approval after disconnect"
        );
        assert!(
            credentials(root.path()).is_err(),
            "disconnected peers remain blocked from ordinary sync"
        );
        let mut replacement = phone.clone();
        replacement.replica = uuid::Uuid::new_v4().to_string();
        assert!(can_resume(root.path(), &replacement).is_err());
    }
    #[test]
    fn invitation_reuses_existing_identity_and_refuses_partial_credentials() {
        let root = tempfile::tempdir().unwrap();
        prepare_identity(root.path()).unwrap();
        let before = identity_certificate(root.path()).unwrap();
        prepare_identity(root.path()).unwrap();
        assert_eq!(before, identity_certificate(root.path()).unwrap());
        std::fs::remove_file(pairing_dir(root.path()).join("identity-key.der")).unwrap();
        assert!(prepare_identity(root.path()).is_err());
        assert_eq!(before, identity_certificate(root.path()).unwrap());
    }
    #[test]
    fn bootstrap_deadline_cancels_without_an_input_frame_or_local_approval() {
        let control = SessionControl::default();
        let deadline = Deadline::new(control.clone(), Instant::now() + Duration::from_millis(20));
        let until = Instant::now() + Duration::from_secs(1);
        while control.check().is_ok() && Instant::now() < until {
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(control.check().is_err());
        drop(deadline);
        let control = SessionControl::default();
        let deadline = Deadline::new(control.clone(), Instant::now() + Duration::from_secs(1));
        drop(deadline);
        assert!(
            control.check().is_ok(),
            "completed bootstrap must stop its watchdog"
        );
    }
}
