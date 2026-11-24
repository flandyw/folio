# Application command and document boundaries

`FolioApplication` owns a single process-wide `FolioRuntime`. `FolioViewModel` is a compatibility presentation layer mirroring workspace state to its Activity's `SavedStateHandle` and forwarding commands. Clearing an Activity ViewModel never closes the writer, drops history, reloads the library, or creates a duplicate active notebook; subsequent Activity owners observe the existing session instead of rewinding it.

## Domain services

- **`DocumentEngine` & `DocumentReducer`:** Page loading, page structure, content commits, revisions, compaction checkpoints, and merged local/group/structure undo. `DocumentReducer` plans ordinary and grouped edits and replays without Android or storage dependencies, validating ledger membership, loaded members, and top edit identities before touching live stacks.
- **`NotebookCatalog`:** Folders, tags, metadata, organisation, and bulk library actions.
- **`NotebookWorkflowService`:** Notebook creation, response attempts, mistake-practice notebooks, and hidden music notebooks (music imports and the shelf stay on the independent music repository).
- **`WorkspaceManager`:** Tabs, remembered cameras and positions, split view (companion pane, focus swap, parked splits), and PDF navigation/search.
- **`ExamSessionManager`:** Notebook sittings, timers, pen activity, and Focal activity sync (no notebook content).
- **Editing controllers (`EditorTextController`, `EditorMediaController`, `EditorSelectionController`, `EditorLayerController`, `MarkingController`):** Stateless workflows over the shared engine and writer.
- **`BackupService`:** Import review, archive/export/backup flows, and progress, always honouring storage barriers before staging content.

New native mutations flow through `EditorPageMutations` (`InkViewHost` takes no `FolioViewModel`), combined with presentation callbacks in `EditorPageActions`. Native input, palm rejection, cancellation, drawing buffers, raster caches, and writing follow keep their existing paths.

## State and reading

- **`DocumentStateStore` & `IndexedItems`:** Synchronised publication point keeping compatibility state and indexed notebook/page trees in sync. `IndexedItems` separates order from ID lookup, replaces items in `O(log pages + log notebooks)` via structural sharing, and traverses in `O(n)` with `O(log n)` stack space.
- **Incremental publication:** Compares shared branches across any number of replacements. Grouped edits refresh only modified page summaries/readers; metadata edits reuse all page summaries; `Notebook.withPage` preserves the indexed tree for lazy loads and metadata updates.
- **Grouped Undo/Redo & availability:** `DocumentReducer` computes all member pages, inverse stacks, transactions, and the moved ledger before mutating live stacks, replacing only member pages. `DocumentHistoryAvailability` caches valid groups by `(notebookId, ledger identity, membership token)` without retaining page trees or ink.
- **`DocumentReader` & `shellState`:** Exposes notebook metadata, revisioned page snapshots, and page order independently (emitting `null` on removal and resuming on undo). The app shell, Library, and Workspace collect `shellState` (metadata stripped of ink, text, images, layers, and clipboard); only the editor collects loaded content.

## Authoritative writer

`DocumentWriteCoordinator` owns the sole write channel, 24 ms monotonic batching window, failed-operation replay, export/copy barriers, edit/durable generations, dirty index checkpoints, and compaction barriers (backed by `DocumentWriteStorage` / `RepositoryWriteStorage`).

- Admission assigns a generation and enqueues under the publication lock before notifying observers, preventing re-entrant out-of-order writes; disk I/O serialises against the backup gate.
- Failed grouped operations and subsequent queued ops preserve order; succeeded ops never replay.
- Read-only barriers never bump edit generation or trigger auto-backup, carry errors even if a later retry succeeds, and propagate cancellation cleanly.
- Idle checkpoints resolve notebooks at execution time so delayed checkpoints cannot resurrect deletions. Auto-backup consumes its dirty flag under the admission lock.
- Page-load waits observe the revisioned page flow (ending immediately on deletion, retaining the 5-second timeout) instead of polling every 40 ms.
- Layer rendering uses a stable `O(items + layers)` projection shared by page views and eraser stroke lists, preserving orphan fallback and locked-layer visibility without allocating temporary pages.
- No binary, JSON, archive, grouped-history, or recovery format changes are introduced. `loadPages` reads sequentially under the repository mutex instead of spawning one coroutine per page.

## Verification and benchmarks

- **Unit tests:** `./gradlew :app:testDebugUnitTest` covers indexed snapshots, traversal, multi-replacement equality, metadata reuse, observer consistency, reader isolation, randomised edit inversion, binary journal replay, revision conflicts, batching, failure ordering, retry, barriers, cancellation, grouped replay, history availability, and layer projection.
- **Build & smoke:** `./build.sh -p -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke` (or `-n`), plus `backup-smoke.cjs`, `palm-rejection-smoke.cjs`, and `page-navigation-smoke.cjs`. Storage changes still require the device round-trip in [Document transactions](document-transactions.md).
- **Synthetic JVM benchmark (`node tools/document-engine-benchmark.cjs`):** Runs 256 notebooks, a 1,024-page active notebook (1,000 warmups, 5,000 ops), and 4,096 strokes across 12 layers. Reference measurements (October 12–13, 2026):
  - List rewrite → indexed replacement → full engine publication: median 15,480 → 840 → 5,561 ns; 5,383 → 975 → 2,770 B/edit.
  - Full indexed traversal: median 40,360 → 6,880 ns (23 → 121 B/scan).
  - Notebook metadata publication: median 408,723 → 2,600 ns (274,495 → 1,631 B/update; P95 5,160 / P99 9,160 ns).
  - Two-page publication: median 462,842 → 7,160 ns (275,583 → 3,951 B/update; P95 10,600 / P99 38,841 ns).
  - Ordinary single-page publication: median 6,160 → 7,360 ns (2,770 → 2,794 B/update; already incremental).
  - Layer projection: median 170,801 → 82,640 ns (44,616 → 9,425 B/projection; P95 92,200 / P99 120,401 ns).
  - Grouped replay planning/publication: median 286,402 → 5,160 ns (401,051 → 5,660 B/action; P95 6,760 / P99 28,960 ns).
  - History availability (60 groups × 64 pages): median 200,441 → 280 ns (967 → 23 B/check).

These JVM metrics measure collection and publication overhead only, not tablet pen latency, frame pacing, bitmap memory, or disk durability.
