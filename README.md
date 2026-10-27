<p align="center">
  <img src="docs/images/folio-banner.png" alt="An open notebook with handwritten notes and a stylus" width="100%">
</p>

<h1 align="center">Folio</h1>

<p align="center">A quiet, local-first notebook for handwriting, sketches, maths, and PDFs.</p>

<p align="center">
  <a href="https://github.com/flandyw/folio/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/flandyw/folio/release.yml?branch=main&label=release" alt="Android release"></a>
  <a href="https://github.com/flandyw/folio/releases/latest"><img src="https://img.shields.io/github/v/release/flandyw/folio?display_name=tag" alt="Latest release"></a>
  <a href="https://github.com/flandyw/folio/releases"><img src="https://img.shields.io/github/downloads/flandyw/folio/total" alt="GitHub downloads"></a>
  <a href="https://github.com/flandyw/folio"><img src="https://img.shields.io/github/stars/flandyw/folio" alt="GitHub stars"></a>
</p>

Folio is a native Android notebook built with Kotlin and Jetpack Compose. Write with a stylus or finger, keep pages organized, and take your notebooks with you in `.folio` backups.

Explore the [Folio wiki](docs/wiki/README.md) for feature guides, data and account information, and developer documentation.

## Features

- Pressure-sensitive handwriting, drawing tools, and undo/redo
- [Writing follow](docs/writing-follow.md) that measures your lines from the ink, holds still for corrections, and offers one-tap or automatic line return, plus a pinned peek view (hold to glance, tap to keep open)
- Typed notes, photos, and PDF annotation
- Long responses: pinned questions, plans, linked drafts and marked copies, side-by-side comparison, and feedback actions
- Ruled, dotted, grid, maths, and infinite-canvas pages
- Graph tool: centred or corner axes, optional grid, ticks, unit numbers and x/y labels
- Page bookmarks, search, and notebook organization
- Dedicated [study timer](docs/study-sessions.md): standalone or notebook-linked sessions, reflections, history, and Focal sync
- Reference view: a read-only PDF pane with contents, text search, page jump, zoom/fit and link following
- Export pages as PDF or PNG; save compact `.folio` backups with undo history, or use incremental automatic library backups
- Make it yours: your own accent colour, app text size, toolbar layout, quick ink colours, saved tool presets and notebook covers
- Core notebook works offline; Android 8.0 and newer

## Get Folio

Download the latest APK from [GitHub Releases](https://github.com/flandyw/folio/releases/latest).

Experimental builds are available from the [Folio server](https://folio.flandolf.me/releases/).
In **Settings → Account & updates**, enable **Experimental builds** to check that
server; leave it off for stable GitHub releases. Server setup and publishing are
documented in [release-server/README.md](release-server/README.md).

## Development

Folio has one Android module, `:app`. Development requires JDK 25 and Android
SDK 36. Source is under `app/src/main/java/com/folio/notes/`, organized by
feature. Start with [AGENTS.md](AGENTS.md) for the source map, conventions,
storage invariants and checks.

Set `JAVA_HOME` to JDK 25 and `ANDROID_HOME` to the Android SDK when they are
not already configured. The Gradle wrapper downloads the project's Gradle
version. Local builds use placeholder Focal settings unless
`FOCAL_SUPABASE_URL` and `FOCAL_SUPABASE_PUBLISHABLE_KEY` are supplied through
the environment, Gradle properties or untracked `local.properties`; account
and sync features need a real project. Use only a publishable or anon client
key, never a service key. The app packages ARM native libraries, so use an ARM
device or emulator.

The canonical local check is `./build.sh -p`: it builds a signed experimental
APK and **publishes it to the Folio server without prompting**. It requires the
configured signing and server credentials. The build script expects
`.signing/folio-release.p12`, `.signing/password` and Android build-tools 36.0.0
(or `FOLIO_BUILD_TOOLS` pointing to the tools). Use `./build.sh -n` to build
the signed APK without publishing. Both reserve an experimental version number.

Run JVM tests with `./gradlew :app:testDebugUnitTest`. Focused smoke checks live
in `tools/`; many need `./gradlew :app:prepareBackupSmoke` first. The relevant
feature guide or AGENTS.md identifies which checks to run. The offline KaTeX
check is `node tools/katex-smoke.cjs`. Smoke scripts require Node.js; most also
use the Kotlin compiler cached by a Gradle build.

On Windows, `.\build.ps1 -Tasks ':app:assembleDebug'` builds a debug APK without
lint. Do not run `:app:lintDebug` as a routine check: it takes substantially
longer. Keep `app/build` and the Gradle caches for repeated builds; avoid `clean`
unless diagnosing a build problem. CI publishes signed R8 releases on pushes to
`main` or manual dispatch.

### Engineering guides

- [Application architecture](docs/application-architecture.md): state, commands, readers and the writer
- [Document transactions](docs/document-transactions.md): grouped edits, recovery and required device checks
- [Continuous editor](docs/continuous-editor.md), [page navigation](docs/page-navigation.md) and [split view](docs/split-view.md)
- [Pen feel](docs/pen-feel.md), [palm rejection](docs/palm-rejection.md), [shape assist](docs/shape-assist.md) and [writing follow](docs/writing-follow.md)
- [Focal sync](docs/focal-sync.md), [study sessions](docs/study-sessions.md) and [planner](docs/planner.md)
- [Release server](release-server/README.md): publishing and server setup

Other feature and device-check guides are in [docs/](docs/). Storage-format and
journal changes require the device round-trip described in the document
transactions guide before release.
