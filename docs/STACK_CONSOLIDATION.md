# Single-app consolidation — 2026-10-16

The user approved the scanner functionality in Stack Preview and requested a single app. Version **0.4.3-dev**, code **7**, keeps that functionality in **Stack**, package `org.stackdocs.app`. The `verification` build type and Preview label override were removed. The old Preview and both temporary instrumentation packages were uninstalled only after data preservation was verified. The proprietary Google Stack installation was untouched.

## Preserved data

| Library | Documents | Pages | Stacks | Schema |
| --- | ---: | ---: | ---: | ---: |
| Previous Stack | 3 | 8 | 7 | 1 |
| Previous Stack Preview | 5 | 6 | 8 | 3 |
| Consolidated Stack | 8 | 14 | 8 | 3 |

Document/page IDs were disjoint. The seven shared stacks had identical settings; the additional stack and all memberships were retained. Neither installation had an unfinished scan. No content-based deduplication, title rewriting, crop replacement or enhancement change was performed.

Both stopped libraries were snapshotted privately, excluding only known synthetic `files/checks` directories. The offline [consolidation tool](../tools/consolidate_libraries.py) normalized the old schema using the app's existing additive migrations and combined documents, pages, stacks, memberships and FTS content. All metadata rows were compared exactly, including crop, rotation, color, flatten choice, timestamps, source identity and text fields. Entire owned document directories were copied, retaining original files even when individual pages had previously been removed.

All **8 original files** matched SHA-256 before/after. The completed payload was read back from the phone: every metadata row and original-file hash matched the prepared union; SQLite integrity and foreign-key checks passed; all page sources resolved. Both pre-consolidation archives were also read back and hash-verified before Preview was removed. They remain in Stack's private storage at `files/consolidation_recovery/pre-consolidation-2026-10-16/{regular,preview}.tar`. These developer recovery copies are not the planned user-facing portable backup/restore feature. Do not remove them as cache cleanup.

The previous camera grant was carried over so scanning remains available. Stack's main activity launched successfully. Package inspection confirmed exactly one `org.stackdocs.app` installation after Preview/test removal. No personal images, database contents, titles, archives or document hashes are committed; temporary host copies were removed after verified private recovery copies existed on the phone.

## Verification

- Six synthetic Python checks pass: schema 1/2 compatibility and retained edits/text/originals; custom stack and unfinished draft preservation; conflicting identities/settings and two active drafts blocked before output.
- The canonical app passed **32 scanner runner groups** on Pixel 4a / Android 13, including original preservation, migration, drafts, export, autocrop, cleanup and gentle-curve flattening. The runner now explicitly targets `org.stackdocs.app` and writes only into fresh isolated child contexts. It never seeds or edits the real library/draft.
- `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug` passed offline. Lint: **0 errors, 7 existing warnings**. APK inspection still reports only CAMERA; no network/runtime dependency or image-processing algorithm changed in this consolidation.
- Original-versus-readback comparisons passed after the app initialized and scanner checks ran. The extra installation was removed after those gates, not before.

Reproduce the tool checks with Python 3.11+:

```sh
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

The preparation tool operates only on trusted private snapshots, never on a live app database. It refuses ambiguous IDs, stack definitions, source paths or simultaneous active drafts rather than choosing which data to overwrite. It does not install/uninstall applications or implement a general backup format. Python is optional developer tooling; the Android app and its build remain independent of it.

The approved scanner behavior and its broader real-photo/platform limitations remain in [scanner evidence](STACK_SCANNER_QUALITY.md). OCR is still unimplemented.
