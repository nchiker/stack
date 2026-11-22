# Stack desktop as a native .deb: plan (2026-11-22)

Decision already made by the owner: ship a .deb (Pop!_OS 24.04 on both computers) instead of the Flatpak.
This plan is read-only research; nothing was built, installed or changed. Claims are cited as file:line; `UNVERIFIED` marks guesses.

## 0. Key finding: the library does not live in the Flatpak

`desktop/packaging/stack-env:5-6` points `XDG_DATA_HOME`/`XDG_CONFIG_HOME` at the HOST folders, and `org.stackdocs.desktop.yml:17-18`
grants those two folders. `library.rs:78-95` (`default_root`) then resolves to the same place a native app uses. Checked on this computer:

- Library, pairing identity/peer/policy, sync blobs: `~/.local/share/org.stackdocs.desktop/` (host path, 417 MB).
- Preferences: `~/.config/org.stackdocs.desktop/view.json` (`preferences.rs:10-19`).
- `~/.var/app/org.stackdocs.desktop/` holds only caches, an EMPTY `data/keyrings/`, an empty `data/org.stackdocs.desktop/`. No user data.
- Backup key: 0.5.1 stores it in the host Secret Service (handoff "key status: store host Secret Service"; `keystore.rs:352-355`, 383-390).
  The attribute is `application=org.stackdocs.desktop` + `purpose=backup-recovery-key` (`keystore.rs:200-213`). Outside a Flatpak
  `attributes()` falls back to the same constant, so a deb build finds the same item. `oo7::Keyring::new()` (`keystore.rs:344`) uses
  the same D-Bus default collection `open_host_keyring` used (`keystore.rs:304-312`). UNVERIFIED until tested (step 8).

So "migration" is mostly: install the deb, open the same folders. The risks are coexistence and the old portal key file (section 3).

## 1. Build

