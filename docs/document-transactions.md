# Cross-page ink and document history

## Cross-page capture

The scrolling finite-page editor captures drawing, erasing, and lasso gestures across hosted paper and sticky-note surfaces. The originating native view keeps pointer ownership, palm rejection, pressure/history smoothing, and cancellation.

- `DocumentInkCapture` freezes geometry at contact-down, splits segments at every paper and sticky-note boundary, and interpolates pressure.
- Ink on a sticky note stores in note-local coordinates; stroke width adjusts across differing page scales to keep consistent screen thickness.
- Gaps between pages hold no ink; re-entering a surface begins a new segment of the same logical action.
- Shapes and graphs generate in source coordinates and split as styled line outlines. Ruler projection, hold-to-shape, and scribble-to-erase share the same routing.
- Previews paint directly to native views without per-sample Compose updates or loading the full notebook. Layout changes or unavailable crossed pages discard the entire contact; locked surfaces reject drawing and protect erasing.

Pen drawing, finger erasing, and lasso use document capture once admitted by the input/palm gate. Finger drawing keeps single-page ownership so retroactive palm withdrawal still works. Single sheets and infinite canvases reuse the same pure sticky-note routing and commit through page content mutations; ordinary single-page strokes keep the fast commit path. Eraser previews consume each new segment once rather than replaying the gesture. Laser trails forward session-only samples to neighbouring native views and never commit edits.

## Scribble to erase

Scribble-to-erase commits the drawn stroke first and applies erasure as a separate undo step: one Undo restores the erased ink while keeping the scribble, a second Undo removes the scribble, and Redo replays both in order (including note-local and grouped cross-page strokes). A scribble that hits nothing remains one ordinary stroke.

- Pure geometry (`Scribble.kt`) picks erase targets and always holds veto power.
- An unmistakable **pen** scrub (`Scribble.isCertain`: an extra reversal, tightly packed lines, not a hollow loop) erases on geometry alone, offline.
- Every other candidate (and every highlighter scrub) requires Google ML Kit Digital Ink Recognition (`EN_AU_GESTURE`) to classify `scribble`. Missing, downloading, or failed recognition preserves ink; busy recognition queues.
- Target hit-testing and inference run on a worker thread. Before erasing, Folio verifies notebook identity, affected page revisions/content, and gesture lifetime; a new contact, cancellation, or detached source discards pending recognition.
- Settings exposes model download status and retry. Recognition runs only at gesture completion, so Undo never re-triggers erasure. No storage format change is needed.

## Spanning lasso

A spanning lasso selects editable ink, text, sticky notes, and images across frozen hosted pages (including off-page sticky notes) while respecting hidden and locked layers.

One shared action bar handles Copy, Duplicate, Delete, ink restyling, handwriting-to-text, drag, resize, rotate, layer changes, and Move to page:

- **Duplicate** leaves the clipboard untouched and keeps the copy selected (stepping back at a finite page edge).
- **Restyling and recognition** include ink inside selected sticky notes; recognition finishes before a revision-checked grouped commit.
- **Drag, resize, and rotate** share one document pivot and route transformed outlines to destination surfaces; text, notes, and images move whole to the page under their centre. All selected pages must be hosted.
- **Layer changes** apply across the selection only if the target layer is editable on every member.
- **Move to page** runs a grouped move, copying image assets and fitting content to the destination. Per-page transform handles stay hidden during a spanning selection.

## PDF marking regions

Dragging a mark area across hosted PDF pages creates one linked manual region with a page-local rectangle on each crossed sheet. `DocumentMarking` intersects the drag with each paper frame, skips seams, and preserves mixed PDF scales in page units. The starting page owns the award (even on upward drags). The allocation popover shows the page count and reads candidate allocations across all segments, leaving ambiguous guesses to the marker. Tapping or hovering any segment opens the same award, loading its anchor page if needed.

- `MarkZones.parts` keys segments by notebook page ID and PDF index so reordering or duplicating pages never misdirects an award.
- Manual-zone preferences preserve their six-field format for single-page zones and append a seventh field with escaped page IDs and local bounds for linked regions (no notebook/snapshot/journal format change). Older builds ignore extended definitions while keeping the ordinary award text and decorations.
- Awarding, editing, or clearing a linked region loads and revision-checks every member and commits one document group. Only the anchor receives the awarded-mark text box so totals count once; continuations receive matching box/strike decorations and preserve unrelated stamps. Grouped Undo/Redo restores every member together.

