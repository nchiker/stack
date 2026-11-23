//! "Add Ami Pro?": offers to install the companion editor through the system's own installer (PackageKit).
//!
//! Nothing here installs anything itself. The offer is a call to the session-bus interface
//! `org.freedesktop.PackageKit.Modify` (`InstallPackageNames`), which shows the system's confirmation and asks for
//! the user's authentication. There is no `pkexec`, no `sudo`, no shell command and no helper of our own.
//! What is installed is read locally without root (the dpkg status file, then the program search path).
use crate::{Message, Stack};
use cosmic::{Element, app, iced, widget};
use std::{
    path::{Path, PathBuf},
    time::Duration,
};

pub const PACKAGE: &str = "amipro-linux";
pub const DPKG_STATUS: &str = "/var/lib/dpkg/status";
/// Either file means the Stack apt source (and so `amipro-linux`) is known to this computer.
pub const SOURCE_FILES: [&str; 2] = [
    "/etc/apt/sources.list.d/stack-docs.sources",
    "/etc/apt/sources.list.d/stack-docs-apt-source",
];
pub const PACKAGEKIT_NAME: &str = "org.freedesktop.PackageKit";
pub const PACKAGEKIT_PATH: &str = "/org/freedesktop/PackageKit";
pub const MODIFY_INTERFACE: &str = "org.freedesktop.PackageKit.Modify";
/// The user may take a while to read the system's confirmation and type a password.
const INSTALL_TIMEOUT: Duration = Duration::from_secs(15 * 60);

pub const TITLE: &str = "Add Ami Pro?";
pub const BODY: &str = "Edit your linked documents on this computer.";
pub const INSTALLED: &str = "Ami Pro is installed.";
pub const NOT_INSTALLED: &str = "Ami Pro was not installed.";
pub const MISSING: &str = "Ami Pro is not installed";
pub const FROM_STORE: &str = "Install Ami Pro from the app store.";
pub const WORKING: &str = "Installing…";

/// What the quick local look found.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Probe {
    pub installed: bool,
    pub source: bool,
    pub packagekit: bool,
    pub dismissed: bool,
}

#[derive(Default)]
pub struct State {
    pub probe: Option<Probe>,
    pub busy: bool,
    /// The calm result line after an install attempt.
    pub result: Option<&'static str>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InstallError {
    /// No PackageKit on the session bus (or no bus).
    Missing,
    /// The user cancelled, the system refused, or the install failed.
    NotInstalled,
}

#[derive(Clone, Debug)]
pub enum Event {
    Probed(Probe),
    Install,
    Installed(Result<(), InstallError>),
    NotNow,
}

/// True when the dpkg status text lists `name` as fully installed.
pub fn dpkg_installed(status: &str, name: &str) -> bool {
    status.split("\n\n").any(|paragraph| {
        let mut package = false;
        let mut installed = false;
        for line in paragraph.lines() {
            if let Some(value) = line.strip_prefix("Package:") {
                package = value.trim() == name;
            } else if let Some(value) = line.strip_prefix("Status:") {
                installed = value.split_whitespace().last() == Some("installed");
            }
        }
        package && installed
    })
}

/// True when `program` is an executable file in one of the `path` directories (a `PATH`-style string).
pub fn on_path(path: &str, program: &str) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::env::split_paths(path).any(|dir| {
        std::fs::metadata(dir.join(program)).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
    })
}

/// Where Ami Pro's own `install.sh` puts the program when run without `--prefix` (`~/.local/bin`). A desktop session often does
/// not have that folder on its `PATH`, so it is searched in addition.
pub fn search_path(path: &str, home: Option<&Path>) -> String {
    match home {
        Some(home) => format!("{}:{}", path, home.join(".local/bin").display()),
        None => path.to_string(),
    }
}

/// The program to start for Ami Pro: plain `amipro-linux` when `path` has it, else the copy in `~/.local/bin`, else plain (so the
/// usual "could not start" message still appears).
pub fn program(path: &str, home: Option<&Path>) -> std::ffi::OsString {
    if !on_path(path, PACKAGE)
        && let Some(home) = home
    {
        let local = home.join(".local/bin");
        if on_path(local.to_str().unwrap_or(""), PACKAGE) {
            return local.join(PACKAGE).into_os_string();
        }
    }
    std::ffi::OsString::from(PACKAGE)
}

