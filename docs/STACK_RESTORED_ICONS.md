# Restored stack icons and colors — 0.8.8 / 0.8.9-dev

After restoring the converted Google Stack archive on a fresh phone, the standard
stack circles displayed folder glyphs. Documents and filing were present.

The converter deliberately assigns UUIDs to source categories. A pristine library
restore preserves that catalog, replacing the starter categories. Icon lookup
previously recognized only the starter IDs (`bills`, `banking`, etc.), so every
imported UUID fell back to a folder. The earlier additive migration check merged
Bills into an existing starter catalog and missed this fresh-phone behavior.

Version 0.8.8-dev / code 21 recognizes the names Bills, Banking, IDs, House,
Medical, Receipts and Starred when an ID is unfamiliar. Starter IDs still retain
their icons after renaming. Name matching ignores case and outer whitespace and
uses a fixed locale. Other custom stack names retain the folder fallback. The
shared resolver covers Home, stack pickers/editor and document membership badges.
Stored category colors and all library data remain unchanged; schema stays 8 and
archive format stays 3, with formats 1 and 2 still supported.

Install the new APK over the existing app using Android's **Update** action. Keep
the existing installation and library. **Do not restore the Google archive again**:
the display fix takes effect on the already imported stacks, and another restore
would duplicate documents. The APK must have the original signing identity.

Host verification uses `python3 tools/test_stack_icons.py`: it compiles and runs
the production resolver against the Android SDK, covering opaque imported IDs,
remapped IDs, renamed starters, custom fallback, Turkish locale and unchanged
category values. `python3 tools/test_google_stack_migration.py` checks the existing
eight synthetic migration regressions. The Android `googleMigration` check now
also restores its authored fixture into a pristine library and checks its icons;
that added device case compiles but has not been executed on a device.

The offline app and instrumentation builds pass, as does Android lint (0 errors,
14 warnings). The packaged OCR/native-library audit passes. The update APK is
`~/Downloads/Stack-0.8.8/Stack-0.8.8.apk`, SHA-256
`ac4790a241fed36f3495b4c6c2c52e3b5bd0f47f83eb20028acb437463e44fd5`.
Its signer matches the earlier 0.8.7 delivery:
`db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab`.

At the user's request, the update was installed on the connected primary Pixel
10a on 2026-10-17. The user granted temporary USB access and accepted the phone's
debugging prompt. The installed application's original signing key was checked
before `adb install -r`; installation succeeded. With the app stopped, a sorted
SHA-256 fingerprint of every file under its private files, databases and
preferences directories matched exactly before and after the update. Hashing
happened on the phone without copying personal documents or logging filenames.
The installed APK hash and version 0.8.8-dev/code 21 were verified, then Stack
was opened. No uninstall, second restore, database replacement or test-library
mutation occurred. Private delivery evidence is in `/tmp/stack-icon-phone-update/`.

## Color follow-up — 0.8.9-dev / code 22

The user then reported that the circles were all gray. Version 0.8.8 repaired
glyph lookup but missed the separate ID-only color lookup. All 17 stacks in the
converted archive also used the same neutral `#E4EDE2` fallback because the Google
source stored palette codes rather than ARGB colors. Unplugging the phone did not
cause the display issue.

`StackStyle.ringColor` now uses the shared icon identity/name resolver for the
seven standard pastel ring colors. For custom stacks with the neutral default,
it selects from eight local pastel colors using the stable stack ID, so renaming,
changing visibility or changing document counts does not change the color.
Explicitly stored custom colors remain in use. This supplies consistent local
colors; it does not claim to reconstruct Google's undocumented palette codes.
No stored colors, stack IDs, memberships or documents are rewritten. Schema and
backup format remain unchanged. The shared UI helper covers Home, stack pickers,
the stack editor and document membership chips.

The host regression now also checks standard imported ring colors, renamed
starter colors, varied stable custom colors, explicit custom-color preservation
and unchanged stored values. Both it and all eight migration tests pass. The
synthetic Android fresh-restore check also asserts colored rings.

The 0.8.9 offline app/instrumentation build and lint pass (0 errors, 14 warnings),
along with the static OCR/native-library audit. The APK is
`~/Downloads/Stack-0.8.9/Stack-0.8.9.apk`, SHA-256
`e8ef8bce015a29ad0fd0b180fa1a0472f6f93c2029cef9d2bc9e57c4222d7ffa`;
the original signing certificate remains unchanged.

After the user restored USB access and authorized the reconnected phone, 0.8.9
was installed on the primary Pixel 10a with `adb install -r` and opened. The
installed signing key, APK hash and version/code were verified. With Stack
stopped, the complete private files/database/preferences fingerprint matched
exactly before and after this update too. No second restore or data rewrite
occurred. Private delivery evidence is in `/tmp/stack-color-phone-update/`.
Visual confirmation of the rings remains with the user; no personal-library
screenshot was captured.
