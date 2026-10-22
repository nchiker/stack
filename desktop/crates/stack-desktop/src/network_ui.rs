//! Automatic paired LAN exchange using the existing repository owner and transport.
use crate::{
    Message, Stack,
    repository::SyncRequest,
    sync_ui::{Action as SyncAction, Pending},
};
use cosmic::{Element, app, widget};
use serde_json::{Value, json};
use stack_transport::{Result, SessionControl};
use std::{
    net::{IpAddr, SocketAddr, TcpListener, TcpStream, UdpSocket},
    path::Path,
};

#[derive(Clone, Debug)]
pub enum Action {
    Nearby(crate::pairing_ui::Action),
    Revoke,
    Tick,
}
pub enum Request {
    Accepted {
        stream: TcpStream,
        control: SessionControl,
    },
    Nearby {
        name: String,
        address: SocketAddr,
        addresses: Vec<String>,
        control: stack_transport::pairing::PairingControl,
    },
    Revoke,
}

pub struct Ui {
    pub nearby: crate::pairing_ui::Ui,
    pub status: Value,
    pub control: Option<SessionControl>,
    listener: Option<TcpListener>,
    discovery: Option<UdpSocket>,
    initialized: bool,
    pub last_sync: Option<std::time::Instant>,
    pub disconnecting: bool,
    pub last_setup_attempt: Option<std::time::Instant>,
    pairing_checked: Option<std::time::Instant>,
    pending_connection: Option<TcpStream>,
}
impl Ui {
    pub fn stop_listener(&mut self) {
        self.listener = None;
        self.discovery = None;
        self.pending_connection = None;
    }
}
impl Default for Ui {
    fn default() -> Self {
        Self {
            nearby: crate::pairing_ui::Ui::default(),
            status: Value::Null,
            control: None,
            listener: None,
            discovery: None,
            initialized: false,
            last_sync: None,
            disconnecting: false,
            last_setup_attempt: Some(std::time::Instant::now()),
            pairing_checked: None,
            pending_connection: None,
        }
    }
}

fn parse_local_addresses(text: &str) -> Vec<String> {
    let mut addresses: Vec<_> = text
        .split_whitespace()
        .filter_map(|s| s.parse::<IpAddr>().ok())
        .filter(|ip| !ip.is_loopback() && !ip.is_unspecified() && !ip.is_multicast())
        .filter(|ip| !matches!(ip, IpAddr::V6(v6) if v6.is_unicast_link_local()))
        .map(|ip| ip.to_string())
        .collect();
    addresses.sort();
    addresses.dedup();
    addresses
}

/// Enumerate this computer's own interface addresses off the UI thread. This
/// neither discovers peers nor sends traffic; discovery can still use broadcasts.
pub(crate) fn pairing_status(root: &Path) -> Result<Value> {
    let mut status = stack_transport::pairing_status(root)?;
    let addresses = std::process::Command::new("hostname")
        .arg("-I")
        .output()
        .ok()
        .filter(|output| output.status.success() && output.stdout.len() <= 16384)
        .map(|output| parse_local_addresses(&String::from_utf8_lossy(&output.stdout)))
        .unwrap_or_default();
    status["local_addresses"] = json!(addresses);
    status["labels"] = stack_transport::pairing::device_labels(root)?;
    Ok(status)
}

pub fn execute(library: &mut stack_core::library::Library, request: Request) -> Result<Value> {
    let root = library.root().to_owned();
    let notice = match request {
        Request::Accepted { stream, control } => {
            let result = stack_transport::serve_accepted_library(library, stream, &control)?;
            stack_transport::pairing::mark_complete(library.root())?;
            stack_transport::pairing::record_sync(library.root())?;
            return Ok(
                json!({"pairing":pairing_status(library.root())?,"session":result,"notice":"Up to date"}),
            );
        }
        Request::Nearby {
            name,
            address,
            addresses,
            control,
        } => {
            let mut result = stack_transport::pairing::invite_library(
                library, &name, address, &addresses, &control,
            )?;
            result["pairing"] = pairing_status(&root)?;
            return Ok(result);
        }
        Request::Revoke => {
            stack_transport::peer_revoke(&root)?;
            "Disconnected"
        }
    };
    Ok(json!({"pairing":pairing_status(&root)?,"notice":notice}))
}

