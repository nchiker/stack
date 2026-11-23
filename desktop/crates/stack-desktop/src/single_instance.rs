//! One window per computer: a second launch asks the running Stack to come forward and quits quietly.
//!
//! The running app owns the session-bus name `org.stackdocs.desktop` and serves the standard freedesktop
//! `org.freedesktop.Application` interface (`Activate`) at `/org/stackdocs/desktop`. A new process first looks for
//! that name; if someone owns it, it calls `Activate` (passing `XDG_ACTIVATION_TOKEN` / `DESKTOP_STARTUP_ID` so
//! Wayland lets the window take focus) and exits 0. If there is no owner, the call fails or it takes longer than
//! a few seconds, it simply starts as before, and the library lock still gives the plain "already open" message.
use std::{collections::HashMap, time::Duration};
use zbus::zvariant::{OwnedValue, Value};

pub const BUS_NAME: &str = "org.stackdocs.desktop";
pub const OBJECT_PATH: &str = "/org/stackdocs/desktop";
const HAND_OFF_TIMEOUT: Duration = Duration::from_secs(3);

/// What a second launch found.
#[derive(Debug, PartialEq, Eq)]
pub enum Handoff {
    /// The running instance accepted the request; this process should exit quietly.
    Accepted,
    /// Nobody owns the name (no running instance, or no session bus).
    NoInstance,
    /// An instance seems to exist but did not answer in time or refused; start normally (the lock message follows).
    Failed(String),
}

/// The activation hints a launcher passed to this process.
pub fn platform_data() -> HashMap<String, OwnedValue> {
    let mut data = HashMap::new();
    for (key, variable) in [("activation-token", "XDG_ACTIVATION_TOKEN"), ("desktop-startup-id", "DESKTOP_STARTUP_ID")] {
        if let Some(value) = std::env::var(variable).ok().filter(|v| !v.is_empty())
            && let Ok(value) = OwnedValue::try_from(Value::from(value))
        {
            data.insert(key.to_owned(), value);
        }
    }
    data
}

/// Ask the instance that owns `name` on the bus at `address` (the session bus when `None`) to come forward.
pub fn hand_off(address: Option<&str>, name: &str, path: &str, data: HashMap<String, OwnedValue>) -> Handoff {
    let (address, name, path) = (address.map(str::to_owned), name.to_owned(), path.to_owned());
    let (done, wait) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(runtime) => runtime,
            Err(e) => return drop(done.send(Handoff::Failed(e.to_string()))),
        };
        let _ = done.send(runtime.block_on(call(address, name, path, data)));
    });
    wait.recv_timeout(HAND_OFF_TIMEOUT)
        .unwrap_or_else(|_| Handoff::Failed("The running Stack did not answer".into()))
}

async fn connect(address: Option<&str>) -> zbus::Result<zbus::Connection> {
    match address {
        Some(address) => zbus::connection::Builder::address(address)?.build().await,
        None => zbus::connection::Builder::session()?.build().await,
    }
}

async fn call(address: Option<String>, name: String, path: String, data: HashMap<String, OwnedValue>) -> Handoff {
    let Ok(connection) = connect(address.as_deref()).await else {
        return Handoff::NoInstance;
    };
    let Ok(bus_name) = zbus::names::BusName::try_from(name.as_str()) else {
        return Handoff::Failed("bad bus name".into());
    };
    let proxy = match zbus::fdo::DBusProxy::new(&connection).await {
        Ok(proxy) => proxy,
        Err(e) => return Handoff::Failed(e.to_string()),
    };
    match proxy.name_has_owner(bus_name).await {
        Ok(true) => {}
        Ok(false) => return Handoff::NoInstance,
        Err(e) => return Handoff::Failed(e.to_string()),
    }
    match connection
        .call_method(Some(name.as_str()), path.as_str(), Some("org.freedesktop.Application"), "Activate", &(data,))
        .await
    {
        Ok(_) => Handoff::Accepted,
        Err(e) => Handoff::Failed(e.to_string()),
    }
}

/// The token a launcher handed over in `Activate`'s platform data, if any.
pub fn token_of(data: &HashMap<String, OwnedValue>) -> Option<String> {
    let value = data.get("activation-token")?;
    <&str>::try_from(value).ok().filter(|t| !t.is_empty()).map(str::to_owned)
}

/// The served side: tells the app (through `tx`) that someone asked for the window, with the activation token if any.
pub struct Application {
    tx: tokio::sync::mpsc::UnboundedSender<Option<String>>,
}

#[zbus::interface(name = "org.freedesktop.Application")]
impl Application {
    async fn activate(&self, platform_data: HashMap<String, OwnedValue>) {
        let _ = self.tx.send(token_of(&platform_data));
    }

    async fn open(&self, _uris: Vec<String>, platform_data: HashMap<String, OwnedValue>) {
        let _ = self.tx.send(token_of(&platform_data));
    }

    async fn activate_action(&self, _action: String, _parameter: Vec<OwnedValue>, platform_data: HashMap<String, OwnedValue>) {
        let _ = self.tx.send(token_of(&platform_data));
    }
}

