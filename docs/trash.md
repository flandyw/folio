# Trash

Deleting a notebook (including via multi-select) moves the entire notebook directory into **Trash**. Open Trash from the Library heading or the tablet navigation rail. You can restore individual notebooks or choose **Restore all**; **Delete forever** and **Empty Trash** require confirmation.

## Rules

- Trashed notebooks stay indefinitely until restored or permanently deleted.
- Restoring returns a notebook to its original folder if it still exists, or to the Library root otherwise. Removing a folder moves its notebooks to the parent folder instead of trashing them.
- External tablet files keep their separate permanent-delete flow.
- Trash is device-local and excluded from library backups, workspace pickers, Study, and Progress.
- Music score cleanup and automatic removal of empty mistake-practice notebooks bypass Trash.

## Storage mechanics

Complete notebook directories move atomically from `files/notebooks/<id>` to `files/trash/notebooks/<id>`, with `trashed-at` recording the deletion timestamp.

Before moving, the serialised document writer drains earlier edits and checkpoints the final notebook index, preserving PDFs, images, page journals, undo/redo, and grouped history with no format change. Pending compaction references are cleared so housekeeping cannot recreate a moved notebook, and failed moves remain in the writer's **Retry save** queue.

Permanent deletion detaches the directory under `.purge-<uuid>` before removal; if interrupted, cleanup resumes the next time Trash loads.

## Verification

- **Automated:** `./gradlew :app:testDebugUnitTest --tests '*NotebookTrashTest'`
- **On device:**
  - Write on a PDF notebook and a notebook with grouped edits, delete both, restart Folio, restore them, and confirm pages, ink, images, and undo/redo survive.
  - Test bulk delete/restore, restoring after removing the original folder, and confirmed permanent deletion.
  - Confirm active-library backups omit Trash and Trash stays accessible in portrait and landscape.
