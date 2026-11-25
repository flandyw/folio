<p align="center">
  <img src="docs/images/folio-banner.png" alt="An open notebook with handwritten notes and a stylus" width="100%">
</p>

<h1 align="center">Folio</h1>

<p align="center">A quiet, local-first Android notebook for handwriting, sketches, maths, and PDFs.</p>

<p align="center">
  <a href="https://github.com/flandyw/folio/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/flandyw/folio/release.yml?branch=main&label=release" alt="Android release"></a>
  <a href="https://github.com/flandyw/folio/releases/latest"><img src="https://img.shields.io/github/v/release/flandyw/folio?display_name=tag" alt="Latest release"></a>
  <a href="https://github.com/flandyw/folio/releases"><img src="https://img.shields.io/github/downloads/flandyw/folio/total" alt="GitHub downloads"></a>
  <a href="https://github.com/flandyw/folio"><img src="https://img.shields.io/github/stars/flandyw/folio" alt="GitHub stars"></a>
</p>

Folio is a native Android notebook built with Kotlin and Jetpack Compose. Write with a stylus or finger, organise pages into folders and tags, and keep portable `.folio` backups on your own terms.

See the [Folio wiki](docs/wiki/README.md) for feature guides, backup and account details, and developer documentation.

## Features

- **Handwriting and drawing:** Pressure-sensitive ink, multiple brushes, shape assist, scribble to erase, and full undo/redo.
- **Writing follow:** [Writing follow](docs/writing-follow.md) tracks your lines from your ink, pauses for corrections, offers one-tap or automatic line return, and pairs with a pinned peek view (hold to glance, tap to keep open).
- **Rich pages:** Typed notes with offline KaTeX maths, sticky notes, photos, and full PDF annotation.
- **Long responses:** Pinned questions, plans, linked drafts and marked copies, side-by-side comparison, and feedback actions.
- **Flexible paper:** Plain, ruled, dotted, grid, maths, and infinite-canvas pages.
- **Graph tool:** Centred or corner axes with optional grid, ticks, unit numbers, and x/y labels drawn as editable ink.
- **Organisation:** Folders, tags, favourites, page bookmarks, full-text search, and split-view tabs.
- **Study and exams:** Dedicated [study timer](docs/study-sessions.md), VCAA paper catalogue, mistake review, progress analytics, planner, and Focal sync.
- **Reference view:** A read-only PDF pane with table of contents, text search, page jump, zoom/fit, and link following.
- **Exports and backups:** Export pages as PDF or PNG, save portable `.folio` notebooks with undo history, or enable incremental automatic library backups.
- **Customisation:** Custom accent colours, app text size, interface font, toolbar layout, quick ink colours, saved tool presets, and notebook covers.
- **Offline first:** Core notebooks work completely offline on Android 8.0 and newer.

## Get Folio

Download the latest stable APK from [GitHub Releases](https://github.com/flandyw/folio/releases/latest).

Experimental builds are published to the [Folio server](https://folio.flandolf.me/releases/). To check for them in-app, open **Settings → Account & updates** and turn on **Experimental builds**; leave it off to track stable GitHub releases. Server setup and publishing are covered in [release-server/README.md](release-server/README.md).

## Development

Folio has a single Android module, `:app`, written in Kotlin and Jetpack Compose under `app/src/main/java/com/folio/notes/` (organised by feature). Start with [AGENTS.md](AGENTS.md) for the source map, conventions, storage invariants, and verification rules.

### Requirements

- **JDK 25** (`JAVA_HOME`) and **Android SDK 36** (`ANDROID_HOME`). The Gradle wrapper downloads the required Gradle version automatically.
- **ARM device or emulator:** The app packages ARM-only native libraries (`arm64-v8a`, `armeabi-v7a`).
- **Focal credentials (optional):** Local builds use placeholder Focal config unless `FOCAL_SUPABASE_URL` and `FOCAL_SUPABASE_PUBLISHABLE_KEY` are set via environment variables, Gradle properties, or untracked `local.properties`. Use only a publishable or `anon` key, never a service key.

### Building and testing

- **Canonical local check:** `./build.sh -p` builds a signed experimental APK and **publishes it to the Folio server without prompting**. It requires `.signing/folio-release.p12`, `.signing/password`, server credentials, and Android build-tools 36.0.0 (or `FOLIO_BUILD_TOOLS`).
- **Signed build without publishing:** `./build.sh -n` (still reserves an experimental version number).
- **Debug build (Windows):** `.\build.ps1 -Tasks ':app:assembleDebug'`.
- **Unit tests:** `./gradlew :app:testDebugUnitTest` (pass `--tests <Class>` to focus).
- **Smoke checks:** Run `./gradlew :app:prepareBackupSmoke` followed by the relevant `node tools/<feature>-smoke.cjs` script. `node tools/katex-smoke.cjs` runs offline with no Gradle step.
- **Skip routine lint:** Do not run `:app:lintDebug` unless explicitly asked; it is slow. Keep `app/build` and Gradle caches between builds; avoid `clean` unless diagnosing a build issue.
- **CI:** `.github/workflows/release.yml` publishes signed R8 releases on pushes to `main` or manual dispatch.

### Engineering guides

- [Application architecture](docs/application-architecture.md): state, commands, readers, and the write coordinator
- [Document transactions](docs/document-transactions.md): grouped edits, recovery, and required device checks
- [Continuous editor](docs/continuous-editor.md), [page navigation](docs/page-navigation.md), and [split view](docs/split-view.md)
- [Pen feel](docs/pen-feel.md), [palm rejection](docs/palm-rejection.md), [shape assist](docs/shape-assist.md), and [writing follow](docs/writing-follow.md)
- [Focal sync](docs/focal-sync.md), [study sessions](docs/study-sessions.md), and [planner](docs/planner.md)
- [Release server](release-server/README.md): publishing and server setup

Additional feature guides and device checklists live in [docs/](docs/). Storage-format and journal changes require the on-device round-trip described in the document transactions guide before release.
