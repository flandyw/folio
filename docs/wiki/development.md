# Developer guide

[Wiki home](README.md) · [Project setup](../../README.md) · [Repository instructions](../../AGENTS.md)

## Architecture

Folio is a single `:app` Android module in Kotlin, Jetpack Compose and Material 3. `MainActivity` enters `FolioApp`; the application owns `FolioRuntime`. `FolioViewModel` is the presentation compatibility port, while feature view models share `DocumentEngine` and `DocumentStateStore`. `DocumentWriteCoordinator` serializes writes, retry and barriers. `NoteRepository` owns on-disk notebook operations and recovery. The [application architecture](../application-architecture.md) explains state publication, readers and writer ordering.

The main source tree is `app/src/main/java/com/folio/notes/`. Package and directory names match:

| Area | Packages and entry points |
| --- | --- |
| App shell and workflows | `app/`, `FolioApp`, `FolioRuntime`, `FolioViewModel` |
| Models and document engine | `model/`, `document/` |
| Storage and backup | `storage/`, `backup/` |
| Editor and native input | `editor/`, `ink/`, `input/` |
| Library and workspace | `library/`, `workspace/` |
| Study and exams | `study/`, `mistakes/`, `exam/`, `marking/`, `progress/`, `planner/`, `vcaa/` |
| Other features | `music/`, `pdf/`, `export/`, `math/`, `settings/`, `sync/`, `chatgpt/`, `update/`, `ui/` |

`MainActivity`, `FolioApplication` and `LibraryBackupJobService` stay in the root package because Android and existing scheduled jobs refer to their names. When moving a source file, update its package, imports, any manifest reference and smoke scripts that compile it by path.

## Persistence and compatibility

A notebook directory contains `note.json` metadata, binary page snapshots (`.fps`), journals (`.fjl`), image assets and an optional source PDF. The index does not contain ink. A snapshot records the journal sequence and undo/redo stacks; journal replay must preserve the same content and history. Grouped cross-page edits use a durable pending intent and notebook ledger; recovery completes before a notebook or backup is exposed. Layers and grouped history can make a notebook unreadable to older app versions.

Portable native backups preserve content and history. Legacy JSON pages and older backups remain readable and are converted with verification. Do not casually change these formats or their migration and recovery rules. [AGENTS.md](../../AGENTS.md) is the detailed invariant reference; [document transactions](../document-transactions.md) includes the required on-device round-trip for storage changes.

## Verification

Set up JDK 25 and Android SDK 36 as described in the [project README](../../README.md). The repository's canonical check is `./build.sh -p`, a signed experimental build that **publishes** without prompting. `./build.sh -n` builds without publication when signing credentials are available. Both reserve an experimental version. CI publishes signed R8 releases; it is not a substitute for local checks.

Run `./gradlew :app:testDebugUnitTest` for JVM tests; use `--tests` for a focused test. There are no instrumented tests. Focused Node smoke scripts live in `tools/`; many need `./gradlew :app:prepareBackupSmoke` and a populated Kotlin compiler cache. `node tools/katex-smoke.cjs` checks the offline KaTeX bundle. Feature guides list relevant smoke tests and manual device checks. Do not run `:app:lintDebug` as a routine check; it is slow and is reserved for explicit requests.

For storage or journal changes, automated tests do not replace the [device round-trip](../document-transactions.md#required-device-round-trip). Input, rendering, performance, Android lifecycle and sync behaviour can also require real-device checks as described in each feature guide.

## Releases and documentation

`release-server/` is a separate Git submodule; its own README covers server setup. Folio configuration is in `release-server.conf`. Stable and experimental versioning and signing rules are in [AGENTS.md](../../AGENTS.md). Do not commit keys or passwords.

When changing behaviour, update the relevant wiki page and detailed feature guide. Keep the wiki focused on current behaviour, limitations and entry points; keep detailed invariants, migration rules and manual checks in the engineering guides. Historical measurements and proposals should remain labelled as such.
