# Private phone and desktop source backup

The private repository is https://github.com/nchiker/stack. Its `main` branch is a
Stack-only source checkpoint, including unfinished Android and desktop work. It is
not a release and does not contain the user's document library, signing keys,
credentials, build outputs or downloaded native runtimes. The shared local parent
repository and its history remain unchanged; other projects are not uploaded.

`STACK_SOURCE_BACKUP.json` in the remote checkpoint records the local base commit,
per-file SHA-256 hashes and the unfinished-work qualification. The checkpoint
history begins at the initial backup; earlier local development history remains
in the original parent repository.

To update the backup from this workspace, use a separate owned checkout:

```sh
python3 tools/backup_github.py --mirror /tmp/stack-source-backup-20261019a --push
```

The tool collects tracked and nonignored Stack source, rejects credentials and
library files, verifies the destination is private, and commits/pushes only its
separate checkout. It never stages the shared working repository. Reuse the same
mirror to retain checkpoint history. If that temporary mirror is lost, clone the
backup first, then pass its path:

```sh
gh repo clone nchiker/stack /tmp/stack-source-backup
python3 tools/backup_github.py --mirror /tmp/stack-source-backup --push
```

To recover source into a fresh directory:

```sh
gh repo clone nchiker/stack Stack-recovered
```

Follow the project build documentation to provision the pinned Android and desktop
tools/runtimes. Keep the existing signing identity separately; a source backup does
not replace secure signing-key custody or private document backups.
