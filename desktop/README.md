# Stack for COSMIC (desktop)

Rust and libcosmic, Linux x86-64, shipped as a Flatpak (`org.stackdocs.desktop`).
It keeps a persistent document library with previews, offline text search, stacks,
editors, portable backups, and two-way sync with the Android app.

Crates: `stack-core` (library and sync storage), `stack-sync` (causal protocol),
`stack-archive` (backup format), `stack-render` (PDF/image previews),
`stack-transport` (paired LAN sync), `stack-desktop` (the app) and `stack-cli`
(scripting and recovery). Storage layout is described in [STORAGE.md](STORAGE.md).

## Sync

Open **Settings → Sync → Connect phone**, then approve on the phone. After that the
phone starts every sync; the computer keeps a receiver open on TCP 45873 (and
answers discovery on UDP 45874) while the app runs. See the
[user guide](../docs/STACK_DESKTOP_PHONE_TRANSFER.md) and the
[transport specification](../docs/desktop/PAIRED_TRANSPORT.md).

## Build and test

Rust 1.99 for development (`rust-toolchain.toml`; the code also builds with 1.98, which the Flatpak SDK provides), a C/C++ toolchain, `pkg-config` and the
Wayland/xkbcommon development libraries. PDF previews and OCR use pinned engines
under `runtime/` (`tools/setup_pdfium.py`, `tools/setup_ocr.py`; both refuse to
overwrite and check SHA-256).

```sh
cd desktop
cargo build --workspace --locked --offline --release
cargo test --workspace --locked --offline
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
```

Tests that need PDFium find it under `runtime/` unless `STACK_PDFIUM_LIBRARY` is
set; they pass in a normal desktop session. To run a binary directly, set
`STACK_PDFIUM_LIBRARY`, `STACK_TESSERACT_LIBRARY` and `STACK_TESSDATA_DIR` to the
files under `runtime/` (see `packaging/stack`).

`bash tools/run-demo.sh` opens the app on synthetic samples in a throwaway
directory. Without `--demo` the app opens the standard library in
`~/.local/share/org.stackdocs.desktop`; `--library ROOT` chooses another.

## Command line

`stack-cli` works on an explicit library directory and never opens personal data
implicitly. Stop the app first (`flatpak kill org.stackdocs.desktop`); the library
has a single writer.

```sh
stack-cli library ROOT status|manifest|search TEXT|rename ID TITLE|notes ID TEXT|delete ID
stack-cli library ROOT stack-create NAME ARGB show|hide|stack-rename ID NAME|stack-delete ID
stack-cli archive ROOT review|restore|export FILE.stackbackup
stack-cli sync ROOT status
```

## Packaging

See [packaging/README.md](packaging/README.md).
