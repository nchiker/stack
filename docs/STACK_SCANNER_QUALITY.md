# Scanner quality evidence — 0.4.2-dev

Measured 2026-10-16 on Pixel 4a, Android 13 / API 33. Baseline processing: `4515be9` / 0.4.1-dev. Final source adds no runtime dependency, native library, OCR engine or downloaded model. See the [source review and integration decision](STACK_SCANNER_RESEARCH.md).

## Scope and gates

The bounded pass covers automatic crop reliability, paper cleanup, source sampling, and the user's subsequent request for both perspective and gentle-curve flattening. Full camera, real-paper quality and P0 acceptance remain open. These are deterministic synthetic regressions, not a benchmark against Google or the shortlisted applications.

Gates established for the fixtures: accepted corners within 2.5% of the source diagonal; accurate crop or safe abstention for an ambiguous touching object; no crop for blank/circular/L-shaped regions; at least 98% near-white paper with faint/dark strokes and continuous small-text edges retained; source bitmap/file and edit-state preservation; reversible flattening reducing baseline spread to at most 2 pixels and below 35% of the original; no warp for straight, blank, severe or inconsistent curves. The touching-object fixture intentionally permits abstention because its page edge merges with a similar-brightness object. Null means manual/full-frame fallback, not a zero-error crop.

## Fixed-source crop comparison

Each source is 800 × 1,000 pixels, with authored text, deterministic texture and known quadrilateral corners. Metric: maximum corresponding-corner Euclidean distance divided by the source diagonal. The same inputs and output convention were used before/after.

| Scene | Baseline error | Revised error / behavior |
| --- | ---: | ---: |
| Skew, dark background | 1.522% | 0.240% |
| Low-contrast light background | 0.368% | 0.238% |
| Illumination gradient | 0.340% | 0.228% |
| Strong broad shadow | 0.340% | 0.182% |
| Similar-brightness object touching paper | 14.104%; includes object | Abstains; no unsupported crop |
| Patterned background | 11.546%; includes surface | 0.195% |
| Narrow receipt | 0.344% | 0.146% |
| Diagonal page | 1.399% | 0.119% |

Usable crops: **6/8 → 7/8**, plus one safe fallback. All seven revised proposals have less than 0.25% diagonal corner error on these sources. Negative fixtures: blank rejected by both; L-shaped region and circle incorrectly proposed by the baseline, rejected by the revision. This small designed set cannot estimate real-world success rates.

Causes addressed: the outer bright-region fallback bypassed rejection; a single brightness cutoff could fragment a shadowed sheet; interior statistics could describe the table when an oversized proposal contained mostly table; partial supported edges could outline non-page shapes; coarse Hough angles left boundary strips. All candidates now share validation, use additional histogram-derived proposals, require sustained edge/contrast evidence, and fit accepted sides to nearby transitions.

## Paper cleanup and fine detail

Inputs are 3:4 portrait at long edges 800, 1,600 and 3,200 pixels. Fixtures include a broad lighting gradient, hard shadow boundary, deterministic texture, isolated bright outliers, 12-pixel text at the 800-pixel reference scale, a faint two-pixel stroke and a dark two-pixel stroke. Near-white means output luminance at least 250/255 in the known blank-paper region, including its shadow boundary. This metric does not count text as a paper defect.

| Output long edge | Baseline near-white paper | Revised near-white paper | Baseline faint / dark sample | Revised faint / dark sample |
| --- | ---: | ---: | ---: | ---: |
| 800 | 38.224% | 99.814% | 240 / 0 | 218 / 30 |
| 1,600 | 2.128% | 99.890% | 240 / 0 | 218 / 30 |
| 3,200 | 2.286% | 99.391% | 10 / 0 | 218 / 30 |

The outliers deliberately stress a local maximum: at larger windows, even a small number of highlights contaminates much of its paper estimate. Median filtering of the estimate suppresses these highlights; maximum followed by minimum contracts the estimate back onto shadow boundaries. Original stroke pixels are not spatially filtered. A broader continuous tone curve preserves faint marks. This designed stress case should not be interpreted as typical baseline performance.

The runner also checks that at least 98% of known small-text core pixels remain dark, intermediate stroke-edge tones exist, source pixels are unchanged, and output size is unchanged. Crop-aware decoding separately retains three-pixel line/gap detail when a page occupies half a 2,400 × 3,200 source and is rendered at long edge 1,600. Source bytes and saved crop/rotation/color remain unchanged by rendering. Perspective/clockwise rotation, EXIF orientation, color presets and unedited PDF byte passthrough are checked independently.

## Gentle curved-paper correction

Authored horizontal text is displaced using a sine curve, independently of the implementation's quadratic fit. At 600 × 800, positive and negative 14-pixel bows start with 10-pixel spread among baseline fiducials. The revised output reduces this to **2 pixels and 0 pixels**, respectively. The renderer applies the selected correction, turning it off restores the original geometry, and the source bitmap remains byte-identical. Straight text, a 60-pixel severe bow, opposite curves in the upper/lower halves, and blank paper are rejected.

This verifies a bounded vertical text-curve correction, not arbitrary 3D paper reconstruction. Horizontal compression, curled edges, varying curvature, folded/occluded text, sparse handwriting, columns, tables and photographs need separate assessment. Flattening requires cropped, upright horizontal text, is off by default, and offers a preview before selection. Every saved/imported page retains its existing choice unless explicitly edited.

