# CLAUDE.md

Guidance for Claude Code (`claude.ai/code`) working in this repository.

@AGENTS.md

`AGENTS.md` (imported above) is the source of truth for architecture, storage invariants, conventions, and release mechanics. Update it directly rather than duplicating details here.

## Commands

- **Canonical build check:** `./build.sh -p`: builds a signed experimental APK and publishes it to the Folio server without prompting. Do not run `:app:lintDebug` unless explicitly asked, even where `docs/` mention it.
- **Experimental build options:** `./build.sh` requires the `.signing/` keystore. Pass `-p` to publish, `-n` to build only, and `-r` to enable R8 (off by default). Every run reserves a version number, even if the build fails.
- **JVM unit tests:** `./gradlew :app:testDebugUnitTest` (use `--tests <Class>` for a focused run). Tests live in `app/src/test`; there are no instrumented tests.
- **Node smoke scripts:** Pure logic is exercised by scripts in `tools/`. Most require `./gradlew :app:prepareBackupSmoke` first:
  - `node tools/backup-smoke.cjs`
  - `node tools/marking-smoke.cjs`
  - `node tools/update-smoke.cjs [server-manifest.json]`
  - `node tools/writing-follow-smoke.cjs`
  - `node tools/graph-smoke.cjs`
  - `node tools/pen-smoothing-smoke.cjs`
  - `node tools/handwriting-smoke.cjs`
  - `node tools/katex-smoke.cjs` (offline; no Gradle step required)
- **Version mapping check:** `python3 tools/build-version-smoke.py`.

## Working notes

- CI (`.github/workflows/release.yml`) only publishes signed releases and does not run verification checks; run `./build.sh -p` locally before finishing.
- Design guides live in `docs/` (`writing-follow.md`, `document-transactions.md`, `focal-sync.md`, `pen-feel.md`, `split-view.md`, and others). Read the relevant guide before changing an area.
- Automated checks do not replace the required on-device round-trip for storage-format or journal/snapshot changes; see `docs/document-transactions.md` (*Required device round-trip*).
