# Phone–desktop sync resumed and confirmed — 2026-10-21

The interrupted session was still fixing populated-library sync; it was not waiting
for user testing. Its last transfer had saved all 175 documents on desktop but had
not completed confirmation. The final defect was a 383,888,840-byte serialized
desktop sync cache: each field repeated the same library-adoption review.

The resumed work shares immutable alternative evidence in memory and persists
accepted/pending operations without repeating their derived registers. Loading
rebuilds those registers in causal order, including histories larger than one
transport batch. Legacy register caches are skipped during decoding and replaced
with the compact representation on the owned library connection. Complete operation
history, evidence, conflicts, pending gaps, frontier, projection and baseline remain
intact. Operations and the phone transport format are unchanged.

The user connected and unlocked the same authorized Pixel 4a, serial
`0A121JEC203530`, on ADB port 5693. Using the actual desktop **Sync with phone →
Ready to sync** control and phone **Backup & restore → Sync with a computer →
Sync now**, the phone confirmed:

> Sync confirmed by your computer. 0 changes received.

The phone persisted **Last confirmed sync: Oct 21, 2026 5:04:39 PM**. Desktop
status subsequently reported adopted=true, three retained operations, zero
pending gaps, zero conflicts, valid projection and one acknowledged peer. No
reset, re-enrollment, uninstall, second backup restore or fixture injection was
used. This resumed an already published transfer and completed its acknowledgment;
it does not claim another fresh initial transfer from an empty library.

Both libraries retain **175 documents, 278 pages, 25 stacks and 248 memberships**.
All five phone preservation hashes match the pre-transfer fingerprint. Desktop
document/object/original aggregates match exactly; all 408 original files remain.
History, pending operations, frontier, projection and baseline hashes also match
across compaction. The desktop cache is now **2,154,890 bytes**, down from
383,888,840 bytes. SQLite may retain freed pages; this is the serialized cache size,
not a claim that the database file was vacuumed or physically shrunk.

The transfer used the rebuilt compact-state implementation. The subsequent
maintenance addition also compacts an unchanged legacy cache during status/replay;
it was checked against the actual desktop library using read-only status after
closing only the agent-launched Stack process. No transport/API call substituted
for the two interface actions that started the successful sync.

The agent temporarily set the desktop accessibility flags to operate Stack's
native exported button actions. The user objected because this enabled the screen
reader setting. Both flags were immediately restored to their original false
values. The saved GNOME screen-reader setting is false and no Orca process remains.
Do not enable screen-reader/accessibility settings again for automation. Phone
interaction uses the current Stack hierarchy and taps without changing timeout or
lock settings.

Verification: **49 affected Rust tests pass** (12 core unit, 15 library integration,
11 SQLite/exchange sync, 9 causal/graph/bundle unit, 2 Java/Rust golden-vector tests),
format checking, strict all-target Clippy for stack-core/stack-sync and the offline
workspace build pass. The new compaction regression initially compared a fresh
manifest's export timestamp; it passes after excluding that generated timestamp
from the saved-data comparison. The failed report is retained. Android code was
not changed in this resumed session; its previously installed fixes were used.

The updated runnable desktop package is
`.artifacts/stack-sync-resumed-20261021/stack`. Source and dependency notices come
from the previous verified offline source delivery. Packaging now respects Cargo
source replacement configuration, allowing an offline rebuild from that delivery.
Missing standalone vendored notice files were recovered byte-for-byte from its
audited notices. Sanitized verification is retained in
`.artifacts/stack-sync-resumed-20261021-evidence`; no personal document text,
originals, phone database or private keys are bundled there.

No further user test is required to establish this successful sync. Physical QR
camera acquisition and broader native focus/screen-reader/compositor observations
remain their separately qualified review items, not blockers for this result.
