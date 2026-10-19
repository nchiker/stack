# M2 final visual and interface review

Engineering and automated checks run headlessly. This is the final user review;
the agent does not open these windows. The authoritative closeout checkbox is in
the [project plan](../STACK_DESKTOP_PLAN.md#one-consolidated-user-dependent-checklist-for-implementation-closeout).

The prepared review directory is `/tmp/stack-m2-user-review`. Its files are authored
synthetic examples, with separate disposable libraries/config and export folders.
It does not open your normal library or the private phone archive. To recreate it
after `/tmp` cleanup, from `desktop/` use a fresh output name:

```sh
python3 tools/prepare_m2_review.py --output /tmp/stack-m2-user-review-NEW
```

Start the small review library when ready:

```sh
/tmp/stack-m2-user-review/run --theme light --size 1080x780 --text-scale 1
```

The launcher sets PDFium and isolated XDG paths. Close normally before reopening
that same library. It retains saved changes. Unsaved editor drafts last only for
the current process. This source build uses the installed COSMIC/system fonts.

Begin with the navigation problem reported in the [interface review](M2_INTERFACE_REVIEW.md).
The supplied HTML mockup is the layout reference. The agent has also performed a
native screenshot comparison with readable sample PDFs at 1080×860; see the
current interface review for that evidence and remaining platform limits. The sidebar contains library
destinations and Backup & restore at its bottom. Back stays at the upper left;
Search and Add documents appear in the library; the document workspace uses that
space for its page/export toolbar. Back is hidden at the library root. At narrow sizes use the
COSMIC navigation toggle to reopen the sidebar.

Walk through the workflow in this order:

1. From All documents, choose Manage stacks in the left sidebar. Scroll down,
   then use Back in the window header. Confirm you return to the same list/search.
   Open Manage stacks again and select a stack on the left; the document list should
   appear. Check that Back, Backup & restore and Add documents retain their positions.
   Try Grid/List and reopen to check the saved layout choice. Cards use real first-page thumbnails with file icons while loading or on error.
   Check names, dates and stack chips beneath the thumbnails.
   Browse the five initial documents and stacks. Search `laterpagesentinel` and
   confirm the hit opens the later PDF page. Check preview zoom and page navigation.
   Use Manage stacks to create, rename, recolor, hide/show and reorder a category.
2. Import `sources/two-pages.pdf`, `oriented.jpg`, `corners.png` and `corners.webp`
   from the review directory using Add documents and the actual file chooser. Check each saved outcome.
   Open another chooser and Cancel; the document count should stay the same.
3. Open a document. Check the preview beside Document information; at narrow sizes
   use Document information/Show preview; information and editing retain a smaller
   page above the controls. Choose Edit document and try Information,
   Details and Pages. Confirm an unfinished title survives section/preview changes
   and an unsaved-change notice appears. Done editing leaves the draft available;
   it does not save it. Save a title, multiline notes, filing and a typed detail.
   Page rotation/reordering/removal are under Pages; document deletion is at the
   end of Information.
   Try an invalid value,
   correct it, save, close the window and reopen using the same launcher. Check that
   exact values and page order persist. Try empty filing as an intentional choice.
4. Export an unchanged imported PDF to `exports/original.pdf`; its original text,
   page count and appearance should remain. Reorder, rotate or remove a page, then
   export to `exports/edited.pdf`. Inspect the saved order/appearance. Rendered PDFs
   have no selectable text layer. Choose a new filename for every output; existing
   destinations are preserved.
5. Use Backup & restore → Export backup to export the portable library to `exports/review.stackbackup`. Use Backup & restore → Import backup for that archive,
   inspect the additive review, and Cancel first. Then select it again and commit:
   the archive adds document copies. Check progress, outcome messages and that the
   interface remains usable while work runs. Remove a test document through its
   confirmation control, and check that it disappears from search.
6. Exercise Tab, Shift-Tab, Enter/Space, Ctrl+F and Escape. Ctrl+F returns to and
   focuses search; Escape first closes an open Backup & restore menu, then cancels an editor confirmation
   before leaving the editor.
   Check focus visibility, labels, text entry, wrapping, scrolling and window controls.
7. Reopen in dark/narrow/enlarged states with the commands below. The same `--text-scale`
   option now scales the entire native shell. Look for clipped controls, tiny preview
   space, blank notes or frame stalls. Report the action and window settings if any
   appear. Review the second library's Previous/Next results controls as well.

```sh
/tmp/stack-m2-user-review/run --theme dark --size 680x680 --text-scale 1
/tmp/stack-m2-user-review/run --theme light --size 1080x780 --text-scale 2
/tmp/stack-m2-user-review/run --theme dark --size 680x680 --text-scale 2
/tmp/stack-m2-user-review/run-many --theme light --size 1080x780 --text-scale 1
```

Known platform qualifications: scoped native patches now publish the actual
widget hierarchy and route targeted activation; wrapped rows also publish their
children. Headless tests exercise the same tree builder and action path. Actual
AT-SPI/screen-reader consumption is still unobserved. The chooser now exports a
real owned native parent; actual placement and focus return remain user checks. Earlier live compositor frame stalls and the historical M0/M2.1
shutdown watch remain recorded; passing offscreen draws does not prove those
platform issues resolved. No live compositor or chooser was exercised during this
headless closeout. Any observed product failure is follow-up engineering work,
not a passed review item.

No phone installation, private-library transfer, pairing, OCR engine or sync is
part of this review. Those belong to later milestones and their own consent.
