# Application command and document boundaries

`FolioApplication` creates one `FolioRuntime` for the process. `FolioViewModel` is a compatibility presentation port: it mirrors workspace state to its Activity's `SavedStateHandle` and forwards existing commands. Clearing an Activity's ViewModel never closes the writer, discards history, loads a second library, or creates another active notebook. The first Activity restores its session; later owners observe the existing session rather than rewind it.

The application services have distinct working state:

- `DocumentEngine`: page loading, page structure, content commits, revisions, compaction checkpoints, and merged local/group/structure undo. `DocumentReducer` plans ordinary/grouped content changes and grouped Undo/Redo without Android or storage. Replay validates ledger membership, loaded members and each top edit's group/action identity before any live history changes.
- `NotebookCatalog`: folders, tags, metadata, organization and bulk library operations.
- `NotebookWorkflowService`: notebook creation, response attempts, mistake-practice notebooks and hidden music notebooks. Music import and its shelf continue to use the independent music repository.
- `WorkspaceManager`: tabs, remembered cameras and positions, split view (second pane, focus swap, parked splits) and PDF navigation/search.
- `ExamSessionManager`: notebook sittings, timers, pen activity and Focal activity integration. Its timer flow has no notebook content.
- `EditorTextController`, `EditorMediaController`, `EditorSelectionController`, `EditorLayerController` and `MarkingController`: editing workflows that use the same engine commits and writer. They never own notebook copies.
- `BackupService`: import review, archive/export/backup workflows and progress. Storage barriers remain mandatory before copying or staging content.

The internal runtime forwards are retained as a migration port for existing workflows. New native document mutations go through `EditorPageMutations`; `InkViewHost` has no `FolioViewModel` parameter. `EditorPageActions` combines that page-bound port with presentation callbacks. The existing native input, palm rejection, cancellation, drawing buffers, raster lifecycle and writing-follow path remain intact.

## State and reading

`DocumentStateStore` is the synchronized publication point. Its authoritative compatibility state and indexed notebook/page values describe the same immutable content. `IndexedItems` preserves order separately from ID lookup, replaces one item through shared tree branches, and compares shared branches without walking their contents. An ordinary page replacement changes O(log pages + log notebooks) collection nodes. Its iterator walks the tree once in O(n), with O(log n) temporary storage; ID misses and page-position lookup use the index directly. Structural or bulk replacements rebuild the relevant indexes.

Publication compares shared branches for any number of replacements, rather than recognizing only the last replacement. A grouped edit refreshes only its changed page summaries/readers and preserves the page-order list. Notebook metadata changes reuse every page summary and skip page ownership/reader work. `Notebook.withPage` keeps the indexed collection, so lazy loading and page metadata commands receive the same incremental behavior as ink commits. Group planning looks up edited members and sorts their positions to preserve durable document order without scanning every page.

Grouped Undo/Redo now uses the same indexed planning/publication boundary. The reducer produces all member pages, inverse stack effects, transactions and the moved ledger before the engine pops any live stacks. Only member pages are replaced; unrelated pages keep their instances, including unloaded pages. Normal and grouped page loading share one history installation helper and install exact undo/redo checkpoints before exposing loaded content.

`DocumentHistoryAvailability` caches available groups by notebook ID, ledger identity and the indexed membership token. This token contains only IDs/positions, so the cache never retains an old page tree or ink arrays. Ordinary content/metadata/load changes reuse validation; ledger changes, deletion, restoration, reordering and notebook switches invalidate it. Ordinary commits preserve the ledger instance when there is no grouped redo to clear. Plain-list callers are validated each time without retaining their content as a key.

`DocumentReader` exposes notebook metadata, a revisioned page snapshot and page order independently. Readers receive null when an identity is removed and continue to work if an undo restores it. Removing a page also drops its loaded content from retained page-flow values. Page-order readers do not emit for ink changes; unrelated page readers keep the same snapshot.

The application root and Library/Workspace presentation models collect `shellState`: notebook/page metadata with ink, text, pictures, layers and clipboard content removed. Only the editor collects loaded document content; individual native page containers additionally observe their page flow. The existing `FolioState` is a compatibility DTO, not a second owner of mutable notebook content. Catalog thumbnails can still react to page revisions, and save/navigation changes still update their relevant presentation. This is not a claim that every editor composition is now page scoped.

## Authoritative writer

`DocumentWriteCoordinator` owns the only channel, batching window, failed-operation replay, export/copy barriers, edit/durable generations, dirty index checkpoints and compaction barriers. `DocumentWriteStorage` supplies the narrow durable operations; `RepositoryWriteStorage` adapts the existing repository. Media writes and other commands enter as ordered operations. Admission assigns a generation and enqueues under the state store's single publication lock before notifying observers, so an immediate observer cannot enqueue the next edit ahead of the current one. Actual I/O is serialized against the application's backup gate.

Consecutive writes for the same page share an append/fsync, using a monotonic 24 ms batching deadline. A failed grouped operation and everything after it remain ordered; successfully written operations are excluded from replay. Barriers are explicit read-only operations: they never increment the edit generation or trigger auto-backup, and publish durability before waking successful callers. Failed barriers carry an error even if a later retry succeeds before the caller resumes. Cancellation propagates without reporting a storage failure; a stopped worker closes admission and cancels queued/in-flight barriers. Idle checkpoints resolve the notebook at execution time, so a delayed checkpoint cannot resurrect a deletion or restore stale metadata. Auto-backup's dirty flag is consumed under the same admission lock, so a concurrent edit cannot lose its next backup request.

