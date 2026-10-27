# Folio wiki

Folio is a local-first Android notebook for handwriting, documents, study and music. This wiki describes the features in the repository and points maintainers to their code and detailed design notes. It describes the current app, not every proposal in `docs/`. Availability and layout can vary with screen size, account state and settings.

## Start here

- [Getting started](getting-started.md): create or import a notebook, write and save a backup.
- [Notebooks and the library](notebooks.md): create and organize notebooks, pages, PDFs, tabs, split view and trash.
- [Writing and editing](writing.md): ink tools, selection, shapes, text, sticky notes, layers and writing follow.
- [Gestures and shortcuts](shortcuts.md): keyboard, touch and stylus controls.
- [Study and exams](study.md): VCAA papers, marking, mistake review, study sessions, progress and planner.
- [Music](music.md): the separate score library, reader and annotations.
- [Data, accounts and settings](data-and-settings.md): backups, sync, privacy, ChatGPT, appearance and updates.
- [Developer guide](development.md): architecture, source map, verification, persistence and release boundaries.

For setup and build commands, see the [project README](../../README.md). [AGENTS.md](../../AGENTS.md) records repository conventions and storage invariants. The linked feature guides in `docs/` contain deeper behaviour, implementation details and device checks; update those and this wiki when a feature changes. A proposal or a historical verification report is not evidence that every idea in it shipped.

## View locally

With Bun 1.4 or newer installed, run this from the repository root:

```sh
bun tools/wiki-server.ts
```

Open <http://localhost:3000>. Set `PORT` to use a different port, for example
`PORT=3001 bun tools/wiki-server.ts`. The server listens on localhost and reads
the Markdown on each request, so refresh after editing. Stop it with Ctrl+C.
Run `bun test tools/wiki-server.test.ts` to check the server.

To share temporarily, install `cloudflared` and run:

```sh
bun tools/wiki-tunnel.ts
```

This starts both the wiki and a Cloudflare quick tunnel. Use `PORT=3001` if the
default port is occupied. Cloudflare prints the public URL; Ctrl+C stops both
services. Anyone with the URL can read the served documentation while the
tunnel is running. Quick tunnels have no uptime guarantee.