fn message(action: Action) -> Message {
    Message::Sync(SyncAction::Network(action))
}

impl Stack {
    pub fn network_action(&mut self, action: Action) -> app::Task<Message> {
        match action {
            Action::Nearby(action) => self.nearby_action(action),
            Action::Tick => self.automatic_sync_tick(),
            Action::Revoke => {
                self.sync.network.stop_listener();
                if self.sync.busy {
                    self.sync.network.disconnecting = true;
                    if let Some(control) = &self.sync.network.control {
                        control.cancel();
                    }
                    return app::Task::none();
                }
                self.sync_request(
                    SyncRequest::Network(Request::Revoke),
                    Pending::NetworkStatus,
                )
            }
        }
    }

    fn automatic_sync_tick(&mut self) -> app::Task<Message> {
        self.sync.network.nearby.poll();
        if self.repository.is_none() || self.flags.demo.is_some() {
            return app::Task::none();
        }
        // The listener needs only the small pairing files. Read them directly and
        // retry until they load, rather than waiting on a single library status
        // request through the repository worker: if that request fails or is
        // delayed, the phone would otherwise find no receiver until a restart.
        if !self.sync.network.status["enrolled"].is_boolean()
            && self
                .sync
                .network
                .pairing_checked
                .is_none_or(|checked| checked.elapsed() >= std::time::Duration::from_secs(2))
            && let Some(root) = &self.flags.library
        {
            self.sync.network.pairing_checked = Some(std::time::Instant::now());
            if let Ok(mut status) = stack_transport::pairing_status(root) {
                status["labels"] =
                    stack_transport::pairing::device_labels(root).unwrap_or(Value::Null);
                self.sync.network.status = status;
            }
        }
        if !self.sync.network.initialized && !self.sync.busy {
            self.sync.network.initialized = true;
            return self.sync_request(SyncRequest::Status, Pending::Status);
        }
        if self.sync.network.status["enrolled"] != true {
            self.sync.network.listener = None;
            self.sync.network.discovery = None;
            return app::Task::none();
        }
        // An approved connection can be interrupted before its initial baseline
        // arrives. Reopen the same pinned setup, allowing ordinary sync time to
        // finish first; completed connections never re-enter this path.
        if self.sync.network.status["labels"]["setup_pending"] == true
            && self.sync.network.pending_connection.is_none()
            && !self.sync.busy
            && !self.transfers.busy()
            && !self.saving
            && self
                .sync
                .network
                .last_setup_attempt
                .is_none_or(|attempt| attempt.elapsed() >= std::time::Duration::from_secs(60))
        {
            return self.nearby_action(crate::pairing_ui::Action::Start);
        }
        if self.sync.network.listener.is_none() {
            match TcpListener::bind("0.0.0.0:45873") {
                Ok(listener) => {
                    let _ = listener.set_nonblocking(true);
                    self.sync.network.listener = Some(listener);
                }
                Err(_) => {
                    self.sync.error = Some("Another Stack window is using the connection. Close it and Stack will try again.".into());
                    return app::Task::none();
                }
            }
            if let Ok(socket) = UdpSocket::bind("0.0.0.0:45874") {
                let _ = socket.set_nonblocking(true);
                self.sync.network.discovery = Some(socket);
            }
        }
        // The existing discovery response is also an address hint after Wi-Fi
        // changes. It conveys no authority: the ordinary pinned TLS check follows.
        if let Some(socket) = &self.sync.network.discovery {
            let mut bytes = [0; 1024];
            for _ in 0..8 {
                let Ok((n, peer)) = socket.recv_from(&mut bytes) else {
                    break;
                };
                if let Ok(request) = serde_json::from_slice::<Value>(&bytes[..n])
                    && request["stack_find"] == 1
                    && request["nonce"].as_str().is_some_and(|n| n.len() == 32)
                {
                    let name = self.sync.network.status["labels"]["own_name"]
                        .as_str()
                        .unwrap_or("My computer");
                    let pin = self.sync.network.status["fingerprint_sha256"]
                        .as_str()
                        .unwrap_or("");
                    let reply = json!({"stack_pair":1,"invite":pin.chars().take(32).collect::<String>(),"name":name,"port":45873,"nonce":request["nonce"]});
                    let _ = socket.send_to(reply.to_string().as_bytes(), peer);
                }
            }
        }
        if self.sync.busy || self.transfers.busy() || self.saving {
            // An already approved phone may reconnect while an incomplete
            // bootstrap waits. Yield that writer to its ordinary TLS connection;
            // otherwise the phone waits for TLS while its setup callback waits
            // behind the same connection on the phone's IO executor.
            if self.sync.busy
                && self.sync.network.pending_connection.is_none()
                && let Some(control) = &self.sync.network.nearby.control
                && let Some(listener) = &self.sync.network.listener
                && let Ok((stream, _)) = listener.accept()
            {
                self.sync.network.pending_connection = Some(stream);
                control.cancel();
            }
            return app::Task::none();
        }
        let incoming = self.sync.network.pending_connection.take().or_else(|| {
            self.sync
                .network
                .listener
                .as_ref()?
                .accept()
                .ok()
                .map(|(stream, _)| stream)
        });
        if let Some(stream) = incoming {
            let control = SessionControl::default();
            self.sync.network.control = Some(control.clone());
            return self.sync_request(
                SyncRequest::Network(Request::Accepted { stream, control }),
                Pending::NetworkSession,
            );
        }
        app::Task::none()
    }