## Durability, offline behavior and cost

The former instrumentation UI path discarded Preview's real draft. That path has been removed. The runner refuses the regular package, creates fresh `files/checks/<UUID>` contexts for every data test, and never seeds or saves into the actual library/draft. It performs no camera capture and does not inspect personal document content.

Checks cover schema **1 → 3** and **2 → 3** with non-default crop/rotation/color, title, text and membership preservation; new flatten defaults off. Legacy version-1 draft JSON without the optional flatten field loads correctly. A selected flatten flag survives draft reload and scan save. Sources, multipage order, idempotent save, safe page removal, edited PDF page counts and FTS4 behavior remain covered. Schema 3 only adds `pages.flatten INTEGER NOT NULL DEFAULT 0`; no existing crop/color values are rewritten. Draft version remains 1 with an optional field.

Stack Preview was updated with `adb install -r`; no application was cleared or uninstalled. Original Google Stack and the regular successor were not updated or edited. An on-device aggregate SHA-256 over Preview's original-document and draft files was recorded before the first update and compared after verification; contents did not leave the phone. Database files necessarily change for the additive migration; real-library metadata was not exported. Synthetic migration checks and the inspected SQL establish preservation behavior separately.

Only `CAMERA` is requested in the built APK. `INTERNET` is absent; automatic backup remains disabled. `debugRuntimeClasspath` remains ExifInterface 1.4.2 → JSpecify 1.0.0. APK inspection finds zero `.so` libraries and no added model assets. These are static offline guarantees plus local execution of the tested processing/storage/export paths; a new-install airplane-mode camera walkthrough is still pending.

Verification APK: **287,541 → 297,402 bytes** (+9,861 bytes; about 9.6 KiB). These are local development-signed APKs, not release-size predictions. Crop analysis for all eight scenes plus three negatives took about 1.7 seconds in a full run. Cleanup took approximately 0.1–0.3 / 0.4 / 1.6 seconds at 800 / 1,600 / 3,200 in observed runs, versus baseline 0.15 / 0.22 / 0.88 seconds. Gentle-curve estimation plus mesh application took about 0.04–0.20 seconds at 800. These are single-device timings, not warmed statistical benchmarks. Cleanup does more passes for better illumination estimates and costs more time at export size.

At 3,200 the runner observed roughly 117 MB Java-used and 66 MB native-heap allocation after processing, including its fixture buffers. These are snapshots, not peak RSS or a production per-page memory requirement. Lower-memory devices, full 4,096 decode/3,200 export chains and large multipage exports still need profiling. Processing stays on the existing background executor.

## Reproduction and limits

The commands below reproduce the historical 0.4.2 two-variant setup. Current 0.4.3 uses one canonical Stack installation; use the [README check commands](../README.md#isolated-scanner-checks) and [consolidation record](STACK_CONSOLIDATION.md). Do not recreate Preview on the consolidated handset.

Historical cached-toolchain commands:

```sh
ANDROID_USER_HOME=/tmp/stack-dev/android-user ./gradlew \
  -g /tmp/stack-dev/gradle-cache \
  :app:assembleDebug :app:assembleVerification :app:assembleVerificationAndroidTest \
  :app:lintDebug :app:dependencies --configuration debugRuntimeClasspath \
  --offline --no-daemon --console=plain
adb install -r app/build/outputs/apk/verification/app-verification.apk
adb install -r app/build/outputs/apk/androidTest/verification/app-verification-androidTest.apk
adb shell am instrument -w -r org.stackdocs.app.verification.test/org.stackdocs.app.ScannerChecks
```

`-e qualityOnly true` runs only the quality fixtures. `-e baseline true` disables comparative crop/cleanup gates for measuring an older processing implementation; it must not be used for acceptance. The measurements above used the original three processing classes before replacement. Current fixture generation is reproducible from `ScannerQualityChecks.java`; synthetic PNGs are written only inside the isolated check directory. No personal images, screenshots, APKs or third-party source were committed.

Final builds and lint succeeded offline. Lint reports **0 errors, 7 warnings**: two dependency/tool update notices, an unused resource, two DetailsSheet constructor/accessibility notices and two pre-existing untranslated camera button strings. No blanket suppression or lint baseline was introduced. Final runner result: **32 groups passed**, `INSTRUMENTATION_CODE: -1`; document/draft aggregate hashes match. These processing/storage results remain valid after the final Flatten-label resource-only cleanup, whose APK builds and lint were refreshed. Final synthetic PNGs were visually inspected: readable small text, a visible faint stroke, clean paper and straighter bowed text; real-photo visual acceptance remains open.

Remaining gates: real photos at fixed output sizes and manual truth corners; very faint marks and photographs; severe shadows/low light; curved-page examples beyond the designed text bows; editor/flatten preview visual acceptance; supported API/device coverage; camera interruptions and denied permissions; storage exhaustion; memory/latency profiling. Existing saved crops are intentionally not replaced by an update: rerun **Auto crop** and choose **Apply** to save a new proposal. No OCR or automatic classification work is included.

## Subsequent user acceptance

The user reports testing the delivered changes and explicitly approves them. This supplies hands-on acceptance of the scanner slice beyond the synthetic checks above. The tested document set and additional device/lighting coverage were not specified, so this does not close the broader corpus, platform or P0 gates. No new automated checks were needed for this documentation-only acceptance update.
