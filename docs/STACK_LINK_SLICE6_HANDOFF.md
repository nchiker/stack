# Stack slice 6 (link-record sync): handoff (2026-11-22)

Start with: "Read docs/STACK_LINK_SLICE6_HANDOFF.md and continue slice 6." Rules: `CLAUDE.md` (setup lives in the product; cheap agents for routine work;
suggest and wait for approval for higher-cost agents; never print/log/store keys; publish only on the user's explicit go-ahead). Keep updates to the user short:
they asked for fewer details and plain asks.

## Where it stands
- Spec: `docs/STACK_LINK_SLICE6_DESIGN.md` rev 3 (two Opus reviews done; sections 15/16 hold the D2/D3/D4a notes). The user accepted all decisions in it.
- Committed on main: design note (1d4e66c), D1 golden cases + D2 Rust link rules + D3 Java link rules (52aca7d), one-button check launcher + `stack-cli key save/status/remove` (de54de8).
  Earlier this session: desktop export format 4 + encryption (ca26f16), CLI error text (1e9e2e1), interop test bed (782eec8, aa64d83), one-button check (17350e4).
  Source was pushed to GitHub through 56748de; later commits are NOT pushed (push only on the user's go-ahead: `python3 tools/backup_github.py --mirror ~/.cache/stack-source-backup --push --public`).
- Also committed since: D4a (Rust+Java hello gate), D5 (desktop apply), P1 (phone capture/apply) with their Opus-review fixes (f11398e, 1cd90de, fffb680), and the cross-kernel Java/Rust link tests plus a design-note wording fix (3f44829). Cross tests found no divergence. D4b done and committed (a0327b9): `ANNOUNCE_LINKS=true` on both sides, link sync is live.
  Unpushed: everything after 56748de.
- Phone run DONE (2026-11-22): newer TEST build installed on `0A121JEC203530` (no data reset needed); `-e linkSync true` passes all 12 groups, including SQLite triggers. Build needs `JAVA_HOME=~/.local/share/stack-jdk/jdk-25.0.5+8`, `--offline -PstackChecks -PstackChecksNamespace=linkcontract`, `assembleDebug assembleDebugAndroidTest`, `adb install -r -t` both APKs, then `am instrument -w -r -e linkSync true org.stackdocs.app.checks.linkcontract.test/org.stackdocs.app.ScannerChecks`. Android org.json escapes `/` as `\/`: normalize before substring checks. Not yet in the one-button runner.
- Order left (design section 11): D5 and P1 are done; remaining: P2 (auto-resolution and tie review per link), D6 (purge cleanup), T1 to T3 tests, docs, and D4b (flip `ANNOUNCE_LINKS`; DONE, see above).

## Approved by the user
- Opus read-only reviews of D5 and P1 (desktop apply code, phone apply code, hello gate, migrations, blob keeping). Run them when each item is done and report findings short, ranked.
- Known loose end: Rust hello parsing is looser than Java for `features` (Java refuses duplicates, null, more than 16 entries). Align them in `stack-transport` once D5 is finished.

## Test tools (all dev-only, synthetic public test key; never the real key or keyring)
- One button: double-click `Run Stack checks.desktop` (opens cosmic-term; runs `desktop/tools/run_all_checks.py`, about 5 minutes: desktop tests, Java vs Rust, desktop interop,
  phone interop and the three phone suites on the test phone). Last result: 8 passed, 0 failed, 1 skipped (Flatpak key store: COSMIC has no Secret portal backend here).
- Test phone is `0A121JEC203530` ONLY (TEST build `org.stackdocs.app.checks.linkcontract`); the production phone `5A101JEA313018` is hands off. The harness never installs; installing the
  TEST build on the test phone was approved by the user (verify the package name first). The newest build installed at 2026-11-22 includes D3; P1 and D4a Java are NOT installed yet.
- Golden cases for graph rules: `desktop/protocol/golden-v1.json` `graph_cases` (113), run by `stack-sync/tests/golden.rs` and `desktop/tools/test_sync_java.py`.

## Open items for the user (ask briefly, one at a time)
1. Hand check: Flatpak key save from the window on COSMIC (the Secret portal may be missing; if the app hangs, propose a timeout and plain "no key store" message).
2. Opus review of the `stack-cli key` commands (they are committed without it).
3. Whether to push the unpushed commits, and whether to release the desktop export (a release is only on the user's explicit go-ahead).
4. Key Vault menus, tooltips and QR button are still unchecked by hand.

## Next steps (as of 3f44829 + phone run)
1. P2 (auto-resolution rule, per-link tie review, conflict-screen names), D6 (purge of unreferenced retained blobs after ack), T1-T3 (stack-core kill tests, add `linkSync` to the runner, real LAN run on test phone), docs (contract, INTEROP_SYNC_PROTOCOL_V1.md), align Rust hello `features` parsing with Java, then D4b last.
2. Still unverified: CursorWindow with large non-Latin text; first-capture time (up to 2 GiB) vs 180 s read timeout; process-kill boundaries; real LAN sessions.
3. Ask the user: push unpushed commits; Opus review of `stack-cli key`; hand checks (Flatpak key save on COSMIC, Key Vault menus/tooltips/QR).

## Update 2026-11-22 (late)
P2, D6 (with Opus review fixes), T1 (desktop SIGKILL tests), T2 (`phone-link-sync` in the one-button runner), docs (contract, interop protocol) are committed and pushed (GitHub 368dda9). One-button run: 9 passed, 0 failed, 1 skipped.
T3 (real LAN, test phone, announcing flipped in a throwaway build) PASSED: 50 links incl. 5 x 20 MiB previews first sync 42-49 s (limit 180 s), trash/delete/edit, conflicts resolved in the same session, old-protocol fallback, no-change sync clean. Not run: tie review over LAN, 1000 links, release timing, phone kill tests. The T3 test code was lost with the scratch patch and has been REBUILT in the repo, parameterised (see "Real LAN run" below; written without a device, not yet run on one). The flip is no longer needed: `ANNOUNCE_LINKS` is on.
Left: hand checks (Flatpak key save on COSMIC, Key Vault menus/QR), Opus review of `stack-cli key`.


## Real LAN run (`phone-link-lan`)
Run on the TEST phone `0A121JEC203530` only (the script refuses any other serial, above all the production phone). Needs the checks build (app + androidTest) already installed on it, as for the other phone steps; the script never installs, clears or opens an activity.

How to run (one command, nothing else to set up; the script finds this computer's LAN address itself):
- `python3 desktop/tools/test_android_link_lan.py --scenario all --build` (or `first_sync`, `tie_review`, `delete_edit`, `kill`), or `python3 desktop/tools/run_all_checks.py --lan` (adds the step `phone-link-lan`; SKIPPED without `--lan`, so the default one-button run is unchanged).
- Parameters: `--links N` (1..1000, default 50), `--previews P` (each is a 20 MiB preview, default 5), `--tie T`, `--desktop-links D`, `--kills K` (0..5), `--edits/--trashes/--deletes`, `--keep`. `--dry-run` lists what would run and touches nothing; `--selftest` (also a runner step, `link-lan-selftest`, always on) checks the guards and comparisons on the host.
- Pieces: androidTest `LinkLanChecks` (`-e linkLan true`, phases seed / sync / inspect / resolve / edit, synthetic records only, through `LinkStore.publish`), desktop helper `stack-cli` example `link_lan` (make / dump), the existing `stack-transport` listener and the existing pairing phases.

What it measures and checks (summary in `<output>/summary.json`, counts and seconds only, no keys or hashes): seed time, first sync seconds (must stay under 180 s), second (no-change) sync, per-link counts, titles, text and preview hashes phone vs computer. `tie_review`: one review per tied link, decided per link (alternating phone / computer), both sides then hold the chosen version. `delete_edit`: rename, trash and delete on the phone reach the computer and stay. `kill`: force-stop after seed, then at 0.3 / 3 / 8 s ... after `sync-start` inside a running sync (phase markers in `link-lan-phase`), inspect after each kill (nothing lost, no duplicates), then a final sync must converge.

Limits: the computer side is changed only by the synthetic archive it starts from (the desktop has no link editing), so deletes and edits travel phone to computer only. Kills are timed from the phone's `sync-start` marker, not at the exact probe points (those are the T1 desktop tests). The 1000-link run needs `--previews` small (disk and time). Not run on a device yet.
