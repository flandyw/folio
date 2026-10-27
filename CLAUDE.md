# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

`AGENTS.md` (imported above) is the source of truth for architecture, the storage format and its invariants, conventions and release mechanics. Keep it updated rather than duplicating its content here.

## Commands

- Build (canonical check): `./build.sh -p` — signed experimental build, published to the Folio server without prompting. Do not run `:app:lintDebug` (takes far too long) unless explicitly asked, even where `docs/` mention it.
- JVM unit tests live in `app/src/test`; run `./gradlew :app:testDebugUnitTest` or use Gradle's `--tests` option for a focused test. There are no instrumented tests. Pure logic is also exercised by Node smoke scripts in `tools/`, many of which need `./gradlew :app:prepareBackupSmoke` first:
  - `node tools/backup-smoke.cjs`
  - `node tools/marking-smoke.cjs`
  - `node tools/update-smoke.cjs [server-manifest.json]`
  - `node tools/writing-follow-smoke.cjs`
  - `node tools/graph-smoke.cjs`
  - `node tools/pen-smoothing-smoke.cjs`
  - `node tools/handwriting-smoke.cjs`
  - `node tools/katex-smoke.cjs` (offline; no Gradle step needed)
- `python3 tools/build-version-smoke.py` checks the version/build-number mapping.
- `./build.sh` builds a signed *experimental* APK (needs `.signing/` keystore); `-p` publishes, `-n` builds only, `-r` enables R8 (off by default). Every run consumes a reserved version number, even if the build fails.

## Working notes

- CI (`.github/workflows/release.yml`) only publishes signed releases; it does not run checks, so run `./build.sh -p` locally before finishing.
- Features have design docs in `docs/` (`writing-follow.md`, `document-transactions.md`, `focal-sync.md`, `pen-feel.md`, `split-view.md`, …). Read the relevant one before changing that area.
- Automated checks do not replace the required on-device round-trip for storage-format or journal/snapshot changes; see `docs/document-transactions.md` (*Required device round-trip*).
