# AGENTS.md

Native Android notebook app. Single module `:app`, Kotlin + Jetpack Compose + Material 3. Package `com.folio.notes` under `app/src/main/java/com/folio/notes/`.

## Build / verify (JDK 17, compileSdk 36 / targetSdk 35 / minSdk 26)

- Toolchain: AGP 9.4.0 with built-in KGP 2.2.10; Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` must match it. `jvmTarget` is intentionally unset (defaults from `compileOptions` Java 17).
- Canonical check (same as CI `.github/workflows/android.yml`):
  `./gradlew :app:assembleDebug :app:lintDebug`
- Windows-only shortcut: `.\build.ps1` (uses ignored `.tooling/` JDK/SDK; same default tasks). No `local.properties` / `.tooling/` needed on macOS/Linux with JDK 17 + SDK 36.
- Offline KaTeX check (also runs in CI): `node tools/katex-smoke.cjs`

## Architecture entrypoints

- `MainActivity.kt` → `FolioApp.kt` (nav, pickers, sharing, settings) → `FolioViewModel.kt` (library/editor state, lazy page loads, app-scope serialized save queue, survives Activity recreation). `WorkspaceScreen.kt` holds the split/reference companion pane; its PDF tools are pure maths in `PdfReference.kt` and the panels in `PdfReferencePane.kt`. Both workspace dialogs (document picker, pane options) live in `WorkspacePanels.kt` over the pure rules in `WorkspacePicker.kt`.
- Persistence: `NoteRepository.kt` (one fsynced journal append per batched transaction + background snapshot compaction) over pure codecs `NoteStore.kt` / `PageJournal.kt` / `NotebookArchive.kt`. Rendering: `InkView.kt` (native input surface) + shared `InkRenderer.kt` (editor/thumbnails/exports).
- The graph tool is dressing, not a special ink type: `GraphStyle.kt` (style + the single-stroke label font) and `GraphAxes.kt` turn a drag into ordinary `Tool.LINE` strokes, so `divisions`/`step`/origin changes cost no migration and never touch the storage format. The style lives in one preference string (`GraphStyle.PREF_KEY`) that the editor watches.
- Keep `PdfSearch.kt`, `PdfLinks.kt`, `PdfOutline.kt`, `NotebookTextSearch.kt`, `VceModels.kt`, and other `*pure*` helpers free of Android imports so they stay cheap to exercise from scratch or the REPL.
- `mistakes/*` is ExamTrack review (Supabase sync + cache); `math/*` is reusable offline KaTeX. Details and smoke test in `docs/examtrack.md`.

## Storage format (do not reshape casually)

- Notebook dir `files/notebooks/<id>/`: `note.json` (title/folder/star/tags/attempts/redo flags + per-page summaries, **no ink**; checkpointed when the writer goes idle, never per stroke) + `pages/<id>.json` (snapshot: content + `journalSeq` + `revision` + undo/redo stacks) + `pages/<id>.journal` (JSONL `PageTransaction`s, one append + one fsync per batch, folded into the snapshot past 4 MB while the writer is idle) + `images/*.jpg` + `source.pdf` if imported. `NoteMetaCodec.VERSION = 6`, page `VERSION = 1`. A `pages/<id>.history` file is only ever read, for pages written before undo state moved into the journal.
- Journal/snapshot invariant: a snapshot records the last journal sequence it contains **and** the undo/redo stacks as of that point, and each record states its own stack effect, so folding the log onto it rebuilds the same stacks before the log is dropped. Torn final journal line is discarded. On load a page's revision is `max(index, snapshot, journal)`.
- Page edits are positional, never whole-page: `StrokesEdit` is `Add`/`Remove`/`Insert`/`Replace`/`Rewrite`/`Set`, and `Set` only for migration or a page that shrank past half. `PageJournal.invert()` (not a second `diff`) produces the undo edit, because a diff would describe a page that grew in the middle as an append — an inverted edit must therefore undo back to the exact same page. `.folio` archives always expand to full portable pages. Folders in `files/library.json`; previews in `cacheDir/thumbnails`; mistake cache in `files/examtrack/<user-id>/cache.json` (v2).
- There are no unit or instrumented tests in this repo. Any change to the storage format or the journal/snapshot invariant must be verified by opening the affected notebooks on a device before release, and the shape of that manual check is documented in `docs/examtrack.md` / `docs/sync.md`.

## Conventions / gotchas

- Material 3 pinned to `1.5.0-alpha01` with Compose BOM `2025.06.01`. Stable M3 omits Expressive APIs (`ExperimentalMaterial3ExpressiveApi`); check Android M3 release notes before upgrading. Reference: `ExpressiveComponents.kt` previews.
- `app/build.gradle.kts` `lint { disable OldTargetApi, GradleDependency, TrustAllX509TrustManager }` — the TrustAll warning is a third-party TLS jar in Gradle cache, not Folio code. `AutoboxingStateCreation` is informational-only.
- Release `isMinifyEnabled/shrinkResources = false` until baseline-profile + R8 keep-rules for pdfbox-android are validated (reflectively loaded font tables).
- Shrinking/renaming storage fields requires migration plus a manual round-trip check on device (see `docs/examtrack.md`); scheduler parity is regenerated by porting the ExamTrack cases by hand.
- `postgrest.rpc` params must be typed `Map<String, JsonElement>` (see the builders in `sync/SyncRemote.kt`): a map inferred as `Map<String, Any>` has no kotlinx serializer and throws `SerializationException: Serializer for class 'Any' is not found` before the request is sent, which surfaces as a sync error and silently never publishes queued work. Sync errors name Focal, not the merged-away ExamTrack project, and the banner keys off `isSyncTrouble(status)` rather than the wording.

## Secrets / releases (never commit real keys)

- ExamTrack config order: env → `-P` gradle property → untracked `local.properties` → safe placeholders. Requires HTTPS URL + `sb_publishable_` or `anon`-role JWT; secret keys fail the build. CI greps `app/build.gradle.kts docs/ README.md .github/` for real `sb_publishable_*` / `*.supabase.co` and fails. ExamTrack was merged into Focal, so there is one credential pair: `FOCAL_SUPABASE_URL` / `FOCAL_SUPABASE_PUBLISHABLE_KEY`. `FocalSupabaseConnection` is the process-wide Supabase client (Auth/Postgrest/Realtime/Storage) shared by `FocalStudyManager` and `ExamTrackAuthRepository`; never build a second client on the same session file.
- Releases (`.github/workflows/release.yml`): Gradle maps full `git rev-list --count HEAD` to version `x.y.z` (`x=count/100`, `y=(count/10)%10`, `z=count%10`); Android `versionCode` stays the full count. Keep `main` append-only so it remains monotonic. Release tags, titles, and APK names use `vX.Y.Z` / `X.Y.Z`; the updater retains compatibility with historical `v0.2.N` tags. Local and CI builds share Gradle's generated version. Signed RSA-3072 (`folio` alias) via `ANDROID_KEYSTORE_BASE64/PASSWORD` secrets; local signed builds need `ANDROID_KEYSTORE_PATH/PASSWORD`, else `assembleRelease` fails. Each release verifies with `apksigner` and ships `SHA256SUMS`. Debug and release keys differ — uninstall debug (export first) before installing a release.
