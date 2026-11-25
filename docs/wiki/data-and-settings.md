# Data, accounts, and settings

[Wiki home](README.md) · [Notebooks](notebooks.md) · [Developer guide](development.md)

## Local storage, export, and backup

Core notebooks work completely offline. Notebooks are stored locally as page snapshots, journals, images, and imported PDFs. Writes are queued, and failed saves stay available for retry.

Portable `.folio` exports and library backups use native backup manifests with all objects required for restore. Automatic restore points share unchanged objects and retain the latest two points; older backup formats remain importable. Backups wait for the document writer and pending recovery before staging files. **Trash** is excluded from library backups, and **Music** is stored separately; see [Trash](../trash.md) and [Music](music.md).

### Choosing an export or backup

| Goal | Action | Result |
| --- | --- | --- |
| Share a readable copy | Notebook export menu → **Save as PDF**, **Save page as image**, or **Export specific pages** | PDF or PNG for viewing and sharing |
| Back up one editable notebook | Notebook export menu → **Save as Folio backup** | Portable `.folio` file with pages, images, PDF, and undo history; re-import via **Library → Create or import → Import Folio backup** |
| Back up the library once | **Settings → Backup & restore → Save library backup** | Self-contained archive of included notebooks and history; **Restore library backup** adds copies alongside existing notebooks |
| Keep automatic restore points | **Settings → Backup & restore → Choose backup folder** | Checks for saved changes and retains the latest two restore points, deduplicating unchanged data |

To restore from automatic backups, choose **Restore from backup folder** and pick the same folder. Keep restore points alongside the **Folio backup data** folder. Backup exclusions apply to both automatic and portable library backups (single-notebook `.folio` exports still include that notebook), so review exclusions before relying on a library backup.

## Focal account and sync

**Study**, **Mistakes**, **Progress**, and **Planner** share one Focal account and sync layer. Most edits work offline and replay once connected, though actions such as merging synced study sessions require connectivity.

Folio distinguishes temporary network failures from rejected sessions. Signing in again recovers a revoked session; local queues and caches have separate lifecycles. See [Focal sync](../focal-sync.md) for limits and recovery steps.

## Experimental ChatGPT connection

Open **Settings → AI** (or tap the sparkle button inside a notebook) to connect an account and run page review. Choose a review mode, add optional context, and pick up to ten consecutive pages. Previewing sends nothing; tapping **Review pages** sends the rendered pages. Suggestions can be edited and added selectively as ordinary sticky notes in a single undo step.

- Requires a supported connected account and network access; output is never applied automatically.
- Finite-page reviews include the PDF background, visible layers, and images, excluding off-page sticky notes. Large infinite canvases are downscaled and may be harder to read.
- Credentials are stored separately from notebooks and backups. See [ChatGPT connection](../chatgpt.md) for privacy, availability, and verification details.

## Settings and updates

Settings covers appearance, accent colour, sidebar destinations, text size, toolbar and cover customisation, writing and stylus behaviour, paper, timers, AI, and accounts. Use the search bar to jump to any setting. Ink colours never change with the interface accent, and the first stylus contact turns off finger drawing unless you have explicitly configured it.

- **Stable releases:** Published on GitHub Releases.
- **Experimental builds:** Turn on **Experimental builds** in **Settings → Account & updates** to check the Folio server.
- **Signing keys:** Debug and release builds use different keys; export your notebooks before uninstalling a debug build to install a release. See the [project README](../../README.md) and [AGENTS.md](../../AGENTS.md) for build and release details.