Run `node tools/marking-smoke.cjs` to check mixed scales, upward drags, unavailable pages, page identity, preference round-trips, single awards, re-awards, clearing, and inverse edits. On device, drag across adjacent PDF pages in both directions, confirm one allocation, tap a continuation, award/change/clear it, Undo/Redo, and reopen, confirming totals count once, unrelated stamps stay untouched, and unloaded or locked members never create partial awards.

## Durable commit and compatibility

`FolioViewModel.commitDocumentEdits` validates member revisions, computes positional forward/inverse edits, and publishes all member content and history together. The notebook ledger tracks group membership and action sequence; Undo/Redo loads unloaded members at their exact checkpoint, validates every stack, and queues one document operation. Any newer ordinary edit on a member must be undone before its group. Structure actions keep session-only history; grouped content history survives restarts.

`DocumentReducer.history` plans the full replay before mutating live stacks, requiring the ledger step and every member top's group/action identity to match. Both normal and grouped loads install page history before publishing content. Cached group availability depends only on the ledger and page membership.

On disk, the repository reserves member journal sequences, atomically writes and fsyncs a CRC-protected `document.pending` intent, appends and fsyncs each missing member record, checkpoints the ledger and affected page summaries, fsyncs directory updates, and removes the intent. Interrupted commits replay safely without duplicating records; a conflicting reserved sequence fails recovery. Startup and backup staging recover pending commits before exposing state. Failed saves stay ordered in **Retry save**, and backup barriers report failure instead of exporting uncommitted state.

- Notebook index stays version 6 until the first grouped action, then permanently writes version 7.
- Group/action metadata uses edit flag 16, journal record version 2, and snapshot version 3 (which always includes the layer-list slot).
- Older builds refuse version 7 indexes rather than partially opening grouped notebooks.
- Portable native v3 backups preserve the ledger, snapshots, and journals after recovering pending intents. Duplicating a notebook resets history as before.

## Verification

`node tools/backup-smoke.cjs` exercises page/note clipping, interpolated pressure, mixed scales, shapes and styled graphs, swept/incremental erasing, protected underlying ink, local canvas coordinates, skipped boundaries, re-entry, blocked pages, spanning lasso, binary/JSON parity, crash recovery at every commit stage, repeated recovery, history folding, grouped Undo/Redo, legacy version selection, and portable ZIP round-trips. Adjacent pure rules are covered by navigation, writing-follow, palm-rejection, music, and graph smoke scripts. These do not cover Android power-loss semantics or native device input.

Build a signed local APK with `./build.sh -n -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke`. Do not publish storage changes until the device round-trip below passes.

## Required device round-trip

1. Cross two and three finite pages with pen and highlighter, varied pressure, coalesced history, unequal PDF scales, and visible seams both on and off. Check gaps, dots, and page re-entry. Test a resting palm, cancelled contact, notebook/layout change, pane resize, and Undo mid-stroke; no partial or duplicate ink should remain.
2. Draw pen/highlighter, ruler lines, every shape, and styled graphs across paper and sticky-note edges (starting inside and outside notes). Erase across those edges with pressure and whole-stroke modes; verify locked note paper protects underlying ink. Confirm hold-to-shape and scribble-to-erase keep surface ownership and cancellation leaves every page unchanged. Lasso ink, text, sticky notes, and images across adjacent pages; test hidden/locked layers, Copy, Duplicate, Delete, restyle, layer changes, drag, resize/rotate, handwriting-to-text, and Move to page. Confirm Undo/Redo acts on every member together, including images.
3. Close, reopen, and restart after a grouped stroke, Undo, and Redo. Scroll member pages out of the loaded window before replaying. Add an ordinary edit on one member and confirm it undoes before the group. Delete and restore a member via structure Undo and recheck group membership.
4. Interrupt the app mid-write and after Undo/Redo, then reopen and inspect member content, stack identities, index revisions, and pending-intent cleanup. Simulate a failed write and **Retry save**; verify queued edits remain ordered and backups report failure until saved.
5. Export/import a native `.folio` notebook and restore a library backup after grouped Undo and Redo, comparing all members and re-testing Undo/Redo. Open legacy base and layered notebooks to confirm unchanged behaviour and conversion. Confirm an older build refuses a grouped notebook instead of partially opening it.
6. Compare native pen latency, sustained-writing saves, long-document memory, and dense-page scrolling on the same device and notebooks before and after the build.
