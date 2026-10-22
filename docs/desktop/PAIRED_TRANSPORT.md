# Paired LAN transport, version 1

The application uses **Settings → Sync → Connect phone**, followed by phone
approval. Both library merges and subsequent bidirectional exchanges use the
existing engine and package protocol. No alternative wire protocol is introduced.

## Approval and initial merge

Clicking Connect phone authorizes the desktop side of the existing commit/reveal
transcript. The phone displays the authenticated computer's name and a comparison
code and requires **Approve connection**. The original transcript commitments,
mutual TLS, exact certificate pins and device-local keys remain enforced. A
same-identity reconnection after Disconnect requires fresh authenticated approval;
normal exchange with a revoked credential is rejected.

The existing baseline review and merge execute internally after approval. Shared
backup IDs are matched. Equal stack and tag names are mapped to a single catalog
entry, membership is combined, and independent documents remain separate. Files
and validation retain existing collision checks. Concurrent nonconflicting
operations merge in the existing causal engine. Genuine conflicts keep their
alternatives until a user chooses the readable phone or desktop version.

## Automatic exchange and retries

Desktop keeps a nonblocking TCP45873 receiver and UDP45874 discovery responder
while connected. Idle listening never occupies the repository writer. Accepted
connections use the existing authenticated exchange on the repository worker.
Discovery after pairing is only an address hint; exact TLS pins remain mandatory.

Android uses the existing serial IO executor for exchange, debounces local changes,
checks about every15seconds in the foreground and schedules persisted network
jobs with Android's minimum15minute periodic interval. Android may defer those
jobs. Pairing preferences retain the verified computer and its last address.
Address failure triggers existing discovery before retrying the pinned peer.
Leaving the Sync screen does not cancel ordinary exchange. Disconnect cancels
current work, revokes the local policy and stops scheduling.

Initial invitation and address discovery reuse45874 and the existing nonce-bound
response format. Phone approval and library exchange keep the original bounded
I/O, cancellation and atomic publication behavior. Authenticated processing allows
180seconds; pre-authentication and TLS deadlines remain separately bounded.
The user-facing screens contain no adoption, receipts, session, direction or
conflict-strategy controls. Internal command-line tools remain available for
protocol testing and recovery.

Each device independently generates a P-256 certificate. Desktop certificates have
DNS SAN`stackdocs.local`, server/client usage and a PKCS#8 private key in the
library's mode0700`pairing` directory with mode0600 files. Android uses Keystore
for its private key and private app files for peer policy. Credentials never enter
archive format3 or sync bundles. Existing complete local identities are reused;
a partial on-disk identity fails rather than overwriting either half.

Advanced public-certificate enrollment remains available through the CLI and requires full SHA-256 fingerprint comparison through a trusted
channel. TLS validates the certificate chain and exact enrolled DER leaf pin;
Android verifies the desktop's`stackdocs.local` SAN. Revocation is rechecked before
each send and durable apply. Unknown/revoked peers are refused before library data.
Replacing a member requires a fresh reviewed baseline, with no dynamic enrollment.

The explicit foreground`listen-once ROOT ADDRESS:PORT` command accepts one session
and times out after45 seconds. `sync-now ROOT ADDRESS:PORT` initiates one session.
Default ordinary exchange port is45873. The application keeps a receiver while desktop is running and Android schedules
automatic attempts; these one-shot CLI tools retain their original bounded behavior.
Android target/compile SDK remain36.

The client sends a `STACKEX1` bundle, the server replies with one, and the
client sends a final receipt bundle. The unchanged repository import path
validates and durably applies each bundle before the receiver emits a JSON
receipt. A failed socket can be retried; replayed operation IDs do not apply
twice. An unresolved conflict or pending gap remains visible in repository
status rather than being reported as a complete merge.

Each frame is `STACKLAN1` (nine ASCII bytes), a kind byte, an unsigned
big-endian 64-bit payload length, then payload. `H` carries JSON
`{version,group,replica}`, `B` carries unchanged exchange bytes, and `R`
carries the durable import result. Control payloads are capped at 16 KiB;
bundles at the existing 8 GiB original-byte + 32 MiB metadata + 12-byte
envelope maximum. Both platforms stream bundle data through private temporary
files in 64 KiB or smaller chunks. TLS handshake timeout is eight seconds on
desktop; normal I/O timeout is 45 seconds. The package's test suite covers
authenticated localhost transfer and acknowledgment, wrong/unknown/revoked
peers, corruption, interruption/reconnect, replay, oversized frame headers,
declared-length truncation and bounded-buffer copying. Additional native-widget
coverage exercises fingerprint rejection, enrollment, canceled listening,
both endpoint roles, exact notes, draft preservation, original preservation and
revocation through the real background owner. Waiting/blocked-handshake cancellation
has a separate two-second completion test. These remain headless observations.
