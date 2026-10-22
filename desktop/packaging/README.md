# Stack Flatpak

The release Flatpak is built from source by
[`org.stackdocs.desktop.yml`](org.stackdocs.desktop.yml) inside the Freedesktop
25.08 SDK with its Rust extension (`org.freedesktop.Sdk.Extension.rust-stable`):

```sh
python3 desktop/tools/build_flatpak.py --output .artifacts/stack-release-YYYYMMDD/flatpak
```

The script vendors every Rust dependency into `SOURCE.tar.gz`, stages it with the
dependency notices, the pinned PDFium and OCR engines from `desktop/runtime/` and
the host's `libjpeg.so.8` (the OCR engine needs the jpeg8 ABI, which the runtime
lacks), then runs `flatpak-builder` without network access and writes
`Stack-VERSION-x86_64.flatpak` and `SHA256SUMS`. The version is the newest release
listed in `org.stackdocs.desktop.metainfo.xml`; add the new release there first.
It never installs the app or opens a library.

Install or update, then launch:

```sh
flatpak install --user --or-update -y /absolute/path/Stack-VERSION-x86_64.flatpak
flatpak run --user org.stackdocs.desktop
```

Keep only the `stable` branch installed so the launcher cannot start an older
build. The app uses the existing library in the host's
`~/.local/share/org.stackdocs.desktop`; removing the Flatpak does not remove it.
Permissions: display, GPU, local network sync, Stack's own data and preferences,
and read-only COSMIC settings. File import/export goes through the desktop portal.

`tools/package_desktop.py` still prepares a standalone (non-Flatpak) install from
locally built binaries and refuses binaries older than the source;
`tools/package_flatpak.py` can wrap that output, but the source build above is the
release path.
