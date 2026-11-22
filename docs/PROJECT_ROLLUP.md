# Project roll-up: Stack, Ami Pro, Filen (written 2026-11-22)

One page: what is done, what is left, and what can run at the same time. Details live in each project's own files (listed at the end).
This page is a snapshot from reading those files; check them before acting on any item.

## Stack Link: slice by slice
Source of truth: `STACK_LINK_CONTRACT.md` ("Work by project").

| Slice | Stack | Ami Pro (phone) | Filen Files | State |
|---|---|---|---|---|
| 1. Contract | done | done | none | done |
| 2. Read side (share, search, preview, Open) | done | done, released 0.4.0 | none | done |
| 3. Archive 4 and encryption | done (backups carry link records) | none | none | done |
| 4. Lifecycle (stat, refresh, removed, Recently deleted) | done | code written (0.5.0), checked against Stack's test build; not released | none | Stack side done; Ami Pro release pending the owner |
| 5. Filen locator and events | Stack side built | write `filen` locator when the file lives in Filen | `moved`/`removed`/`unshared` events, locator lookup by UUID | not started in Ami Pro / Filen (unverified, read the two STATUS files) |
| 6. Sync and desktop | link sync built, tested and announced (see below) | **Ami Pro Linux: accept a file argument and hand off to a running instance** | none | Stack side done; Ami Pro Linux part not done; Flatpak launch test (6b) not done |
| 7. Partner publish (Filen folder) | not started | none | folder change notice, `filen-in` open | not started |

## Stack slice 6 (link sync between phone and computer)
Done and pushed: design (rev 3), merge rules (Rust and Java, golden cases), phone capture and apply, computer apply, hello handshake,
conflict resolution and per-link tie review (P2), cleanup of retained files (D6), tests (kill tests T1, phone checks in the one-button
runner T2, real network run T3), docs, announcing switched on (D4b, see `STACK_LINK_SLICE6_HANDOFF.md` for the final state).
Opus reviews done for D5, P1, P2, D6 and the `stack-cli key` commands.

Left in Stack:
1. Fix the `stack-cli key` review findings: Flatpak keyring location (app and CLI may use different files), a time limit on the secret store (hangs without a portal), `--replace` can lose the old key, small message and exit-code items.
2. Hand checks only the owner can do: save a key from the window on Flatpak/COSMIC; Key Vault menus, tooltips and QR button.
3. Not yet tested: tie review over the network, 1000 links, release-build timing, phone kill tests.
4. Release of the desktop export (only on the owner's explicit go-ahead).

## Ami Pro (phone app, `~/Projects/AmiPro-APK`)
Done: open, edit, format, save SAM/native, New, Find, Print, update check, Stack link share/search/preview/Open (0.4.0), slice 4 code.
Left: release the slice 4 build (needs the owner's go-ahead and a signed release); slice 5 `filen` locator; `moved`/`trashed` events; share from recent-file rows; unchecked soft-keyboard cases and very large documents.

## Ami Pro Linux (`~/AmiPro-Linux`)
Version 0.1.1 is out. For slice 6: take a file argument on launch and hand it to a running instance (Stack's "Open in Ami Pro" on the computer needs it).

## Filen Files (`~/Projects/Filen`)
Own roadmap in `docs/ROADMAP.md` (offline pinning, media viewers, search filters, public links, quota bar, cleanup). For Stack: slice 5 events and locator lookup, slice 7 folder notice and `filen-in` open. Filen already provides the backup-folder grant that Stack depends on (do not rename its action or extras without telling Stack).

## What can run at the same time
Right now only Stack is running.

Can run alongside Stack, in parallel, because the work is independent (separate folders and toolchains: each project has its own JDK/SDK/Rust environment):
- **Ami Pro Linux file-argument work** (slice 6): no phone, no Stack files touched. Safest first choice.
- **Filen roadmap items** with no phone need (search filters, quota bar, viewers, cleanup): host builds and unit work only.
- **Ami Pro slice 4 release prep**: build and docs; the owner approves the release.

Needs the test phone (`0A121JEC203530`): only ONE of these at a time, never two device jobs together: Stack's one-button runner or hand runs, Ami Pro slice 4 end-to-end check, Filen device runs. The production phone `5A101JEA313018` stays off limits for all.

Should wait for Stack:
- Ami Pro / Filen **slice 5** (locator and events) wants Stack's slice 5 behaviour confirmed on the test phone first.
- **Slice 7** (partner publish) needs encryption (done) and an owner decision to start; it touches Stack and Filen together, so run it alone.

Do not run two agents on the same project's build output (`target/`, `app/build/`) at once, and do not run two Rust builds on one machine with the same cargo home at the same time.

## Files to read
- Stack: `docs/STACK_LINK_CONTRACT.md`, `docs/STACK_LINK_SLICE6_HANDOFF.md`, `docs/STACK_LINK_SLICE6_DESIGN.md`
- Ami Pro: `~/Projects/AmiPro-APK/docs/STATUS.md`, `docs/STACK_LINK_HANDOFF.md`
- Filen: `~/Projects/Filen/docs/STATUS.md`, `docs/ROADMAP.md`
- Ami Pro Linux: `~/AmiPro-Linux/ARCHITECTURE.md`, `CLAUDE.md`
