# Stack interface reference and implementation

This pass brings the independent application's interface closer to Google Stack. It uses the supplied APKs as read-only layout references, the [reference audit](STACK_REFERENCE_AUDIT.md), and Google's [launch presentation](https://blog.google/innovation-and-ai/technology/area-120/stack/). Historical screenshots were viewed outside the repository. No Google layouts, code, icons, fonts, illustrations or screenshots are bundled in the application.

## Reference patterns

The later APK's Home resources describe a search surface, two bottom destinations, circular stack previews with 72 dp icon containers and 14 sp labels, and a floating add menu. Document preview resources define thumbnail cards with small titles. The detail resources describe a subdued page canvas, compact toolbar actions, a floating edit control and a persistent details sheet with a 160 dp peek height. The scan overview has a large preview, Color/Crop & rotate tools and a save action; post-scan filing combines the name and selected stacks. These are static observations, not a complete runtime or pixel comparison. See audit evidence E03–E05 and E09.

## Implemented in 0.3.0-dev

| Area | Interface changes |
| --- | --- |
| Shared styling | White surfaces, blue actions, neutral gray text and page canvases, platform sans-serif typography, compact rounded controls and independently drawn line icons. The independent launcher icon uses the same blue palette. |
| Home | Search card at the top; Your Stacks/Edit header; circular pastel-ring category tiles; New stack control; recent-document thumbnails; icon-and-label Home/All documents navigation. |
| Library/stack browsing | Two-column document thumbnails on phones, with more columns at larger widths; compact titles; distinct search-result rows with previews and snippets when available. Thumbnails use actual local first pages and a bounded cache. |
| Add | Floating white plus button opens a compact PDF/Photo/Camera menu above it. Each option invokes the real picker or scanner. |
| Document | Back/title/share/overflow toolbar, large page preview, compact page navigation, floating page-edit action, and expandable/collapsible Details sheet with memberships and added date. Share, rename, filing, Save PDF and deletion remain available. |
| Stack selection | Circular category badges with explicit multiple selection and check marks, shared by document details and scan review. |
| Scan review | Preview canvas and page controls above Color/Crop & rotate/Add page tools; filled name field; stack selection before Save scan. Selected memberships are applied to the saved document. |
| Camera/editor | Dark camera chrome and circular shutter; white review/editor toolbars; blue crop outlines and icon-based editing tools. |

The code uses platform Android views and APIs. No runtime dependency or database schema change was needed for this interface pass. Starter category rings use a consistent presentation palette; custom stacks retain their stored color. Names and filing selections survive activity recreation; the existing unfinished-draft limitation remains: reopening a scan in a new session restores its pages/edits, with a date-based name and a fresh filing selection.

## Deliberate differences and remaining work

- The library menu replaces Google's account avatar behavior with local options. There is no sign-in, Google branding, Drive status or cloud UI.
- System typography replaces Google Sans, and independent geometric symbols replace Google's artwork. This is an interface reconstruction, not a claim of pixel-identical reproduction.
- Search still indexes names only. No organization suggestions, extracted key values, OCR progress or automatic category results are fabricated for presentation.
- The Details sheet supports a handle tap, keyboard activation and an upward/downward handle swipe; it does not reproduce the complete Material bottom-sheet gesture/physics implementation.
- Full camera scenarios, large-font/landscape layouts, accessibility and the updated filing flow still need acceptance on the supported Android versions. The user subsequently reported successful capture and saving on the Pixel 4a. Existing scanner checks are historical 0.2.0-dev evidence, not a new result for the revised interface or processing.

Builds use the existing JDK/Gradle/SDK setup. `verification` is a separate **Stack Preview** installation, with its own application ID and data; it can show synthetic preview documents without accessing the regular library. Generated files and all external reference material remain excluded from Git.

## Build and handoff record

Same-page runtime reference: the original Google Stack build B was inspected on the handset with a user-provided scan of the same printed page. Its displayed output has smoother text and cleaner paper than our 0.4.0 binary filter. Version 0.4.1 keeps the Black & white label but uses continuous grayscale cleanup to preserve stroke edges. This observation does not establish Google's selected preset or implementation. The original app was displayed with dark surfaces; our current white interface remains a known visual difference. See the playbook for the comparison's limits.

In 0.4.0-dev, scan review's Color tool opens a direct per-page choice: Black & white, Original color, Color · Medium or Color · Bright. New camera captures default to black and white after automatic perspective correction when corners are found. The editor shares these choices. Original color preserves geometry; Reset restores the full original. Imported/existing pages keep their current settings. This behavior follows the user's reference feedback; cleanup quality and curved-page flattening are not established parity.

On 2026-10-16, `:app:assembleDebug` and `:app:assembleVerification` built offline using the existing toolchain. No automated tests were added or run for this interface pass. A separate Stack Preview installation was prepared on the Pixel 4a with three clearly labeled synthetic documents. The handset was locked, so a screenshot review could not be completed; no lock-screen or personal-library screenshots were taken. The regular app installation and its library were left unchanged. Open **Stack Preview** after unlocking to review the redesign.

Follow-up handset review on 2026-10-16: the user identified a clipped top border on a stack chip in the document Details sheet. The chip's horizontal row used centre gravity with a bottom-only child margin, lifting the chip above its parent's clipping bounds. Top-aligning these rows preserves the complete outline. Both APK variants rebuilt successfully; Stack Preview was updated in place, and a screenshot of the synthetic Bills document confirmed the full border and rounded corners. No automated tests were added or run for this fix.
