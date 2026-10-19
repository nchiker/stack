# Frozen P1 fixtures and rubric

Frozen before A. All independently authored CC0 synthetic content, authored and
labeled by the implementation agent. No external/human labels or private bills.
`p1-details-corpus.json`: 4 tuning + 16 evaluation cases. Exact field role/value/
precision/currency and issuer identity are scored independently on supplied text
and on pages rendered with Android PdfDocument then actual bundled English OCR.
Missing, conflicting, invalid or ambiguous evidence should abstain. Corrections,
removals, source invalidation and drafts have separate deterministic state fixtures.
Names: agent rubric unchanged / small edit / unhelpful / abstain; human usefulness
and acceptance targets remain pending. No automatic score constitutes human acceptance.

Photo fixtures, frozen by `P1CorpusChecks.photoFixture`: 20 800×1000 rendered scenes;
0–9 page positives varying text size, faint ink, warm paper and 2/4/6-degree rotation;
10–12 blank-sheet negatives; 13–16 colored stripe negatives; 17–19 seeded noise.
These are detector regression inputs, not real photos or a representative camera-roll
false-import estimate. Report precision, recall, false imports, misses and abstention
with denominators. No threshold has been agreed. Do not tune measured evaluation
cases without disclosing exposure. Real-photo/human review belongs to H4/H7.

Platform fixture providers and recipients are test-only components and use the
separate test APK UID. Authentication Session checks inject results into a pure
state boundary; they do not establish OS biometric/enrollment behavior. Photo
consent races are synthetic predicates; no actual media collection is authorized
for automated tests on the personal handset. Existing P0 corpora remain regression.

## Contract audit after first A4 measurement

Rule `local-details-2` conservatively marks a generic Date role unknown, keeps pound
currency unknown without an explicit code, and rejects contradictory currency
code/symbol pairs. Added focused regressions are exposed agent-authored cases, not
a new blind evaluation. Original frozen case text/field-ID/value/currency labels
are unchanged and remeasured. Detailed provenance now reports semantic role. The
primary stable `date:document` ID also hosts generic Date, retaining removals; its
ID is not a semantic-role claim. Audited semantic role labels/scores are separately
identified as post-exposure regression evidence in frozen-quality.json. No claim
of independent role-quality acceptance follows from those labels.
