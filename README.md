# Stack

An independent, open-source document library: an Android app and a Linux desktop
app (COSMIC) that keep the same documents in sync over the local network. Scan or
import documents, read their printed text offline, file them into stacks, search
and export them. No account or cloud service is involved.

Stack is not affiliated with Google and contains no code, assets or models from
Google's discontinued Stack app.

| | Source | Current release | Read first |
|---|---|---|---|
| Android | [`app/`](app/) | 0.10.0 (code 24) | [app/README.md](app/README.md) |
| Desktop | [`desktop/`](desktop/) | Flatpak 0.2.0 | [desktop/README.md](desktop/README.md) |

Releases with the APK and Flatpak are on
[GitHub Releases](https://github.com/nchiker/stack/releases).

## Documentation

- [Sync your phone and computer](docs/STACK_DESKTOP_PHONE_TRANSFER.md) — user guide.
- [Current state and handoff](docs/desktop/CURRENT_RELEASE_HANDOFF.md) — what is
  verified, device rules, toolchain paths, known issues.
- [Sync protocol](docs/desktop/INTEROP_SYNC_PROTOCOL_V1.md) and
  [LAN transport](docs/desktop/PAIRED_TRANSPORT.md).
- [Portable backup format](docs/STACK_ARCHIVE_FORMAT.md).
- [Android signing](docs/STACK_ANDROID_SIGNING.md) and
  [publishing source to GitHub](docs/STACK_SOURCE_BACKUP.md).

Earlier plans, playbooks and evidence logs were removed from the tree; they remain
in the git history.

## License

Apache-2.0 (see [LICENSE](LICENSE)). Bundled components keep their own notices.