/// Own `name` and serve `Application` at `path`. The object is served before the name is requested, so a caller that
/// sees the name never reaches a missing object. Errors (name taken, no bus) mean "no activation"; the app still runs.
pub async fn serve(
    address: Option<&str>,
    name: &str,
    path: &str,
    tx: tokio::sync::mpsc::UnboundedSender<Option<String>>,
) -> zbus::Result<zbus::Connection> {
    let builder = match address {
        Some(address) => zbus::connection::Builder::address(address)?,
        None => zbus::connection::Builder::session()?,
    };
    // Never take the name from, or let it be taken by, another instance.
    builder
        .serve_at(path, Application { tx })?
        .name(name)?
        .allow_name_replacements(false)
        .replace_existing_names(false)
        .build()
        .await
}

/// The subscription stream of the running app: each request to come forward becomes `Message::Activate(token)`.
pub fn stream() -> impl cosmic::iced::futures::Stream<Item = crate::Message> {
    cosmic::iced::stream::channel(4, |mut output: cosmic::iced::futures::channel::mpsc::Sender<crate::Message>| async move {
        use cosmic::iced::futures::SinkExt;
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        // Keep the connection alive for as long as the subscription runs.
        let _connection = serve(None, BUS_NAME, OBJECT_PATH, tx).await.ok();
        while let Some(token) = rx.recv().await {
            if output.send(crate::Message::Activate(token)).await.is_err() {
                break;
            }
        }
        std::future::pending::<()>().await;
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};

    /// A private message bus (never the real session). `None` when no `dbus-daemon` is installed.
    fn private_bus() -> Option<(std::process::Child, String)> {
        let mut child = std::process::Command::new("dbus-daemon")
            .args(["--session", "--nofork", "--print-address=1"])
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::null())
            .spawn()
            .ok()?;
        let mut line = String::new();
        BufReader::new(child.stdout.take()?).read_line(&mut line).ok()?;
        let address = line.trim().to_owned();
        (!address.is_empty()).then_some((child, address))
    }

    #[test]
    fn a_second_launch_reaches_the_running_instance_and_otherwise_falls_back() {
        let Some((mut bus, address)) = private_bus() else {
            eprintln!("SKIP: no dbus-daemon");
            return;
        };
        // Nobody serves the name yet: nothing to hand off to, the process starts normally.
        let none = hand_off(Some(&address), "org.stackdocs.desktop.test", OBJECT_PATH, HashMap::new());
        assert_eq!(none, Handoff::NoInstance);

        let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let connection = runtime
            .block_on(serve(Some(&address), "org.stackdocs.desktop.test", OBJECT_PATH, tx))
            .expect("serve on the private bus");
        let mut data = HashMap::new();
        data.insert("activation-token".to_owned(), OwnedValue::try_from(Value::from("tok-1")).unwrap());
        let accepted = hand_off(Some(&address), "org.stackdocs.desktop.test", OBJECT_PATH, data);
        assert_eq!(accepted, Handoff::Accepted);
        assert_eq!(runtime.block_on(rx.recv()), Some(Some("tok-1".to_owned())));
        // Without a token the running app is still asked to come forward.
        assert_eq!(
            hand_off(Some(&address), "org.stackdocs.desktop.test", OBJECT_PATH, HashMap::new()),
            Handoff::Accepted
        );
        assert_eq!(runtime.block_on(rx.recv()), Some(None));
        // The name exists but the object does not answer (wrong path): a failure, so the caller starts normally.
        assert!(matches!(
            hand_off(Some(&address), "org.stackdocs.desktop.test", "/nowhere", HashMap::new()),
            Handoff::Failed(_)
        ));
        // A second owner of the same name cannot be served: the second app instance keeps running without activation.
        let (tx2, _rx2) = tokio::sync::mpsc::unbounded_channel();
        let second = runtime.block_on(serve(Some(&address), "org.stackdocs.desktop.test", OBJECT_PATH, tx2));
        assert!(second.is_err(), "{:?}", second.map(|_| ()));
        drop(connection);
        // The first instance quit: back to "no instance".
        std::thread::sleep(Duration::from_millis(200));
        assert_eq!(
            hand_off(Some(&address), "org.stackdocs.desktop.test", OBJECT_PATH, HashMap::new()),
            Handoff::NoInstance
        );
        let _ = bus.kill();
        let _ = bus.wait();
    }

    #[test]
    fn no_bus_at_all_means_no_instance() {
        assert_eq!(
            hand_off(Some("unix:path=/nonexistent/stack-test-bus"), BUS_NAME, OBJECT_PATH, HashMap::new()),
            Handoff::NoInstance
        );
    }

    #[test]
    fn the_token_is_read_only_when_it_is_a_nonempty_string() {
        let mut data = HashMap::new();
        assert_eq!(token_of(&data), None);
        data.insert("activation-token".to_owned(), OwnedValue::try_from(Value::from("")).unwrap());
        assert_eq!(token_of(&data), None);
        data.insert("activation-token".to_owned(), OwnedValue::try_from(Value::from("abc")).unwrap());
        assert_eq!(token_of(&data).as_deref(), Some("abc"));
        data.insert("activation-token".to_owned(), OwnedValue::try_from(Value::from(7u32)).unwrap());
        assert_eq!(token_of(&data), None);
    }
}