/// The apt source for the Stack repository is present.
pub fn source_configured(files: &[&Path]) -> bool {
    files.iter().any(|file| file.exists())
}

impl Probe {
    /// The library page card: Ami Pro missing, installable, and not put off.
    pub fn card(&self) -> bool {
        !self.installed && self.source && self.packagekit && !self.dismissed
    }

    /// An Install button belongs wherever Ami Pro is found missing and the system installer is there.
    pub fn can_install(&self) -> bool {
        !self.installed && self.source && self.packagekit
    }

    /// Settings only: there is no system installer to ask.
    pub fn store_hint(&self) -> bool {
        !self.installed && self.source && !self.packagekit
    }
}

fn dismissed_file() -> Option<PathBuf> {
    Some(crate::preferences::path().ok()?.parent()?.join("ami-offer-dismissed"))
}

fn set_dismissed(on: bool) {
    let Some(file) = dismissed_file() else { return };
    if on {
        if let Some(parent) = file.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let _ = std::fs::write(file, b"");
    } else {
        let _ = std::fs::remove_file(file);
    }
}

/// Everything the probe needs from the outside, so tests can point it elsewhere.
pub fn probe_local(status: &Path, sources: &[&Path], path_var: &str, dismissed: bool) -> (bool, bool, bool) {
    let installed = std::fs::read_to_string(status).is_ok_and(|text| dpkg_installed(&text, PACKAGE))
        || on_path(path_var, PACKAGE)
        // Inside a Flatpak the file system is not this computer's: nothing here can tell, so never nag.
        || Path::new("/.flatpak-info").exists();
    (installed, source_configured(sources), dismissed)
}

async fn connect(address: Option<&str>) -> zbus::Result<zbus::Connection> {
    match address {
        Some(address) => zbus::connection::Builder::address(address)?.build().await,
        None => zbus::connection::Builder::session()?.build().await,
    }
}

/// Is PackageKit running or startable on the bus? A missing bus counts as no.
pub async fn packagekit_available(address: Option<&str>) -> bool {
    let Ok(connection) = connect(address).await else { return false };
    let Ok(proxy) = zbus::fdo::DBusProxy::new(&connection).await else { return false };
    let Ok(name) = zbus::names::BusName::try_from(PACKAGEKIT_NAME) else { return false };
    if proxy.name_has_owner(name).await.unwrap_or(false) {
        return true;
    }
    proxy
        .list_activatable_names()
        .await
        .is_ok_and(|names| names.iter().any(|n| n.as_str() == PACKAGEKIT_NAME))
}

/// Ask PackageKit (session interface) to install `names`; the system shows its own confirmation and authentication.
pub async fn install_packages(address: Option<&str>, names: &[&str]) -> Result<(), InstallError> {
    let connection = connect(address).await.map_err(|_| InstallError::Missing)?;
    let names: Vec<String> = names.iter().map(|n| (*n).to_owned()).collect();
    // (xid of our window: unknown, package names, interaction hints: the system's defaults)
    let body = (0u32, names, "");
    let call = connection.call_method(
        Some(PACKAGEKIT_NAME),
        PACKAGEKIT_PATH,
        Some(MODIFY_INTERFACE),
        "InstallPackageNames",
        &body,
    );
    match tokio::time::timeout(INSTALL_TIMEOUT, call).await {
        Ok(Ok(_)) => Ok(()),
        Ok(Err(zbus::Error::MethodError(name, _, _)))
            if matches!(
                name.as_str(),
                "org.freedesktop.DBus.Error.ServiceUnknown" | "org.freedesktop.DBus.Error.NameHasNoOwner"
            ) =>
        {
            Err(InstallError::Missing)
        }
        Ok(Err(zbus::Error::NameTaken)) => Err(InstallError::Missing),
        Ok(Err(_)) | Err(_) => Err(InstallError::NotInstalled),
    }
}

