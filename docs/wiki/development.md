# Developer guide

[Wiki home](README.md) · [Project setup](../../README.md) · [Repository instructions](../../AGENTS.md)

## Architecture

Folio is a single `:app` Android module built with Kotlin, Jetpack Compose, and Material 3. `MainActivity` launches `FolioApp`, while `FolioApplication` owns `FolioRuntime`. `FolioViewModel` serves as the presentation compatibility layer, and feature ViewModels share `DocumentEngine` and `DocumentStateStore`. `DocumentWriteCoordinator` serialises writes, retries, and barriers, and `NoteRepository` owns on-disk notebook storage and recovery. See [Application architecture](../application-architecture.md) for state publication, readers, and writer ordering.

Source files live under `app/src/main/java/com/folio/notes/`, where package names always match directories:

| Area | Packages and entry points |
| --- | --- |
| App shell and workflows | `app/`, `FolioApp`, `FolioRuntime`, `FolioViewModel` |
| Models and document engine | `model/`, `document/` |
| Storage and backup | `storage/`, `backup/` |
| Editor and native input | `editor/`, `ink/`, `input/` |
| Library and workspace | `library/`, `workspace/` |
| Study and exams | `study/`, `mistakes/`, `exam/`, `marking/`, `progress/`, `planner/`, `vcaa/` |
| Other features | `music/`, `pdf/`, `export/`, `math/`, `settings/`, `sync/`, `chatgpt/`, `update/`, `ui/` |

Keep `MainActivity`, `FolioApplication`, and `LibraryBackupJobService` in the root package: Android launchers and persisted `JobScheduler` jobs reference their exact class names. When moving any other file, update its `package` line, imports, manifest entries, and any `tools/*-smoke.cjs` script that compiles it by path.

## Persistence and compatibility

Each notebook directory stores `note.json` metadata (no ink), binary page snapshots (`.fps`), journals (`.fjl`), image assets, and an optional source PDF. Snapshots record the journal sequence and undo/redo stacks; journal replay must reproduce the exact same content and history.

Grouped cross-page edits use a durable pending intent and notebook ledger, and recovery finishes before any notebook or backup is exposed. Layers and grouped history can make a notebook unreadable to older app versions.

Portable native backups preserve content and history, and legacy JSON pages and older backups remain readable through verified conversion. Do not reshape formats or migration rules casually; check [AGENTS.md](../../AGENTS.md) and run the required on-device round-trip in [Document transactions](../document-transactions.md).

## Verification

- **Requirements:** JDK 25 and Android SDK 36 (see the [project README](../../README.md)).
- **Canonical check:** `./build.sh -p` builds a signed experimental APK and **publishes it without prompting**. Use `./build.sh -n` to build without publishing (both reserve an experimental version number). CI publishes signed R8 releases and does not replace local checks.
- **Unit tests:** `./gradlew :app:testDebugUnitTest` (or `--tests <Class>`). There are no instrumented tests.
- **Smoke checks:** Node scripts in `tools/` exercise pure logic (most require `./gradlew :app:prepareBackupSmoke` first); `node tools/katex-smoke.cjs` runs offline.
- **Skip routine lint:** Do not run `:app:lintDebug` unless explicitly requested.
- **Device checks:** Automated tests do not replace the [device round-trip](../document-transactions.md#required-device-round-trip) for storage/journal changes. Input, rendering, lifecycle, and sync changes also carry hardware checklists in their feature guides. The front-buffer ink path is not yet device-verified; see [Low-latency ink device checks](../low-latency-ink-device-checks.md).

## Releases and documentation

`release-server/` is a separate Git submodule with its own README; Folio's server config lives in `release-server.conf`. Versioning and signing rules are in [AGENTS.md](../../AGENTS.md). Never commit keys or passwords.

When changing behaviour, update both the relevant wiki page and the corresponding engineering guide in `docs/`. Keep the wiki focused on current behaviour, limits, and entry points, and keep invariants, migration rules, and manual checklists in the engineering guides. Label historical reports and proposals clearly.
