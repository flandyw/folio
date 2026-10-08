# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

`AGENTS.md` (imported above) is the source of truth for architecture, the storage format and its invariants, conventions and release mechanics. Keep it updated rather than duplicating its content here.

## Commands

- Build (canonical check): `./build.sh -p` — signed experimental build, published to the Folio server without prompting. Do not run `:app:lintDebug` (takes far too long) unless explicitly asked, even where `docs/` mention it.
- There is no unit/instrumented test suite and no single-test command. Pure logic is exercised by Node smoke scripts in `tools/`, which compile the pure Kotlin files against the Gradle cache. Run `./gradlew :app:assembleDebug :app:prepareBackupSmoke` first, then:
  - `node tools/backup-smoke.cjs`
  - `node tools/marking-smoke.cjs`
  - `node tools/update-smoke.cjs [server-manifest.json]`
  - `node tools/writing-follow-smoke.cjs`
  - `node tools/graph-smoke.cjs`
  - `node tools/katex-smoke.cjs` (offline; no Gradle step needed)
- `python3 tools/build-version-smoke.py` checks the version/build-number mapping.
- `./build.sh` builds a signed *experimental* APK (needs `.signing/` keystore); `-p` publishes, `-n` builds only, `-r` enables R8 (off by default). Every run consumes a reserved version number, even if the build fails.

## Working notes

- CI (`.github/workflows/release.yml`) only publishes signed releases; it does not run checks, so run `./build.sh -p` locally before finishing.
- Features have design docs in `docs/` (`marking.md`, `writing-follow.md`, `backups.md`, `examtrack.md`, `sync.md`, …). Read the relevant one before changing that area.
- Storage-format or journal/snapshot changes can't be verified by tests here; say so explicitly and point to the manual on-device check in `docs/examtrack.md` / `docs/sync.md`.