/// Run `work` on its own thread and runtime and wait for it without blocking the caller's executor.
async fn off_thread<T: Send + 'static>(work: impl std::future::Future<Output = T> + Send + 'static) -> Option<T> {
    let (tx, rx) = tokio::sync::oneshot::channel();
    std::thread::spawn(move || {
        if let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() {
            let _ = tx.send(runtime.block_on(work));
        }
    });
    rx.await.ok()
}

/// Look at this computer, off the UI. `forget_not_now` is set when Settings > Ami Pro is opened.
pub fn probe_task(forget_not_now: bool) -> app::Task<Message> {
    cosmic::task::future(async move {
        let probe = off_thread(async move {
            if forget_not_now {
                set_dismissed(false);
            }
            let sources: Vec<&Path> = SOURCE_FILES.iter().map(Path::new).collect();
            let dismissed = dismissed_file().is_some_and(|f| f.exists());
            let home = std::env::var_os("HOME").map(PathBuf::from);
            let path = search_path(&std::env::var("PATH").unwrap_or_default(), home.as_deref());
            let (installed, source, dismissed) = probe_local(Path::new(DPKG_STATUS), &sources, &path, dismissed);
            let packagekit = !installed && source && packagekit_available(None).await;
            Probe { installed, source, packagekit, dismissed }
        })
        .await
        .unwrap_or_default();
        Message::AmiInstall(Event::Probed(probe))
    })
}

impl Stack {
    pub fn ami_install_action(&mut self, event: Event) -> app::Task<Message> {
        let state = &mut self.ami_install;
        match event {
            Event::Probed(probe) => {
                state.probe = Some(probe);
                app::Task::none()
            }
            Event::Install if !state.busy && state.probe.is_some_and(|p| p.can_install()) => {
                state.busy = true;
                state.result = None;
                cosmic::task::future(async {
                    let result = off_thread(async { install_packages(None, &[PACKAGE]).await })
                        .await
                        .unwrap_or(Err(InstallError::NotInstalled));
                    Message::AmiInstall(Event::Installed(result))
                })
            }
            Event::Install => app::Task::none(),
            Event::Installed(result) => {
                state.busy = false;
                match result {
                    Ok(()) => {
                        state.result = Some(INSTALLED);
                        if let Some(probe) = &mut state.probe {
                            probe.installed = true;
                        }
                    }
                    Err(InstallError::Missing) => {
                        // The system installer went away: behave as if it was never there.
                        state.result = None;
                        if let Some(probe) = &mut state.probe {
                            probe.packagekit = false;
                        }
                    }
                    Err(InstallError::NotInstalled) => state.result = Some(NOT_INSTALLED),
                }
                app::Task::none()
            }
            Event::NotNow => {
                state.result = None;
                if let Some(probe) = &mut state.probe {
                    probe.dismissed = true;
                }
                std::thread::spawn(|| set_dismissed(true));
                app::Task::none()
            }
        }
    }

