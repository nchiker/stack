use std::env;
use std::fs;
use std::net::SocketAddr;
use std::path::Path;

fn usage() -> &'static str {
    "Usage: stack-transport identity-init ROOT | identity-show ROOT | peer-enroll ROOT GROUP PEER_REPLICA PEER_CERT_DER VERIFIED_SHA256 | peer-revoke ROOT | listen-once ROOT ADDRESS:PORT | sync-now ROOT ADDRESS:PORT"
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().skip(1).collect();
    let (command, rest) = args.split_first().ok_or(usage())?;
    let output = match (command.as_str(), rest) {
        ("identity-init", [root]) => {
            serde_json::json!({"fingerprint_sha256":stack_transport::identity_init(Path::new(root))?})
        }
        ("identity-show", [root]) => {
            let cert = stack_transport::identity_certificate(Path::new(root))?;
            serde_json::json!({"fingerprint_sha256":stack_transport::fingerprint(&cert),"certificate_der_base64":null,"certificate_path":Path::new(root).join("pairing/identity.der")})
        }
        ("peer-enroll", [root, group, peer, cert, verified]) => {
            stack_transport::peer_enroll(Path::new(root), group, peer, &fs::read(cert)?, verified)?;
            serde_json::json!({"enrolled":true,"peer":peer,"group":group})
        }
        ("peer-revoke", [root]) => {
            stack_transport::peer_revoke(Path::new(root))?;
            serde_json::json!({"revoked":true})
        }
        ("listen-once", [root, address]) => {
            stack_transport::listen_once(Path::new(root), address.parse::<SocketAddr>()?)?
        }
        ("sync-now", [root, address]) => {
            stack_transport::sync_now(Path::new(root), address.parse::<SocketAddr>()?)?
        }
        _ => return Err(usage().into()),
    };
    println!("{}", serde_json::to_string_pretty(&output)?);
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("stack-transport: {error}");
        std::process::exit(1);
    }
}
