# AGENTS.md

Native Android notebook app. Single module `:app`, Kotlin + Jetpack Compose + Material 3. Package `com.folio.notes` under `app/src/main/java/com/folio/notes/`.

## Build / verify (JDK 17, compileSdk 36 / targetSdk 35 / minSdk 26)

- Toolchain: AGP 9.4.0 with built-in KGP 2.2.10; Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` must match it. `jvmTarget` is intentionally unset (defaults from `compileOptions` Java 17).
- Canonical check (same as CI `.github/workflows/android.yml`):
  `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- Single unit test: `./gradlew :app:testDebugUnitTest --tests "com.folio.notes.PageJournalTests"`
- Full pre-release validation: `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest`; instrumented tests need an isolated emulator/device: `./gradlew :app:connectedDebugAndroidTest`
- Windows-only shortcut: `.\build.ps1` (uses ignored `.tooling/` JDK/SDK; same default tasks). No `local.properties` / `.tooling/` needed on macOS/Linux with JDK 17 + SDK 36.
- Offline KaTeX check (also runs in CI): `node tools/katex-smoke.cjs`

## Architecture entrypoints

- `MainActivity.kt` → `FolioApp.kt` (nav, pickers, sharing, settings) → `FolioViewModel.kt` (library/editor state, lazy page loads, app-scope serialized save queue, survives Activity recreation).
- Persistence: `NoteRepository.kt` (fsynced journal + background snapshot compaction) over pure codecs `NoteStore.kt` / `PageJournal.kt` / `NotebookArchive.kt`. Rendering: `InkView.kt` (native input surface) + shared `InkRenderer.kt` (editor/thumbnails/exports).
- Keep `PdfSearch.kt`, `PdfLinks.kt`, `PdfOutline.kt`, `NotebookTextSearch.kt`, `VceModels.kt`, and other `*pure*` helpers free of Android imports — unit-testability is intentional.
- `mistakes/*` is ExamTrack review (Supabase sync + cache); `math/*` is reusable offline KaTeX. Details and smoke test in `docs/examtrack.md`.

## Storage format (do not reshape casually)

- Notebook dir `files/notebooks/<id>/`: `note.json` (title/folder/star/tags/attempts/redo flags + per-page summaries, **no ink**) + `pages/<id>.json` (snapshot) + `pages/<id>.journal` (JSONL deltas, fsynced per edit, compacted past 256 KB) + `pages/<id>.history` (persisted per-page undo, cap 60) + `images/*.jpg` + `source.pdf` if imported. `NoteMetaCodec.VERSION = 6`, page `VERSION = 1`.
- Journal/snapshot invariant: snapshot records last journal sequence it contains; torn final journal line is discarded. `.folio` archives always expand to full portable pages. Folders in `files/library.json`; previews in `cacheDir/thumbnails`; mistake cache in `files/examtrack/<user-id>/cache.json` (v2).

## Conventions / gotchas

- Material 3 pinned to `1.5.0-alpha01` with Compose BOM `2025.06.01`. Stable M3 omits Expressive APIs (`ExperimentalMaterial3ExpressiveApi`); check Android M3 release notes before upgrading. Reference: `ExpressiveComponents.kt` previews.
- `targetSdk 35` is intentional; `lint { disable OldTargetApi, GradleDependency, TrustAllX509TrustManager }` — the TrustAll warning is a third-party TLS jar in Gradle cache, not Folio code. `AutoboxingStateCreation` is informational-only.
- Release `isMinifyEnabled/shrinkResources = false` until baseline-profile + R8 keep-rules for pdfbox-android are validated (reflectively loaded font tables).
- `app/build.gradle.kts` `afterEvaluate` reorders `android.jar` last on unit-test classpaths so `org.json:json` `JSONObject.similar()` compiles. Do not remove.
- Shrinking/renaming storage fields requires migration + round-trip tests (see `NoteTests.kt`, `LazyStoreTests.kt`, `PageJournalTests.kt`); scheduler parity fixtures regenerate via `node tools/examtrack-scheduler-fixtures.cjs ../examtrack`.

## Secrets / releases (never commit real keys)

- ExamTrack config order: env → `-P` gradle property → untracked `local.properties` → safe placeholders. Requires HTTPS URL + `sb_publishable_` or `anon`-role JWT; secret keys fail the build. CI greps `app/build.gradle.kts docs/ README.md .github/` for real `sb_publishable_*` / `*.supabase.co` and fails.
- Releases (`.github/workflows/release.yml`): Gradle maps full `git rev-list --count HEAD` to version `x.y.z` (`x=count/100`, `y=(count/10)%10`, `z=count%10`); Android `versionCode` stays the full count. Keep `main` append-only so it remains monotonic. Release tags, titles, and APK names use `vX.Y.Z` / `X.Y.Z`; the updater retains compatibility with historical `v0.2.N` tags. Local and CI builds share Gradle's generated version. Signed RSA-3072 (`folio` alias) via `ANDROID_KEYSTORE_BASE64/PASSWORD` secrets; local signed builds need `ANDROID_KEYSTORE_PATH/PASSWORD`, else `assembleRelease` fails. Each release verifies with `apksigner` and ships `SHA256SUMS`. Debug and release keys differ — uninstall debug (export first) before installing a release.