    /// The card on the library page (or the calm result line after an install).
    pub fn ami_install_card(&self) -> Option<Element<'_, Message>> {
        let state = &self.ami_install;
        let probe = state.probe?;
        if probe.installed {
            return state.result.map(|line| widget::container(self.body(line)).padding([0, 0, 4, 0]).into());
        }
        if !probe.card() && !state.busy {
            return None;
        }
        let mut column = widget::column::with_capacity(4)
            .spacing(8)
            .push(widget::text::heading(TITLE))
            .push(self.body(BODY));
        if state.busy {
            column = column.push(self.body(WORKING));
        } else {
            if let Some(line) = state.result {
                column = column.push(self.body(line));
            }
            column = column.push(
                widget::row::with_capacity(2)
                    .spacing(8)
                    .push(self.button("Install").on_press(Message::AmiInstall(Event::Install)))
                    .push(self.button("Not now").on_press(Message::AmiInstall(Event::NotNow))),
            );
        }
        Some(widget::container(column).padding(12).class(cosmic::theme::Container::Card).width(iced::Length::Fill).into())
    }

    /// Settings > Ami Pro.
    pub fn ami_install_settings(&self) -> Element<'_, Message> {
        let state = &self.ami_install;
        let mut column = widget::column::with_capacity(4).spacing(12).push(widget::text::heading("Ami Pro"));
        match state.probe {
            Some(probe) if probe.installed => column = column.push(self.body(INSTALLED)),
            Some(probe) if probe.can_install() => {
                column = column.push(self.body(BODY));
                column = if state.busy {
                    column.push(self.body(WORKING))
                } else {
                    column.push(self.button("Install").on_press(Message::AmiInstall(Event::Install)))
                };
            }
            Some(probe) if probe.store_hint() => column = column.push(self.body(FROM_STORE)),
            _ => column = column.push(self.body(MISSING)),
        }
        if let Some(line) = state.result.filter(|_| !state.busy) {
            column = column.push(self.body(line));
        }
        column.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        io::{BufRead, BufReader},
        sync::{Arc, Mutex},
    };

    const STATUS: &str = "Package: bash\nStatus: install ok installed\nVersion: 5\n\n\
Package: amipro-linux\nStatus: install ok installed\nVersion: 1\n\n\
Package: other\nStatus: deinstall ok config-files\n";

    #[test]
    fn dpkg_status_is_read_by_package_and_state() {
        assert!(dpkg_installed(STATUS, "amipro-linux"));
        assert!(dpkg_installed(STATUS, "bash"));
        assert!(!dpkg_installed(STATUS, "other"));
        assert!(!dpkg_installed(STATUS, "amipro"));
        assert!(!dpkg_installed("Package: amipro-linux\nStatus: deinstall ok config-files\n", "amipro-linux"));
        assert!(!dpkg_installed("Package: amipro-linux\nStatus: install ok half-installed\n", "amipro-linux"));
        assert!(!dpkg_installed("", "amipro-linux"));
    }

    #[test]
    fn the_offer_follows_what_this_computer_has() {
        let probe = |installed, source, packagekit, dismissed| Probe { installed, source, packagekit, dismissed };
        assert!(probe(false, true, true, false).card());
        assert!(!probe(true, true, true, false).card());
        assert!(!probe(false, false, true, false).card());
        assert!(!probe(false, true, false, false).card(), "no PackageKit: no card");
        assert!(!probe(false, true, true, true).card(), "Not now hides the card");
        assert!(probe(false, true, true, true).can_install(), "but Open in Ami Pro still offers Install");
        assert!(probe(false, true, false, false).store_hint());
        assert!(!probe(false, true, true, false).store_hint());
        assert!(!probe(false, false, false, false).store_hint());
    }

    #[test]
    fn local_probe_reads_files_and_path() {
        let dir = tempfile::tempdir().unwrap();
        let status = dir.path().join("status");
        let source = dir.path().join("stack-docs.sources");
        let none = dir.path().join("none");
        std::fs::write(&status, "Package: x\nStatus: install ok installed\n").unwrap();
        let (installed, has_source, dismissed) = probe_local(&status, &[&none, &source], "", true);
        assert!((installed, has_source, dismissed) == (false, false, true) || Path::new("/.flatpak-info").exists());
        std::fs::write(&source, "").unwrap();
        assert!(probe_local(&status, &[&none, &source], "", false).1);
        std::fs::write(&status, STATUS).unwrap();
        assert!(probe_local(&status, &[], "", false).0);
        // A program on the search path counts too.
        let bin = dir.path().join("bin");
        std::fs::create_dir(&bin).unwrap();
        let program = bin.join(PACKAGE);
        std::fs::write(&program, "").unwrap();
        assert!(!on_path(bin.to_str().unwrap(), PACKAGE), "not executable yet");
        std::fs::set_permissions(&program, std::os::unix::fs::PermissionsExt::from_mode(0o755)).unwrap();
        assert!(on_path(bin.to_str().unwrap(), PACKAGE));
    }

    #[test]
    fn ami_pro_from_its_install_script_is_found_without_local_bin_on_path() {
        let home = tempfile::tempdir().unwrap();
        let local = home.path().join(".local/bin");
        std::fs::create_dir_all(&local).unwrap();
        let program_file = local.join(PACKAGE);
        std::fs::write(&program_file, "").unwrap();
        std::fs::set_permissions(&program_file, std::os::unix::fs::PermissionsExt::from_mode(0o755)).unwrap();
        let status = home.path().join("status");
        let source = home.path().join("stack-docs.sources");
        std::fs::write(&source, "").unwrap();
        // A plain search path misses it; the extended one finds it, so the "Add Ami Pro?" card stays hidden.
        assert!(!probe_local(&status, &[&source], "/nonexistent", false).0 || Path::new("/.flatpak-info").exists());
        let extended = search_path("/nonexistent", Some(home.path()));
        assert!(probe_local(&status, &[&source], &extended, false).0);
        // Starting it uses the full path; with nothing installed it falls back to the plain name.
        assert_eq!(program("/nonexistent", Some(home.path())), program_file.as_os_str());
        assert_eq!(program("/nonexistent", None), std::ffi::OsString::from(PACKAGE));
        let empty = tempfile::tempdir().unwrap();
        assert_eq!(program("/nonexistent", Some(empty.path())), std::ffi::OsString::from(PACKAGE));
        // Found on the normal path: the plain name is kept.
        let bin = home.path().join(".local/bin");
        assert_eq!(program(bin.to_str().unwrap(), Some(empty.path())), std::ffi::OsString::from(PACKAGE));
    }

    type Calls = Arc<Mutex<Vec<(u32, Vec<String>, String)>>>;

    struct Modify {
        calls: Calls,
        fail: bool,
    }

    #[zbus::interface(name = "org.freedesktop.PackageKit.Modify")]
    impl Modify {
        async fn install_package_names(&self, xid: u32, names: Vec<String>, interaction: String) -> zbus::fdo::Result<()> {
            self.calls.lock().unwrap().push((xid, names, interaction));
            if self.fail {
                Err(zbus::fdo::Error::Failed("cancelled".into()))
            } else {
                Ok(())
            }
        }
    }

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

    async fn serve(address: &str, fail: bool) -> (zbus::Connection, Calls) {
        let calls: Calls = Arc::default();
        let connection = zbus::connection::Builder::address(address)
            .unwrap()
            .serve_at(PACKAGEKIT_PATH, Modify { calls: calls.clone(), fail })
            .unwrap()
            .name(PACKAGEKIT_NAME)
            .unwrap()
            .build()
            .await
            .unwrap();
        (connection, calls)
    }

    #[test]
    fn install_calls_packagekit_modify_and_maps_failures() {
        let Some((mut bus, address)) = private_bus() else {
            eprintln!("SKIP: no dbus-daemon");
            return;
        };
        let runtime = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
        // Nothing on the bus: PackageKit is missing, both for the availability check and the call.
        assert!(!runtime.block_on(packagekit_available(Some(&address))));
        assert_eq!(runtime.block_on(install_packages(Some(&address), &[PACKAGE])), Err(InstallError::Missing));

        let (connection, calls) = runtime.block_on(serve(&address, false));
        assert!(runtime.block_on(packagekit_available(Some(&address))));
        assert_eq!(runtime.block_on(install_packages(Some(&address), &[PACKAGE])), Ok(()));
        assert_eq!(*calls.lock().unwrap(), vec![(0u32, vec!["amipro-linux".to_owned()], String::new())]);
        drop(connection);

        // The system refused or the user cancelled.
        std::thread::sleep(Duration::from_millis(200));
        let (connection, calls) = runtime.block_on(serve(&address, true));
        assert_eq!(runtime.block_on(install_packages(Some(&address), &[PACKAGE])), Err(InstallError::NotInstalled));
        assert_eq!(calls.lock().unwrap().len(), 1);
        drop(connection);

        // No bus at all.
        assert_eq!(
            runtime.block_on(install_packages(Some("unix:path=/nonexistent/stack-test-bus"), &[PACKAGE])),
            Err(InstallError::Missing)
        );
        assert!(!runtime.block_on(packagekit_available(Some("unix:path=/nonexistent/stack-test-bus"))));
        let _ = bus.kill();
        let _ = bus.wait();
    }

    #[test]
    fn results_update_the_state_calmly() {
        let mut state = State { probe: Some(Probe { source: true, packagekit: true, ..Probe::default() }), ..State::default() };
        // The state transitions are plain; mirror the handler's outcomes.
        assert!(state.probe.unwrap().can_install());
        state.result = Some(NOT_INSTALLED);
        assert_eq!(state.result, Some("Ami Pro was not installed."));
        assert_eq!(INSTALLED, "Ami Pro is installed.");
    }
}