- Package name `stack-docs` (NOT `stack`: Debian's Haskell `stack` owns `/usr/bin/stack`). Version = metainfo newest release
  (as `build_flatpak.py:35-40`), e.g. `0.6.0`. Next version needs no schema change (library schema stays 7) so the Flatpak can still open it.
- Method: a new `desktop/tools/package_deb.sh`-style tool modeled on `~/AmiPro-Linux/tools/package-deb.sh` (hand-rolled `dpkg-deb --root-owner-group --build`,
  offline, refuses when sources are newer than the binary, `dpkg-shlibdeps` for Depends, postinst for icon/desktop caches). Written in Python to match
  `desktop/tools/*.py`. No cargo-deb (not installed; not needed). Tools present here: dpkg-deb, dpkg-shlibdeps, fakeroot, apt-ftparchive, gpg, desktop-file-validate, appstreamcli.
- Build input: `cargo build --release --offline --locked -p stack-desktop -p stack-cli -p stack-transport`. There is no `target/release` today (only debug), so the first release build is a real step.
- Layout:
  - `/usr/bin/stack-desktop` (the app), `/usr/bin/stack-cli` (command line). `stack-transport` -> `/usr/lib/stack-docs/stack-transport` (check whether anything spawns it; none found by grep).
  - `/usr/lib/stack-docs/runtime/pdfium/lib/libpdfium.so` and `/usr/lib/stack-docs/runtime/ocr/{lib,tessdata}` (copy `runtime/`, 17 MB, minus `*.tgz *.zip` as `package_desktop.py:36`). Everything is inside the package; nothing downloads at first run.
  - `/usr/share/applications/org.stackdocs.desktop.desktop` with `Exec=stack-desktop`, icon `/usr/share/icons/hicolor/scalable/apps/org.stackdocs.desktop.svg`,
    `/usr/share/metainfo/org.stackdocs.desktop.metainfo.xml` (existing files; keep app id so the window class matches `StartupWMClass`),
    `/usr/share/doc/stack-docs/{copyright,notices/}` (dependency notices from `package_notices.py`).
  - No MIME/URL handlers exist today (the desktop file has none); add none.
- Depends: generate with `dpkg-shlibdeps` on the three binaries AND the bundled `libtesseract.so.5`/`liblept.so.5`/`libpdfium.so`. Known from `ldd`: the debug binary
  needs libxkbcommon0 + libc/libgcc; the toolkit dlopens Wayland/EGL/Vulkan/X11 (Ami Pro adds `libwayland-client0, libegl1, libvulkan1, libx11-6, libx11-xcb1`: copy that list).
  The OCR libs need (ldd): libarchive13, libcurl4, libgomp1, libpng16-16, libjpeg-turbo8 (provides `libjpeg.so.8`, why the Flatpak shipped it, `build_flatpak.py:56`),
  libtiff6, libwebp7, libwebpmux3, libopenjp2-7, libgif7, libicu74-ish via libxml2, libssl3 ... let `dpkg-shlibdeps` resolve the exact list on a 24.04 build (the build machine is Pop 24.04, `ENGINE.json` pins "Ubuntu 24.04").
  `Recommends: xdg-desktop-portal, xdg-desktop-portal-cosmic | xdg-desktop-portal-gtk, gnome-keyring | libsecret-1-0`. Fonts: UNVERIFIED (check in the clean test that text renders; add `Recommends: fonts-noto-core` if not).
- OCR library caveat: the app pins `libtesseract.so.5` by SHA (`text.rs:9-10, 207-208`), so keep the bundled copy rather than Ubuntu's (a security update would break the pin). Its DT_NEEDED `liblept.so.5` is
  resolved today by `LD_LIBRARY_PATH` (`stack-env:10`). Fix in code: dlopen the bundled liblept by absolute path first (glibc then reuses it by soname). UNVERIFIED; proven or disproven in step 3.
- Packaging notes: the existing `install_desktop.py`, `package_desktop.py`, `package_flatpak.py`, the `stack`/`stack-cli`/`stack-env` wrappers become dead code once the deb ships (delete in step 10; `package_desktop.py:51-58` wrapper env is what moves into the binary).

## 2. Install and update

Option (a): signed apt repository on GitHub Pages. Option (b): in-app update like Ami Pro (`updates.rs`: GitHub latest release, SHA256SUMS from the same release, `pkexec apt-get install -y file`).

| | (a) apt repo | (b) in-app |
|---|---|---|
| New code in the app | none | about 600 lines (Ami Pro's updates.rs is the model) plus tests |
| Trust | GPG-signed Release; apt verifies | checksum from the same GitHub release (integrity, not authenticity) |
| User sees updates | wherever Pop!_OS shows apt updates | only after pressing Check for Updates |
| Needs new infra | signing key, Pages path, Release/Packages generation | GitHub release per version |
| Update with no terminal | yes if the system updater lists third-party apt updates (UNVERIFIED) | yes, always; pkexec prompt |

Recommendation: **(a)**, and put the apt files in a subfolder `apt/` of the EXISTING Pages repo `nchiker/stack-flatpak` (no new public repo is needed; agents may not create one, see handoff).
`publish_flatpak_repo.py` is the model. `apt-ftparchive` generates `Packages` and `Release`; `gpg --clearsign`/`--detach-sign` produces `InRelease`. Reuse the existing GPG signing key
(`~/APK-Keys/stack-flatpak/flatpak-signing-secret.asc`, fingerprint FF1ECD6A...; the publish script imports it into a throwaway keyring, `publish_flatpak_repo.py:3-9`; never print it).
Owner decision: reuse it, or create a separate `~/APK-Keys/stack-apt/` (cleaner; one more key to back up in Key Vault). Default: separate key.
The first install is one double-click: the main package itself ships `/etc/apt/sources.list.d/stack-docs.sources` (`Signed-By: /usr/share/keyrings/stack-docs-archive.gpg`,
`URIs: https://nchiker.github.io/stack-flatpak/apt/`) and the public keyring, exactly as Chrome/Signal do. The Flatpak's "setup in the product" rule is met: the package sets itself up; no script, no env var.
After that, `apt upgrade` (and the Pop updater) delivers new versions.

Fallback if step 11 shows COSMIC's updater does not list the repo's updates: add (b) later as a small "Check for updates" button reusing Ami Pro's logic, reading `nchiker/stack` releases (public repo; `gh repo view` says PUBLIC). Do not build (b) now.
Caveat on first install: `~/AmiPro-Linux/docs/INSTALL.md` still says `sudo apt install ./x.deb`. Whether a double-clicked deb installs from COSMIC's file manager/Store on this Pop release is UNVERIFIED (cosmic-store and pop-upgrade are installed here); test in step 11. If double-click does not work, the owner's one terminal command is `sudo apt install ./stack-docs_X_amd64.deb`, which is the only terminal step.

Brand-new computer, exactly: (1) download `stack-docs_X_amd64.deb` from the GitHub release page; (2) double-click it and press Install (or the one `apt install` line above if double-click fails); (3) open Stack from the launcher; (4) later, accept updates in the normal system updater. Nothing else (no key to import, no repo to add by hand).

## 3. Migration of the owner's existing data

Data is already in host folders (section 0), so the deb app opens the existing library in place. Nothing is moved or copied; the Flatpak's own folders stay untouched.
Procedure the app itself follows on first run of the deb build (put in `stack-desktop` startup, plain messages):
1. Open library at `default_root()`; `writer.lock` (`library.rs:~107-112`) already refuses a second writer with `Error::Busy`. If the Flatpak is still running the deb shows "Stack is already open (close the other copy)"; verify this text exists, else add it.
2. Before the first open by a new version, run `PRAGMA quick_check` read-only and refuse to proceed (message, no write) if not "ok". Existing schema migration (`schema::migrate`, `library.rs:~122`) runs as today; the deb 0.6.0 adds no schema change.
3. Safety copy: reuse the sync "safety copy" mechanism (handoff 0.3.0 notes). Optional extra: a one-time marker `deb-first-run.json` and a reflink/regular copy of `library.sqlite3` only (not originals) if free space allows; skip silently otherwise. Decision for owner: yes/no (default yes, small).
4. Backup key: read via `SystemStore` as before. If none is found AND `~/.var/app/org.stackdocs.desktop/data/keyrings/default.keyring` exists with content (a 0.5.0 portal-file key, `keystore.rs:232-243`), show: "Your backup key was saved by the old Flatpak. Enter it again (it is in Key Vault)." The file is encrypted with a portal-provided password that a native app cannot obtain on COSMIC (no Secret portal backend, `keystore.rs:337-339`). This computer's keyring dir is empty, so only the partner's machine could be affected (UNVERIFIED).
5. Window state / theme: no stored window state found in the app's config (only `view.json`); COSMIC theme is read from `~/.config/cosmic` directly (the Flatpak granted it read-only, `.yml:19`). Nothing to carry over.

Failure modes: Flatpak and deb both installed give two launcher entries with the same id `org.stackdocs.desktop.desktop` (the system copy is shadowed by the Flatpak export in `~/.local/share/flatpak/exports`; harmless; remove the Flatpak after verifying). A newer deb with a schema bump makes the old Flatpak unable to open the library, so keep schema 7 until the Flatpak is gone. Secret Service locked -> existing "Locked" path (`keystore.rs:30-32`). Library untouched on every path above except the normal migrate.
Never print the key; the existing tests do not.

## 4. Code changes (files)

- `desktop/crates/stack-render/src/lib.rs:145` and `text.rs:22-30`: resolve PDFium/Tesseract/tessdata without env: env override first (kept for tests), else relative to `std::env::current_exe()`: `../lib/stack-docs/runtime/...`. Add the liblept preload in `text.rs` `Engine::new` before `Library::new`.
- `desktop/crates/stack-desktop/src/main.rs` and `stack-cli/src/main.rs`: set `OMP_THREAD_LIMIT=1` at the very start (edition 2024: `unsafe set_var` before any thread), replacing `stack-env:11`. `XDG_*` lines of `stack-env` need no replacement (the default `$HOME/.local/share` fallback in `library.rs:92-96` and `preferences.rs:13` is already right). `HOST_XDG_*` is only read in `keystore.rs:250`.
- `desktop/crates/stack-core/src/keystore.rs`: outside a Flatpak nothing changes (`with_system`, 549-): `Single(None)` path -> `oo7::Keyring::new()`. Keep the sandbox code (`sandbox_keyring_path`, `Chain`, portal file) until the Flatpak is retired, then delete it (lines ~200-260, 297-340, 520-560) and the `FLATPAK_ID` logic. Add the old-key message from section 3.4 (in stack-desktop, wherever key status is shown).
- `desktop/crates/stack-desktop/src/ami_open.rs`: `portal_open` (line ~110-132) becomes `std::process::Command::new("amipro-linux").arg(path)` (documented in `~/AmiPro-Linux/docs/INSTALL.md`: opens in the running window or starts one). Drop `open_fd`/`OpenError::Gone` handling for lost document-portal grants (keep "file gone" for a missing path). If `amipro-linux` is not on PATH: message "Ami Pro is not installed." Keep the choose-once step: it still teaches Stack the Filen folder path, and `link_local_files` (schema 7) stays; outside a Flatpak the chooser returns a real path (no document-portal path rewriting). Use `Command::spawn` with null stdio, no shell. Tests in `ami_open.rs` for `classify/open_fd` shrink accordingly.
- `desktop/packaging/`: new `stack-docs.sources`, postinst script text, a deb control template; metainfo release entry for 0.6.0; `.desktop` `Exec=stack-desktop`. Delete the `stack*` wrappers and `.yml` only in the retirement step. `desktop/packaging/README.md` rewritten.
- `desktop/tools/`: new `package_deb.py`, new `publish_apt_repo.py`; `run_all_checks.py` (below). Keep `build_flatpak.py`/`publish_flatpak_repo.py` until retirement.
- Secrets permission (`--talk-name=org.freedesktop.secrets`) is simply gone; a native app talks to the Secret Service directly.
- Update `CLAUDE.md`-style docs: handoff, `desktop/README.md`, `docs/desktop/CURRENT_RELEASE_HANDOFF.md`.

## 5. Tests and checks (without touching the real install)

What exists here (checked): `unshare -r` works (user namespaces; /etc/subuid has entries), `bwrap`, `fakeroot`, `dpkg-deb`, `dpkg-shlibdeps`, `apt-ftparchive`, `gpg`, `dbus-run-session`, `gnome-keyring-daemon`, `desktop-file-validate`, `appstreamcli`. NOT present: podman, docker, distrobox, systemd-nspawn, debootstrap, qemu, lxc. Nothing to install.

- Static: `dpkg-deb -I/-c` on the deb; `desktop-file-validate`; `appstreamcli validate` of the metainfo; `dpkg-shlibdeps` clean; Depends names exist in this host's `dpkg -l` (the host is 24.04, so it is the reference system).
- No-root "install": `dpkg-deb -x pkg.deb $T/root`; run `$T/root/usr/bin/stack-cli` with `HOME=$T/home XDG_*` under `$T`. Because the binary finds runtime files relative to itself, this tests the relocatable lookup. It does not run postinst.
- Isolation recipe (real, per run): `env -i HOME=$T/home XDG_DATA_HOME=$T/home/.local/share XDG_CONFIG_HOME=$T/home/.config dbus-run-session -- ...` so the app talks to a PRIVATE session bus. Start `gnome-keyring-daemon --unlock` inside it (empty password) as a fake Secret Service; the real keyring is never reachable. Without a private bus the test would touch the owner's real Secret Service (attribute `application=org.stackdocs.desktop`): forbidden; use a different test attribute or the private bus only.
- apt path without root: build a local repo in `$T/repo` (apt-ftparchive + gpg with a throwaway key), then `apt-get -o Dir::Etc::sourcelist=$T/s.list -o Dir::Etc::sourceparts=- -o Dir::State=$T/state -o Dir::Cache=$T/cache -o Dir::State::status=$T/status -o Debug::NoLocking=1 -s install stack-docs` simulates resolution and signature checking. An actual dpkg install into a scratch root needs root (`unshare -r dpkg --root=$T/r --force-not-root --force-script-chrootless -i`: plausible, UNVERIFIED; try in step 6).
- Synthetic library: build with the existing fixtures/CLI (`stack-cli`), never the owner's folder. Migration test: create a library with the Flatpak build (test id `org.stackdocs.desktop.test`, as `run_all_checks.py:134`) or with `stack-cli` into `$T/home/.local/share/org.stackdocs.desktop`, open with the deb binary, compare `sha256sum` of `originals/` and a `quick_check`; run again; confirm Flatpak copy still opens it. Update test: install version N, then N+1 from the local repo (needs root for a true install; otherwise the simulation above). Removal test: `dpkg -r` leaves `~/.local/share/org.stackdocs.desktop` (assert with a synthetic home).
- Real final proof (owner's computer, needs the owner): see step 11.
- `run_all_checks.py`: keep the unit tests/interop; replace `flatpak_key_store` (lines 141-262) with `deb_key_store` that runs the unpacked deb's `stack-cli key save/status/remove` and the `archive ... --saved-key` calls inside the private bus + gnome-keyring; add `deb_package_checks` (control, Depends, desktop/metainfo validators, runtime SHA check against `ENGINE.json`, OCR on a sample PDF with the unpacked binary and NO `STACK_*` env).

## 6. Risks, work plan, owner decisions, Flatpak retirement

Risks, most serious first:
1. COSMIC/Pop updater may not offer the apt repo's updates, or double-click install may not work (both UNVERIFIED). Mitigation: step 11 test; fallback (b).
2. Env-free runtime lookup breaks OCR/PDF (liblept preload, relative path) = silent loss of text search and previews. Mitigation: tested with `env -i` in step 3, pinned SHA checks stay.
3. A bad package or bad migrate touching the real library. Mitigation: nothing is migrated; quick_check and a small safety copy; never test against the real folders.
4. Lost signing key = users stuck. Mitigation: Key Vault backup (owner clicks Back up now); never print it.
5. Partner's computer may hold a 0.5.0 portal-file key that cannot be read (re-enter from Key Vault).
6. Depends wrong on the partner's machine (unknown Pop point release). Mitigation: shlibdeps; one clean test.
7. Same GitHub Pages repo grows (limit 1 GB, files under 100 MB, `publish_flatpak_repo.py:41`): keep 3 deb versions.

Ordered work plan (each step small; steps 1-9 suitable for a cheap agent; reviews by a higher-cost agent only after approval, per CLAUDE.md):
1. Release build of the three binaries. Done: `target/release/stack-desktop --version`-style start; sizes recorded.
2. Runtime lookup relative to `current_exe` plus env override (stack-render). Done: new unit tests; app starts with `env -i`.
3. liblept preload and `OMP_THREAD_LIMIT` in main. Done: OCR test on a sample PDF with no `STACK_*`/`LD_LIBRARY_PATH`.
4. `package_deb.py` producing the .deb with metainfo/desktop/icon/notices/sources/keyring. Done: `dpkg-deb -I`, validators clean, Depends listed.
5. Extraction smoke: unpack, run CLI and window capture (existing capture tool) in a private HOME and private bus.
6. `publish_apt_repo.py` building the repo in a temp folder with a THROWAWAY key (no push, no real key). Done: `apt-get -s install` against it is signed-ok.
7. `ami_open.rs` direct launch plus tests. Done: unit tests; a fake `amipro-linux` script on a temp PATH receives the file path.
8. Key store test with private bus (`deb_key_store`); also check the attribute/collection match the Flatpak's item using a throwaway item written by the Flatpak test build. Done: save/status/remove OK.
9. Migration checks (synthetic library, Flatpak test id vs deb binary), schema stays 7, update `run_all_checks.py`. Done: all green.
10. Docs and handoff; `packaging/README.md`; version bump 0.6.0 (this is the first deb) and metainfo release.
11. Owner step: with explicit go-ahead, publish to `nchiker/stack-flatpak/apt/` with the real key; on this computer double-click the deb (Flatpak still installed); verify library opens, key shows saved, sync, Open in Ami Pro; later publish 0.6.1 and confirm the system updater offers it. Then the partner's computer.
12. Flatpak retirement: keep the Flatpak repo published and untouched until both computers run the deb and have been updated once through apt. Then the owner runs `flatpak uninstall org.stackdocs.desktop` (leaves `~/.local/share/org.stackdocs.desktop`), then delete wrappers/yml/flatpak tools/sandbox keystore code in a final commit, and only then archive the Flatpak site (the owner decides; do not delete it automatically).

Owner must decide or do by hand: (1) approve key choice (reuse vs new `stack-apt` key); (2) approve publishing under `nchiker/stack-flatpak/apt/` (or create a new Pages repo; agents cannot); (3) package name `stack-docs` and first version 0.6.0; (4) whether the small pre-migration safety copy is wanted; (5) the test double-click install and the first update on the real machine; (6) partner's computer: confirm Pop point release and whether a 0.5.0 key file exists; (7) back up the signing key via Key Vault.