Page loading waits observe the revisioned page flow instead of polling every 40 ms. A removed page ends the wait immediately; the five-second load timeout remains.

Layer rendering uses a shared pure projection for page views and eraser stroke lists. It counts visible items by layer, allocates the exact output, and fills each layer's slice in source order. This stable O(items + layers) operation replaces filtering/comparison sorting, preserves orphan fallback and locked-layer visibility, and never constructs a temporary notebook page for an eraser preview. Ordinary pages still return their original collections.

No binary, JSON metadata, archive, grouped-history or recovery format changes are involved. `NoteRepository` still implements the existing recovery, snapshots, journals, media and migration rules. `loadPages` reads sequentially because those reads share its recovery/write mutex; it no longer starts one queued coroutine per page.

## Automated verification

Run `./gradlew :app:testDebugUnitTest` for conventional JVM checks. The suite covers indexed snapshots, traversal/bounds, equality and multiple replacements, metadata projection reuse, immediate-observer publication consistency, reader isolation and deletion/restoration, randomized edit inversion, real binary journal replay with undo, grouped revision conflicts, writer batching, failed-group ordering, retry, barriers, reentrant admission, cancellation and delayed-checkpoint deletion. Grouped replay tests reject stale/missing/unloaded members, preserve current document order and verify randomized Undo/Redo through binary journals with exact stack effects. Availability tests verify content/load reuse and membership/ledger invalidation. Randomized layer tests check stable ordering, visibility, orphan fallback and shared item identity. Tests inject a storage implementation into the real writer and do not construct an Android application.

Use `./build.sh -p -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke` for the signed experimental build and publication, or `-n` to build without publishing. Run `node tools/backup-smoke.cjs`, `node tools/palm-rejection-smoke.cjs` and `node tools/page-navigation-smoke.cjs` for the adjacent rules. Storage changes also require the device round-trip in [document transactions](document-transactions.md).

`node tools/document-engine-benchmark.cjs` compares the former list rewrite, indexed replacement and complete engine state publication, plus full traversal, metadata publication, two-page publication, layer-stroke projection, grouped replay planning/publication and history availability. An optional first argument selects a saved compilation `classes.jar` with matching benchmark APIs for comparison. It uses 256 notebooks, a 1,024-page active notebook, 1,000 warmups and 5,000 measured operations on this JVM. The layer case projects 4,096 strokes over twelve layers, including hidden and orphan IDs. It reports median/P95/P99 CPU time and per-thread allocations. The October 12, 2026 run before the traversal/multiple-replacement follow-up measured:

- Former list rewrite: median 15,480 ns, P95 20,361 ns, P99 109,321 ns; 5,383 allocated bytes/edit.
- Indexed replacement alone: median 840 ns, P95 1,920 ns, P99 2,360 ns; 975 allocated bytes/edit.
- Full engine publication including projections/readers: median 5,561 ns, P95 9,680 ns, P99 44,880 ns; 2,770 allocated bytes/edit.

The October 13 follow-up compared the previous `IndexedItems`/`DocumentStateStore` sources from commit `7024968` against the new implementation using the same expanded benchmark:

- Full indexed traversal: median 40,360 → 6,880 ns; allocations 23 → 121 bytes/scan. The iterator trades a small traversal stack for a linear walk.
- Notebook metadata publication: median 408,723 → 2,600 ns; allocations 274,495 → 1,631 bytes/update.
- Two-page publication: median 462,842 → 7,160 ns; allocations 275,583 → 3,951 bytes/update.
- Ordinary single-page engine publication: median 6,160 → 7,360 ns; allocations 2,770 → 2,794 bytes/update. This path already had incremental publication; this run does not show a latency improvement for it.

The new metadata/two-page P95 values were 5,160/10,600 ns and P99 values were 9,160/38,841 ns. JVM timings vary with compilation, garbage collection and concurrent build/check work; the allocation reductions are the stronger evidence for avoiding full-page projection rebuilds.

The subsequent layer projection comparison measured median 170,801 → 82,640 ns and allocations 44,616 → 9,425 bytes/projection. The new P95/P99 values were 92,200/120,401 ns. The comparison uses the saved `4.3.0-exp.2` compilation and the new debug compilation, both before shrinking. This measures filtering/ordering alone; drawing paths and Android frame timing are outside the benchmark.

The grouped replay comparison retains the former full-page scan as a benchmark-only function and compares it with the new reducer against the same two-member action and publication store. Median planning/publication time was 286,402 → 5,160 ns, with allocations 401,051 → 5,660 bytes/action; new P95/P99 were 6,760/28,960 ns. Live stack mutations, page loading and durable I/O are excluded from both cases. Availability over sixty groups of sixty-four pages measured median 200,441 → 280 ns and allocations 967 → 23 bytes/check. The latter count includes measurement overhead; the cache hit returns the existing result.

These are synthetic collection/publication measurements, not completed-stroke latency or tablet performance. Pen-to-ink delay, dense-page frame pacing, retained bitmap memory and real durable-save latency still require the tablet and representative notebooks. Keep those measurements separate from this benchmark. No native tool-engine rewrite or Gradle module extraction is implied by these results.
