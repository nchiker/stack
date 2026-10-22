# Sync your phone and computer

1. On the computer, open **Settings → Sync** and select **Connect phone**.
2. Keep Stack open on the unlocked phone, on the same Wi-Fi network.
3. On the phone, select **Approve connection** for your computer.

Stack combines the existing libraries and automatically synchronizes documents,
saved edits and stacks in both directions. Existing documents are kept. When both
devices were restored from the same backup, their shared document identities are
matched; unrelated documents with similar titles remain separate. Matching stack
names are combined without generating duplicate starter stacks. On startup, Stack
also repairs verified old generated duplicates when their starter copy is unused,
keeping the populated stack and all its document memberships.

The Sync page shows the connected device, connection status, last sync, **Sync
now** and **Disconnect**. There is no separate library adoption, direction choice,
file exchange or transport configuration screen. A genuine conflicting edit shows
the phone and desktop versions with **Keep phone version** and **Keep desktop
version** choices. Other documents continue synchronizing while you decide.

Keep the desktop app running to receive changes. The phone checks automatically
while Stack is open and schedules background work when Android permits it.
Android may defer background work to save battery. Saved phone changes request
sync automatically. **Sync now** on the phone requests an immediate attempt; on
desktop it makes the receiver ready for the phone's next automatic connection.
Leaving Settings does not cancel everyday synchronization.

If either device goes offline, documents remain available and Stack retries.
Changing Wi-Fi addresses does not require another pairing. The verified device
identity is retained across restarts. Both devices must be on a reachable local
network; a network that blocks communication between devices prevents sync.
**Disconnect** stops future synchronization and keeps documents on both devices.
Reconnecting requires fresh approval on the phone.

The connection uses the existing mutually authenticated, certificate-pinned TLS
transport. Device names and discovery responses do not authorize a connection.
Private keys stay on their devices and are not included in backups.

## Backup, restore and reset

Backup and restore remain in **Backup & restore**. Reset is a deliberate recovery
action with local confirmation, not a normal synchronization step. Never erase
both devices unless you have checked an independent backup. A backup imported
into a populated library adds copies; restoring the same backup into empty
libraries preserves the identities that the initial connection can match.
Resetting an identity requires a fresh connection and approval. A reset on one
device never remotely erases the other device.

## Implementation and validation

See [automatic sync validation](desktop/AUTOMATIC_SYNC_20261021.md) for the tested
implementation, artifacts and qualified runtime results.
