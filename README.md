<p align="center">
  <img src="docs/images/folio-banner.png" alt="An open notebook with handwritten notes and a stylus" width="100%">
</p>

<h1 align="center">Folio</h1>

<p align="center">A quiet, local-first notebook for handwriting, sketches, maths, and PDFs.</p>

<p align="center">
  <a href="https://github.com/flandyw/folio/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/flandyw/folio/release.yml?branch=main&label=checks" alt="Android checks"></a>
  <a href="https://github.com/flandyw/folio/releases/latest"><img src="https://img.shields.io/github/v/release/flandyw/folio?display_name=tag" alt="Latest release"></a>
  <a href="https://github.com/flandyw/folio/releases"><img src="https://img.shields.io/github/downloads/flandyw/folio/total" alt="GitHub downloads"></a>
  <a href="https://github.com/flandyw/folio"><img src="https://img.shields.io/github/stars/flandyw/folio" alt="GitHub stars"></a>
</p>

Folio is a native Android notebook built with Kotlin and Jetpack Compose. Write with a stylus or finger, keep pages organized, and take your notebooks with you in `.folio` backups.

## Features

- Pressure-sensitive handwriting, drawing tools, and undo/redo
- [Writing follow](docs/writing-follow.md) that measures your lines from the ink, holds still for corrections, and offers one-tap or automatic line return, plus a pinned peek view (hold to glance, tap to keep open)
- Typed notes, photos, and PDF annotation
- [Long responses](docs/long-responses.md): pinned questions, plans, linked drafts and marked copies, side-by-side comparison, and feedback actions
- Ruled, dotted, grid, maths, and infinite-canvas pages
- Graph tool: centred or corner axes, optional grid, ticks, unit numbers and x/y labels
- Page bookmarks, search, and notebook organization
- Dedicated [study timer](docs/study-timer.md): standalone or notebook-linked sessions, reflections, history, and Focal sync
- Reference view: a read-only PDF pane with contents, text search, page jump, zoom/fit and link following
- Export pages as PDF or PNG; save compact `.folio` backups with undo history, or use incremental automatic library backups ([details](docs/backups.md))
- Make it yours: your own accent colour, app text size, toolbar layout, quick ink colours, saved tool presets and notebook covers
- Core notebook works offline; Android 8.0 and newer

## Get Folio

Download the latest APK from [GitHub Releases](https://github.com/flandyw/folio/releases/latest).

Experimental builds are available from the [Folio server](https://folio.flandolf.me/releases/).
In **Settings → Account & updates**, enable **Experimental builds** to check that
server; leave it off for stable GitHub releases. Server setup and publishing are
documented in [release-server/README.md](release-server/README.md).

## Build

Requires JDK 17 and Android SDK 36.

```sh
./gradlew :app:assembleDebug
```

Run the full CI checks with:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

On the configured VPS, run `bash ./build.sh` to make a signed experimental build,
then answer `y` to publish it. Each run gets a new version code even without a new
commit (`commitCount × 10000 + localBuildNumber`) and a name such as `2.1.7-exp.2`.
Stable Gradle/CI builds use `commitCount × 10000`; the next stable commit therefore
supersedes the preceding experimental builds.
