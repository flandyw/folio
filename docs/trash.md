
## Trash

Notebook deletion (including multi-select) moves the complete notebook into **Trash**. Open Trash
from the Library heading or the tablet navigation rail. Restore individual notebooks or choose
**Restore all**; **Delete forever** and **Empty Trash** require confirmation. Items stay indefinitely.
Restoration keeps the original folder if it still exists, otherwise the notebook returns to the
Library root. Removing a folder still keeps its notebooks and moves them to the parent folder.
Tablet files are original external files, so their existing permanent-delete flow is unchanged.

Trash is device-local, outside library backups, workspace pickers, study and progress. Complete
notebook directories move atomically from `files/notebooks/<id>` to `files/trash/notebooks/<id>`;
`trashed-at` records the deletion time. The serialized document writer drains earlier edits and
checkpoints the final notebook index before the move, preserving PDFs, images, page journals,
undo/redo and grouped history without a notebook format change. Pending compaction references are
cleared so housekeeping cannot recreate a moved notebook. Failed moves remain in the writer's
Retry save queue. Permanent deletion detaches the directory under `.purge-<uuid>` before cleanup,
which resumes when Trash is next loaded after an interruption. Music score cleanup and automatic
removal of empty mistake-practice notebooks bypass Trash.

Automated filesystem checks: `./gradlew :app:testDebugUnitTest --tests '*NotebookTrashTest'`.
On a device, write on a PDF and a notebook with grouped edits, delete them, restart Folio, restore
them, and verify pages, ink, images and Undo/Redo. Also test bulk deletion/restoration, restoring
after the original folder is removed, and confirmed permanent deletion. Verify that active-library
backups omit Trash and that Trash remains accessible in portrait and landscape.
