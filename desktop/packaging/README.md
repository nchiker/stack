# Stack Flatpak

Build an installable x86-64 bundle from a source-matched desktop delivery:

```sh
python3 desktop/tools/package_desktop.py --profile release \
  --output .artifacts/stack-release-YYYYMMDD/desktop
python3 desktop/tools/package_flatpak.py \
  --package .artifacts/stack-release-YYYYMMDD/desktop \
  --output .artifacts/stack-release-YYYYMMDD/flatpak --version 0.1.0 --branch stable
```

The build uses the already installed Freedesktop Platform and SDK 25.08. It
checks the prepared payload and current desktop source hashes before exporting.
It never installs the app, downloads a runtime, or opens a document library.
The output includes a `.flatpak`, `SHA256SUMS`, and a `DELIVERY.json` receipt.

Install or update the supplied bundle, then launch Stack:

```sh
flatpak install --user --or-update /absolute/path/Stack-0.1.0-x86_64.flatpak
flatpak run --user org.stackdocs.desktop
```

Flatpak may offer to install its Freedesktop runtime when needed. Close the
standalone Stack app before opening the Flatpak: both use the existing library
at the host's XDG data directory under `org.stackdocs.desktop`. The repository
lock prevents two app instances from writing that library at once. No data copy,
reset, restore, or library migration is needed. Existing preferences and COSMIC
theme settings are reused.

Permissions provide the display, graphics, local network sync, Stack's own data
and preferences, and read-only COSMIC configuration. File import/export uses the
desktop portal. The package does not grant all of the home directory. Removing
the application does not remove the existing desktop library.

The pinned PDF/OCR engines and their notices are included. Ubuntu's
`libjpeg-turbo8` is bundled with its copyright notice because the prepared OCR
engine requires `libjpeg.so.8`, which the chosen runtime does not provide.
Other native dependencies resolve against the runtime. Building from different
engine binaries or choosing a different runtime requires repeating dependency,
rendering, OCR, and native startup checks.

Release 0.1.0 uses the `stable` branch. Keep only one installed branch so the
launcher cannot start an older build (`flatpak uninstall --user org.stackdocs.desktop//candidate`). `appstreamcli validate`
currently reports a missing homepage URL; the project has no configured public
homepage. Desktop entry validation and Flatpak's icon/export validation pass.
