# Stack foreground LAN transport (version 1)

This package carries existing `STACKEX1` exchange bundles over one mutually
authenticated TLS connection. It exposes a standalone `stack-transport` command;
the desktop's `Library::sync_export` and `Library::sync_import` remain the sole
bundle writer and durable apply path. A listener starts only for an explicit
`listen-once` call and exits after one connection or 45 seconds. There is no
automatic discovery or background service. Choose a manual IP address and port.
The native desktop exposes the same flow in **Sync exchange → Paired local
connection**, using its existing repository owner. Close the native app before
using the standalone command on the same library.

This initial transport supports exactly two frozen group members. Both replicas
must be recorded by `sync start` or explicit adoption. Third-peer forwarding is
rejected until the protocol can authenticate the original writer independently
of the forwarding peer. LAN transport does not modify archive format 3, replace
folder exchange, or sync local credentials, lock policy, consent, paths or drafts.

## Enrollment

Create an identity for each desktop library with `stack-transport identity-init
ROOT`. It generates a P-256 X.509 end-entity certificate and PKCS#8 private key
using the installed OpenSSL command. Both DER files live in `ROOT/pairing`, a
mode-0700 directory; credentials and policy are mode 0600. The Android client
uses a nonexportable Android Keystore P-256 key and private application policy.
These files are never included in Stack backups. Preserve your own recovery
records separately; a restored library cannot silently rejoin an old group.

Use `identity-show ROOT` to display the local SHA-256 certificate fingerprint and
certificate path. Exchange **public** DER certificates through a chosen channel.
Read the 64-character fingerprint from each device and compare it on a separate
trusted channel before enrollment. Then run:

```
stack-transport peer-enroll ROOT GROUP_UUID PEER_REPLICA_UUID PEER_CERT_DER VERIFIED_SHA256
```

`peer-enroll` rejects a mismatch, own identity, duplicate enrollment, and a
group inconsistent with an adopted library. Revocation is immediate with
`peer-revoke ROOT`; the local policy then rejects both inbound and outbound LAN
sessions. Active sessions re-read policy before each bundle send and durable
apply, and abort when enrollment changes or the peer is revoked. Re-pairing
requires an explicit fresh group/identity flow. Revocation
does not erase the immutable sync history or authorize reuse of a revoked replica.
Group membership in this version is frozen, so a lost-device replacement needs a
new reviewed baseline.

## Foreground operation

The receiving desktop chooses a listen address and runs
`stack-transport listen-once ROOT 0.0.0.0:PORT`. The initiating device chooses
that device's LAN address and runs `stack-transport sync-now ROOT ADDRESS:PORT`.
Both devices must first start or adopt the complete two-member group through
the reviewed file exchange flow. Certificate enrollment can happen earlier,
but it never adopts library content. `listen-once` rejects an unadopted
library before binding a socket. A nonempty destination needs the separate
reviewed mapping flow from the sync engine.

A session sends client bundle, server bundle, then client receipt bundle. Each
bundle receives a JSON receipt only after `sync_import` finishes its durable
apply. The third bundle durably records the server's acknowledgment of its own
outbound bundle. The JSON result includes the repository's actual frontier,
pending gaps, conflicts and `acknowledged_by` state. If a socket fails, restart
`listen-once` and run `sync-now` again; operation replay remains idempotent.

## Wire and security limits

TLS uses rustls 0.23.46 with ring 0.17.14 on desktop and Android platform JSSE
on the phone. A chain must validate against the enrolled certificate as its
trust anchor; after the handshake, the leaf DER SHA-256 must exactly match the
reviewed fingerprint. The desktop certificate has DNS SAN `stackdocs.local`;
the Android client uses that TLS name even when a manual IP address supplies the
socket destination. Unknown and revoked peers cannot receive library data.
TLS 1.2/1.3 are accepted; downgrade to plaintext is unavailable.

The byte frame is nine ASCII bytes `STACKLAN1`, one kind byte, and an unsigned
64-bit big-endian payload length. `H` is JSON `{version,group,replica}` and is
exchanged after mTLS authentication. `B` is an unchanged `STACKEX1` bundle.
`R` is the durable `sync_import` result JSON. Control payloads are capped at
16 KiB. A bundle is capped at the `STACKEX1` maximum: 8 GiB of originals,
32 MiB of metadata, and its 12-byte envelope. Bundle frames stream through
private temporary files in chunks of at most 64 KiB. Handshake timeout is
8 seconds, normal I/O and one-shot listener timeout 45 seconds. Connection
establishment is also bounded to eight seconds. Native **Cancel connection**
interrupts accept/TLS I/O and propagates cancellation into bundle publication and
apply; decisions already committed remain saved. Repository operations can report
busy until the session finishes. No listener starts automatically.
The underlying bundle enforces its own metadata, operation, blob and reference
limits before applying anything.

The checked behavior is headless localhost Rust endpoints for authenticated
apply/acknowledgment/replay, unknown and revoked peers, wrong fingerprint,
interrupted connection, corrupt bundle rejection, and clean retry. Android
platform runtime pairing and cross-platform transfer require separate device
evidence; Java compilation alone is not that evidence.
