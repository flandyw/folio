# Application command and document boundaries

`FolioApplication` creates one `FolioRuntime` for the process. `FolioViewModel` is a compatibility presentation port: it mirrors workspace state to its Activity's `SavedStateHandle` and forwards existing commands. Clearing an Activity's ViewModel never closes the writer, discards history, loads a second library, or creates another active notebook. The first Activity restores its session; later owners observe the existing session rather than rewind it.

The application services have distinct working state:

- `DocumentEngine`: page loading, page structure, content commits, revisions, compaction checkpoints, and merged local/group/structure undo. `DocumentReducer` plans ordinary and grouped content changes without Android or storage. Group planning rejects stale, missing or unloaded members before any live history changes.
- `NotebookCatalog`: folders, tags, metadata, organization and bulk library operations.
- `NotebookWorkflowService`: notebook creation, response attempts, mistake-practice notebooks and hidden music notebooks. Music import and its shelf continue to use the independent music repository.
- `WorkspaceManager`: tabs, remembered cameras and positions, split/reference panes and PDF navigation/search.
- `ExamSessionManager`: notebook sittings, timers, pen activity and Focal activity integration. Its timer flow has no notebook content.
- `EditorTextController`, `EditorMediaController`, `EditorSelectionController`, `EditorLayerController` and `MarkingController`: editing workflows that use the same engine commits and writer. They never own notebook copies.
- `BackupService`: import review, archive/export/backup workflows and progress. Storage barriers remain mandatory before copying or staging content.

The internal runtime forwards are retained as a migration port for existing workflows. New native document mutations go through `EditorPageMutations`; `InkViewHost` has no `FolioViewModel` parameter. `EditorPageActions` combines that page-bound port with presentation callbacks. The existing native input, palm rejection, cancellation, drawing buffers, raster lifecycle and writing-follow path remain intact.

## State and reading

`DocumentStateStore` is the synchronized publication point. Its authoritative compatibility state and indexed notebook/page values describe the same immutable content. `IndexedItems` preserves order separately from ID lookup, replaces one item through shared tree branches, and compares shared branches without walking their contents. An ordinary page replacement changes O(log pages + log notebooks) collection nodes. Structural or bulk replacements rebuild the relevant indexes.

`DocumentReader` exposes notebook metadata, a revisioned page snapshot and page order independently. Readers receive null when an identity is removed and continue to work if an undo restores it. Removing a page also drops its loaded content from retained page-flow values. Page-order readers do not emit for ink changes; unrelated page readers keep the same snapshot.

The application root and Library/Workspace presentation models collect `shellState`: notebook/page metadata with ink, text, pictures, layers and clipboard content removed. Only the editor collects loaded document content; individual native page containers additionally observe their page flow. The existing `FolioState` is a compatibility DTO, not a second owner of mutable notebook content. Catalog thumbnails can still react to page revisions, and save/navigation changes still update their relevant presentation. This is not a claim that every editor composition is now page scoped.

## Authoritative writer

`DocumentWriteCoordinator` owns the only channel, batching window, failed-operation replay, export/copy barriers, edit/durable generations, dirty index checkpoints and compaction barriers. `DocumentWriteStorage` supplies the narrow durable operations; `RepositoryWriteStorage` adapts the existing repository. Media writes and other commands enter as ordered operations. Admission assigns a generation and enqueues together, while actual I/O is serialized against the application's backup gate.

Consecutive writes for the same page share an append/fsync. A failed grouped operation and everything after it remain ordered; successfully written operations are excluded from replay. A barrier wakes after a failure so a copy/export reports a save error rather than waiting indefinitely. Idle checkpoints cannot resurrect deleted notebooks and do not count as new user edits.

No binary, JSON metadata, archive, grouped-history or recovery format changes are involved. `NoteRepository` still implements the existing recovery, snapshots, journals, media and migration rules. `loadPages` reads sequentially because those reads share its recovery/write mutex; it no longer starts one queued coroutine per page.

## Automated verification

Run `./gradlew :app:testDebugUnitTest` for conventional JVM checks. The 14-test suite covers indexed snapshots and equality, immediate-observer publication consistency, reader isolation and deletion/restoration, randomized edit inversion, real binary journal replay with undo, grouped revision conflicts, writer batching, failed-group ordering, retry, barriers and deletion/checkpoint behavior. Tests inject a storage implementation into the real writer and do not construct an Android application.

Use `./build.sh -p -- :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke` for the signed experimental build. Continue to run `node tools/backup-smoke.cjs`, `node tools/palm-rejection-smoke.cjs` and `node tools/page-navigation-smoke.cjs`. There is no visual verification in this refactor.

`node tools/document-engine-benchmark.cjs` compares the former list rewrite, indexed replacement and complete engine state publication. It uses 256 notebooks, a 1,024-page active notebook, 1,000 warmups and 5,000 measured edits on this JVM. It reports median/P95/P99 CPU time and per-thread allocations. The October 12, 2026 run of the final implementation measured:

- Former list rewrite: median 15,480 ns, P95 20,361 ns, P99 109,321 ns; 5,383 allocated bytes/edit.
- Indexed replacement alone: median 840 ns, P95 1,920 ns, P99 2,360 ns; 975 allocated bytes/edit.
- Full engine publication including projections/readers: median 5,561 ns, P95 9,680 ns, P99 44,880 ns; 2,770 allocated bytes/edit.

These are synthetic collection/publication measurements, not completed-stroke latency or tablet performance. Pen-to-ink delay, dense-page frame pacing, retained bitmap memory and real durable-save latency still require the tablet and representative notebooks. Keep those measurements separate from this benchmark. No native tool-engine rewrite or Gradle module extraction is implied by these results.
