# Stack: working rules

## Setup and install must live in the product, not in scripts
Anything a fresh install or a later setup needs (keys, permissions, paths, first-run steps) must be part of the app itself,
or the packaging must change so it is not needed. Do not solve it with a helper script, a shell command the user runs,
an environment variable, or a dev-machine path (for example `~/Projects/...`). Before calling something done, ask:
"On a brand-new computer with only the installed app, how does this get set up?" If the answer is not "the app does it",
fix the design, not the symptom. No band-aids.

## Process
- Routine work (reads, edits, tests, builds) by cheaper agents; suggest a higher-cost review and wait for approval.
- Never print, log or store keys. Publishing only on the user's explicit go-ahead.
