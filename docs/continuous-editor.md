# Continuous document editor

Finite notebooks now use a single paper column with 6 dp seams, one document camera and shared page navigation. The counter opens the page browser. The active page's edge handle and the toolbar's page menu expose page actions; TalkBack also gets a page-options action and a numbered native surface label. The boundary button hides seams on ordinary pages. PDFs retain their page edges and original aspect ratios. **Focus view** in the page menu hides tab chips, caption-free page handles and ordinary-paper seams; drawing, navigation, saves and page actions remain available.

## Ownership

- `EditorScreen` is the notebook-keyed route. It creates `EditorSession` and provides its surface registry to the layouts. `EditorWorkspaceContent` composes the existing tool, follow and feature controls.
- `EditorSession` owns shared tools, per-tool options, the single-stroke eraser return target, the document and fitted-page cameras, interactions, browser state and overlay drafts. Saveable presentation state contains no notebook content.
- `ContinuousDocumentHost` owns the existing stylus-aware document gesture, fast scrubbing, carry overlays, edge scrolling and pull feedback. `ContinuousDocument` virtualizes keyed pages. `SinglePageCanvas`, `InfiniteCanvas` and `MusicCanvas` retain their existing pager/native-camera behavior.
- `EditorViewportCoordinator` uses `PageNavigation`, `DocumentViewport` and `DocumentMotion`. Explicit destinations retain priority until landing. The continuous camera survives fitted-page visits. Reading positions carry a page ID and page-local vertical offset through resize, tabs and saved-state restoration. A deep link replaces that anchor deliberately.
- `DocumentPageGeometry` is Android-free. It separates paper bounds from workspace bounds, converts window/page coordinates and provides indexed seam targets and document anchors. `EditorTransferCoordinator` tracks visible geometry and calls the existing destination-first selection/sticky transfer methods.
- `EditorPreferences` observes one shared snapshot of input/navigation preferences. `EditorFollowCoordinator` binds answer-area preferences; `EditorFollowState` retains the explicit canvas response and overview visit. Global follow settings still cannot start canvas motion.
- `EditorPage` handles the paper workspace and focused overlays. `PageAssets` retains asynchronous, repository-cached PDF/image loading and background guide detection. `InkViewHost` is the Compose/native bridge, using `InkSurfaceConfig`, `PagePresentation` and `EditorPageActions`. The registry releases handles on native detach and Compose release and updates identity when a retained view changes page.
- The browser, rename/move/delete dialogs, paper picker, text/PDF search, study/mark dialogs, mark popovers and toolbar container are separate components.

`FolioViewModel` and the repository remain the only content/persistence owners. Native pressure samples, history, prediction, cancellation and raster caching keep their original path. Cross-page contacts are captured by `DocumentInputCoordinator` and committed through recoverable document transactions. Existing notebooks retain their storage versions until grouped history is used. Reading-position JSON only gains optional presentation fields; old positions remain readable.

Cross-page stylus handwriting and document-spanning lasso now use frozen paper geometry and shared native previews. Shared selection actions and grouped Undo/Redo commit every affected page together. Details, compatibility and recovery checks are in [document transactions](document-transactions.md). Existing sticky carries and single-page selection transfers retain their independent per-page undo effects.

## Automated checks

The navigation smoke runner now compiles document geometry and viewport math from source. It covers mixed PDF sizes, margin notes, coordinate round trips at multiple scales, exact seam boundaries, invalid frames, reordered/deleted anchors, 10,000-page prefix geometry, most-visible-page selection and the existing explicit-navigation races. Backup smoke also covers pressure interpolation at seams, mixed page scales, spanning lasso, fault-injected group recovery, history folding and portable backup restoration.

Run:

```sh
./build.sh -n -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke
node tools/page-navigation-smoke.cjs
node tools/writing-follow-smoke.cjs
node tools/palm-rejection-smoke.cjs
node tools/backup-smoke.cjs
node tools/music-smoke.cjs
node tools/graph-smoke.cjs
```

Backup/graph runners select the newest available release/debug classes so the canonical signed build can supply the code under test. No lint or debug APK assembly is required.

## Device acceptance and performance record

No device was connected during implementation. Native stylus, TalkBack and performance results remain unmeasured; compilation and pure smoke checks do not establish those results.

On the same device and the same notebooks, record the previous build and the new experimental build:

1. Write, highlight, erase, hold a shape and use Undo/Redo on adjacent ruled pages and PDF answer areas. Test pen pressure/history, a palm resting across a seam, ACTION_CANCEL and Android-canceled contacts. No partial or duplicate stroke should appear after a layout switch.
2. Pinch around a finger near a seam, pan from the workspace and release into inertia. Jump rapidly through short and tall PDF pages, search hits, contents, bookmarks and add/undo/delete. Explicit jumps must land before derived page selection resumes.
3. Scroll halfway through a page at enlarged zoom; enter single-page mode, turn pages and return. Verify the continuous position and zoom. Rotate, resize a split pane, reorder or insert a preceding page, switch tabs rapidly, close/reopen and restart the app. Check deep links land on their requested page rather than an old anchor.
4. Carry sticky notes and selections across seams and beyond paper edges, auto-scroll both directions and release. Source pages must remain pinned, locked/hidden layers must stay protected, and each page must Undo its own transfer half. Reopen and compare content.
5. Open zoom writing and peek on dense ink/PDF pages. Switch the active page, close/reopen each surface and switch notebooks; commands must target only currently hosted surfaces. Check response columns, natural line breaks, overview return and automatic answer-area detection.
6. Read and annotate one/two-sheet music layouts and a split/reference companion. Confirm independent cameras and annotation persistence, without notebook imports/backups absorbing music collection files.
7. With TalkBack and larger font scaling, enable focus view. Page numbers, page options, browser controls and selected tools must remain discoverable.
8. Compare pen-to-pixel recordings, scroll frame timing, memory after opening/revisiting dense pages, PDF load time and sustained-writing save completion. Use the same dense notebook, long mixed-size PDF and multi-view session. Record measurements rather than claiming a speed improvement from the refactor alone.
