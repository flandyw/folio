# Folio wiki

Folio is a local-first Android notebook for handwriting, PDFs, study, and sheet music. This wiki documents what is currently shipped in the app and points maintainers to the corresponding code and engineering guides. Screen layouts and available controls adapt to window size, account state, and settings.

## Guides

- [Getting started](getting-started.md): create or import a notebook, write, and save a backup.
- [Notebooks and the library](notebooks.md): organise notebooks, pages, PDFs, tabs, split view, and Trash.
- [Writing and editing](writing.md): ink tools, selection, shapes, text, sticky notes, layers, and writing follow.
- [Gestures and shortcuts](shortcuts.md): keyboard, touch, and stylus controls.
- [Study and exams](study.md): VCAA papers, marking, mistake review, study sessions, progress, and planner.
- [Music](music.md): the separate sheet-music library, reader, and annotations.
- [Data, accounts, and settings](data-and-settings.md): backups, Focal sync, privacy, ChatGPT review, appearance, and updates.
- [Developer guide](development.md): architecture, source map, verification, persistence, and release boundaries.

For setup and build commands, see the [project README](../../README.md). [AGENTS.md](../../AGENTS.md) records repository conventions and storage invariants. Detailed behaviour, implementation notes, and device checklists live in `docs/`; update both the wiki and the relevant guide when changing a feature. Proposals and historical reports in `docs/` do not imply that every idea in them shipped.

## View locally

With Bun 1.4 or newer installed, run from the repository root:

```sh
bun tools/wiki-server.ts
```

Open <http://localhost:3000> (or set `PORT=3001` to change the port). The server listens on localhost and reads Markdown from disk on each request, so refresh after editing. Stop it with `Ctrl+C`. Run `bun test tools/wiki-server.test.ts` to test the server.

To share the wiki temporarily, install `cloudflared` and run:

```sh
bun tools/wiki-tunnel.ts
```

This starts both the wiki server and a Cloudflare quick tunnel (set `PORT=3001` if 3000 is busy) and prints a public URL. Anyone with that URL can read the served docs while the tunnel is running; press `Ctrl+C` to stop both. Quick tunnels have no uptime guarantee.