    pub fn network_view(&self) -> Element<'_, Message> {
        let status = &self.sync.network.status;
        let connected = self
            .sync
            .network
            .last_sync
            .is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(45));
        let mut content = widget::column::with_capacity(8)
            .spacing(16)
            .push(widget::text::title3(
                status["labels"]["peer_name"]
                    .as_str()
                    .unwrap_or("Your phone"),
            ))
            .push(self.body(if self.sync.busy {
                "Syncing…"
            } else if connected {
                "Connected · Up to date"
            } else {
                "Waiting for phone · Syncs when Stack is open on your phone"
            }));
        let last = status["labels"]["last_sync"]
            .as_u64()
            .map(|time| {
                let ago = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs()
                    .saturating_sub(time);
                if ago < 60 {
                    "Last sync: just now".to_owned()
                } else if ago < 3600 {
                    format!("Last sync: {} minutes ago", ago / 60)
                } else {
                    format!("Last sync: {} hours ago", ago / 3600)
                }
            })
            .unwrap_or_else(|| "Last sync: waiting for first sync".into());
        content = content.push(self.body(last));
        content = content
            .push(
                self.button("Sync now")
                    .on_press_maybe((!self.sync.busy).then_some(message(Action::Tick))),
            )
            .push(self.button("Disconnect").on_press(message(Action::Revoke)));
        content.into()
    }
}

pub fn subscription(_ui: &Ui) -> cosmic::iced::Subscription<Message> {
    cosmic::iced::time::every(std::time::Duration::from_millis(500)).map(|_| message(Action::Tick))
}

#[cfg(test)]
mod tests {
    #[test]
    fn local_address_labels_exclude_unusable_destinations_and_duplicates() {
        assert_eq!(
            super::parse_local_addresses(
                "127.0.0.1 0.0.0.0 ::1 :: fe80::1 224.0.0.1 invalid 192.168.1.20 192.168.1.20 fd00::2"
            ),
            vec!["192.168.1.20", "fd00::2"]
        );
    }
}
