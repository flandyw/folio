# Cross-page ink and document history

The scrolling finite-page editor captures one stylus pen/highlighter contact across hosted paper frames. The originating native view retains pointer ownership, palm rejection, pressure/history smoothing and cancellation. `DocumentInkCapture` freezes geometry at pen-down, clips segments at paper edges and interpolates pressure. Width is adjusted between page scales to preserve screen thickness. Gaps contain no ink; re-entering a page creates another segment of the same logical action. Preview updates go directly to native views. Layout changes or an unavailable/locked crossed page discard the complete contact rather than committing a partial stroke.

Finger drawing, sticky-note ink, ruler strokes, held shapes, infinite canvases, fitted single-page layouts and music retain their existing input paths. Ordinary single-page contacts still use the original commit path. Cross-page capture does not load the entire notebook or run Compose state updates per sample.

A spanning lasso selects editable ink, text and images across its frozen hosted pages. Hidden and locked layers remain protected. One shared bar supports Copy, Duplicate, Delete, ink restyling and Move to page. Layer changes act on the entire selection when the destination layer is editable on every member. The shared page picker performs a grouped move, including copied image assets and destination fitting. Per-page transform handles are suppressed for a spanning selection.

## Durable commit and compatibility

`FolioViewModel.commitDocumentEdits` validates member revisions, computes positional forward/inverse edits and publishes all member content and history together. The notebook ledger records group membership and action sequence. Undo/Redo loads unloaded members with their exact content/history checkpoint, validates every member stack, and queues one document operation. A newer ordinary member edit must be undone before its group. Structure actions retain their existing session-only history; grouped content history survives restart.

The repository reserves member journal sequences, atomically writes and fsyncs a CRC-protected `document.pending` intent, appends/fsyncs each missing member record, checkpoints the notebook ledger and affected page summaries, then removes the intent. Notebook directory changes are fsynced. Recovery repeats these steps safely after interruption; already-applied records are not duplicated. A conflicting reserved sequence fails recovery. Startup and backup staging recover pending commits before exposing their state. Failed save operations remain queued in order for Retry; backup barriers report the failure instead of exporting uncommitted state.

An index remains version 6 until its first grouped action, then permanently writes version 7. Group/action metadata uses edit flag 16, journal record version 2 and snapshot version 3. Other journals and snapshots keep their existing versions. Snapshot version 3 includes the layer-list slot even when empty. An older build cannot safely read a notebook that uses grouped history and rejects its version 7 index. Portable native v3 backups preserve the ledger, snapshots and journals; pending intents are recovered before staging. Duplicating a notebook resets its history as before.

## Verification

`node tools/backup-smoke.cjs` exercises clipping, interpolated pressure, mixed scales, skipped boundaries, re-entry, blocked pages, spanning lasso, binary/JSON identity, interruption before/after every member append/checkpoint/completion, repeated recovery, history folding, grouped Undo/Redo, legacy version selection and a portable ZIP round-trip with every member history preserved. Navigation, writing-follow, palm-rejection, music and graph smoke runners check adjacent pure rules. These checks do not validate Android filesystem power-loss behavior or native device input.

Build a signed local APK with `./build.sh -n -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke`. Publication must wait for the storage/device round-trip required by AGENTS.md. No device was connected during this implementation.

## Required device round-trip

1. Cross two and three finite pages with pen/highlighter, varied pressure, coalesced history, unequal PDF scales and visible seams both on/off. Check gaps, dots and page re-entry. Test a resting palm, canceled contact, notebook/layout change, pane resize and Undo during contact; no partial or duplicate ink should remain.
2. Lasso ink, text, sticky notes and images on adjacent pages. Check hidden/locked layers, shared copy/duplicate/delete/restyle/layer actions and a move into a loaded destination. Undo and Redo must affect every member together, including images.
3. Close/reopen and restart after a grouped stroke, Undo and Redo. Scroll member pages out of the loaded window before replay. Place an ordinary edit on one member and verify it is undone before the group. Delete/restore a member with structure Undo and recheck group membership.
4. Interrupt the app during group writes and after Undo/Redo, then reopen. Compare member content, stack identities, index revisions and pending-intent cleanup. Simulate a failed write and Retry; later queued edits must remain ordered and backups must report failure until saved.
5. Export/import a native notebook and restore a library backup after both grouped Undo and Redo. Compare all member contents and perform Undo/Redo again. Open legacy base/layer notebooks and verify unchanged behavior and conversion. Verify an older build refuses a grouped notebook rather than partially opening it.
6. Record native pen latency, sustained-writing saves, long-document memory and dense-page scrolling on the same device/notebooks before and after this build.
