# Data, accounts and settings

[Wiki home](README.md) · [Notebooks](notebooks.md) · [Developer guide](development.md)

## Local storage, export and backup

The core notebook works offline. Notebooks are stored on the device with page snapshots, journals, images and imported PDFs. Saving is queued; failed writes remain available for retry. Export pages as PDF or PNG for sharing, or export a `.folio` notebook to retain editable content and undo history.

Portable `.folio` and library backups use native backup manifests and include the objects needed for restore. Automatic restore points share stored objects and retain two points. Older backup formats remain importable. A backup waits for the document writer and pending recovery before staging content. Trash is excluded from library backups, and the music collection is separate: see [Trash](../trash.md) and [Music](music.md).

### Choose an export or backup

| What you need | Action | Result |
| --- | --- | --- |
| A readable copy | In the notebook export menu, choose **Save as PDF**, **Save page as image**, or **Export specific pages** | PDF or PNG output for viewing and sharing |
| One editable notebook | In the notebook export menu, choose **Save as Folio backup** | A `.folio` file with its pages, images, PDF and undo history; import it from Library → **Create or import** → **Import Folio backup** |
| A portable library copy | In Settings → **Backup & restore**, choose **Save library backup** | A self-contained file containing included notebooks and their history; **Restore library backup** adds copies beside existing notebooks |
| Automatic library restore points | In Settings → **Backup & restore**, choose **Choose backup folder** | Folio checks for saved changes and keeps the latest two restore points, sharing unchanged data |

For automatic restore, use **Restore from backup folder** and choose the same folder. Keep the restore points together with the **Folio backup data** folder. Backup exclusions apply to automatic and portable library backups; a single-notebook `.folio` export still includes that notebook. Check exclusions before relying on a library backup.

## Focal account and sync

Study, Mistakes, Progress and Planner use the shared Focal account and synchronization services. Some operations are available offline and replay when connected; operations such as merging synced study sessions need a connection. A temporary connection failure and a rejected session are treated differently. Signing in again can restore a revoked session; local queues and caches have their own lifecycle. See [Focal sync](../focal-sync.md) and the relevant feature guide for limits and recovery.

## Experimental ChatGPT connection

Settings → Account & updates → Page review & account opens the account controls. The sparkle button in a notebook opens page review. After connecting, choose a review mode, add optional context and select up to ten consecutive pages. A preview alone sends nothing; choosing **Review pages** sends the selected rendered pages. Review suggestions can be edited and selectively added as ordinary sticky notes in one undo step.

This is experimental and requires a suitable connected account and network access. Review output is not automatically applied. Finite-page review includes the PDF background, visible layers and images; outside-page stickies are excluded. Large infinite canvases are downscaled and may be hard to read. Credentials are stored separately from notebooks and backups. See [ChatGPT connection](../chatgpt.md) for the connection, privacy, availability and verification details.

## Settings and updates

Settings includes appearance, accent colour, sidebar destinations, text size, toolbar and cover choices, writing and stylus behaviour, paper, timers, account and updates. Search settings by name. Ink colours are independent of the interface accent. The first pen contact turns off finger drawing unless the user has explicitly chosen that setting.

Stable releases are available from GitHub Releases. Experimental builds come from the Folio server when **Experimental builds** is enabled under Account & updates. Debug and release builds use different signing keys; export data before uninstalling a debug build to install a release. Build and release details are in the [project README](../../README.md) and [AGENTS.md](../../AGENTS.md).
