# Next session — start the Rust COSMIC desktop app

The user requested this desktop work order after Android closeout. The full
[desktop implementation prompt](STACK_DESKTOP_NEXT_SESSION_PROMPT.md) is ready;
creating it did not implement or test any desktop feature.

Paste this into the next session:

```text
Begin the native Rust COSMIC desktop version of Stack in
/home/charlie.day/Projects/Stack. Read and execute
docs/STACK_DESKTOP_NEXT_SESSION_PROMPT.md in full as the active work order.
It supersedes the old no-desktop restriction for this new scope. Begin actual
implementation, not planning only, under Stack/desktop/, using current official
libcosmic guidance and pinned dependencies. Build a persistent integrated desktop
library with safe phone-compatible transfer and a sync-ready data model. The
intended end state is opt-in bidirectional phone/desktop synchronization, usable
while each device is offline; backup re-import alone is not sync.

Confirm current state first. Android closeout baseline is0.8.7-dev/code20/schema8/
archive3, implementationb5cc48e plus subsequent evidence/documentation commits.
Read the desktop work order's Android evidence/archive/handoff references and
preserve the real phone library, original signing identity and all current lock/
consent choices. Never use private Google/Stack archives as regression fixtures.
Use synthetic desktop roots and org.stackdocs.app.checks for mutations and sync.
Keep user reports, source inspection, generated fixtures and actual runtime results
separate. Preserve unrelated OCR-row formatting and cosmic-takeout.

Progress continuously through the first runnable desktop milestone, author and run
meaningful interoperability/recovery/sync-conflict fixtures, commit coherent Stack-only
increments and update the ledger across compactions. Complete independent work
before grouping all remaining user/hardware checks into one final checklist.
Document exactly what transfer, synthetic sync and real phone integration work;
never claim automatic bidirectional sync before both sides actually support it.
```

Android delivery/installation remains in [the handoff](STACK_SESSION_HANDOFF.md),
[actual evidence](STACK_UNLOCK_AND_LIBRARY_TOOLS.md) and
[primary-phone instructions](STACK_PRIMARY_PHONE_INSTALL.md). Its earlier H1–H8
session is completed,user-reported. New direct native authentication and primary-phone
install/restore remain focused user actions; do not restart the full Android session.
The desktop work order carries the canonical data/signing protections forward.
