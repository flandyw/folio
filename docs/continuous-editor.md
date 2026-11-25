# Continuous document editor

Finite notebooks render as a single vertical paper column with 6 dp seams, one document camera, and shared page navigation. Tapping the page counter opens the page browser; both the browser and a page's long-press menu expose page actions (TalkBack also gets a page-options action and a numbered native surface label). Tabs follow the toolbar's tabs preference, and ordinary pages and PDFs keep their natural aspect ratios and spacing.

The fast-scroll thumb sits 2 dp from the right edge with a 24 dp grab zone spanning only the visible thumb (tapping elsewhere on the track no longer jumps). Scrubbing begins once vertical drag passes touch slop, and adding another finger or a pen cancels the scrub.

## Ownership

- **`EditorScreen` & `EditorWorkspaceContent`:** Notebook-keyed route creating `EditorSession` and providing its surface registry. Supporting UI lives in `editor/follow/WritingFollowToolbar.kt`, `editor/EditorPeekOverlay.kt`, and `editor/EditorMarkingState.kt`. `InkView` owns input and page state while `SelectionChromePainter`, `FollowOverlayPainter`, `RulerPainter`, and `LaserTrail` (`ink/`) paint chrome and `ink/ImageCropFrame.kt` holds pure crop geometry.
- **`EditorSession`:** Owns shared tools, per-tool options, single-stroke eraser return targets, document/fitted cameras, browser state, and overlay drafts. Saveable state holds no notebook content.
- **`ContinuousDocumentHost` & `ContinuousDocument`:** Own stylus-aware document gestures, fast scrubbing, carry overlays, edge scrolling, pull feedback, and keyed page virtualisation. `SinglePageCanvas`, `InfiniteCanvas`, and `MusicCanvas` keep their own pager/native-camera behaviour.
- **`EditorViewportCoordinator`:** Combines `PageNavigation`, `DocumentViewport`, and `DocumentMotion`. Explicit destinations hold priority until landing, continuous cameras survive fitted-page visits, and reading positions persist page ID plus local vertical offset across resize, tabs, and restoration (deep links deliberately override the anchor).
- **`DocumentPageGeometry` & `EditorTransferCoordinator`:** Pure geometry separating paper and workspace bounds, window/page transforms, seam targets, and anchors; `EditorTransferCoordinator` drives destination-first selection and sticky transfers.
- **`EditorPreferences`, `EditorFollowCoordinator`, `EditorFollowState`:** Shared preference snapshot, answer-area binding, and explicit canvas-response/overview state (global follow settings never start canvas motion).
- **`EditorPage`, `PageAssets`, `InkViewHost`:** Paper workspace, cached async PDF/image loading and guide detection, and the Compose/native bridge (`InkSurfaceConfig`, `PagePresentation`, `EditorPageActions`). Handles detach cleanly and update identity when reused.
- **`PdfRenderCache`:** LRU cache of recent parsed PDFs and shared backgrounds separate from PDFBox metadata scans; see [PDF rendering](pdf-rendering.md).

`FolioViewModel` and `NoteRepository` remain the sole content and persistence owners. Cross-page drawing, shapes, graphs, ruler strokes, erasing, and spanning lasso run through `DocumentInputCoordinator` and commit via recoverable grouped transactions; see [Document transactions](document-transactions.md). Reading-position JSON adds only optional presentation fields, keeping old positions readable.

## Automated checks

`node tools/page-navigation-smoke.cjs` tests mixed PDF sizes, margin notes, multi-scale coordinate round-trips, seam boundaries, invalid frames, reordered/deleted anchors, 10,000-page prefix geometry, most-visible-page selection, and navigation races. Run:

```sh
./build.sh -n -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke
node tools/page-navigation-smoke.cjs
node tools/writing-follow-smoke.cjs
node tools/palm-rejection-smoke.cjs
node tools/backup-smoke.cjs
node tools/music-smoke.cjs
node tools/graph-smoke.cjs
```

Backup and graph runners pick up the newest release or debug classes automatically; no lint or separate debug APK build is required.

## Device acceptance and performance record

Compare the previous build and the new experimental build on the same device and notebooks:

1. Write, highlight, erase, hold a shape, and Undo/Redo across adjacent ruled pages and PDF answer areas. Test pressure/history, a palm resting across a seam, `ACTION_CANCEL`, and Android-cancelled contacts. No partial or duplicate strokes should remain after a layout switch.
2. Pinch near a seam, pan from the workspace, and fling. Jump rapidly across short/tall PDFs, search hits, outline entries, bookmarks, and add/undo/delete; explicit jumps must land before derived selection resumes.
3. Scroll halfway through a zoomed page, switch to single-page mode, turn pages, and return; confirm continuous position and zoom. Rotate, resize a split pane, reorder/insert preceding pages, switch tabs rapidly, reopen, and restart; verify deep links override old anchors.
4. Create, resize, and overlap sticky notes across seams and past paper edges (including below 90 × 60 page units); confirm both halves accept ink/eraser/taps, spacing stays fixed, and state survives reopen. Carry sticky notes and selections across seams with auto-scroll; confirm source pinning, locked/hidden layer protection, and per-page undo.
5. Open zoom writing and peek on dense ink/PDF pages, switch active pages/notebooks, and confirm commands only hit hosted surfaces. Check response columns, natural line breaks, overview return, and automatic answer-area detection.
6. Test one- and two-sheet music layouts and split/reference panes for independent cameras and isolated persistence.
7. With TalkBack and large font scale, verify page numbers, page options, browser controls, and tools stay discoverable. Confirm fast-scroll taps preserve offset, dragging reaches both ends, and a second finger or pen cancels a scrub.
8. Record pen-to-pixel latency, scroll frame timing, dense-page memory, PDF load time, and sustained-writing save times rather than assuming improvements from refactors.
