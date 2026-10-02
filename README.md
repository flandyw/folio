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
- Writing follow that learns your pauses and line spacing, with Text/Maths modes, Pause/Resume, and one-tap line return
- Typed notes, photos, and PDF annotation
- Ruled, dotted, grid, maths, and infinite-canvas pages
- Graph tool: centred or corner axes, optional grid, ticks, unit numbers and x/y labels
- Page bookmarks, search, and notebook organization
- Reference view: a read-only PDF pane with contents, text search, page jump, zoom/fit and link following
- Export pages as PDF or PNG; back up notebooks as `.folio`
- Core notebook works offline; Android 8.0 and newer

## Get Folio

Download the latest APK from [GitHub Releases](https://github.com/flandyw/folio/releases/latest).

## Build

Requires JDK 17 and Android SDK 36.

```sh
./gradlew :app:assembleDebug
```

Run the full CI checks with:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```
