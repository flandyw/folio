# Explorer

Open **Explorer** from the Library’s quick actions or the tablet navigation rail. The existing shelf remains available, with the same notebooks, covers, and editor.

## Notebooks

**Folders** is a spatial view: the library root contains top-level folders and unfiled notebooks, and opening a folder shows its direct contents. Breadcrumbs and Android Back go up through the hierarchy. Searching a folder includes its descendants; **All notebooks**, **Favorites**, and tag filters search across the library. On wide tablets, collections and tags stay in a side panel; narrow windows use horizontally scrolling chips. Covers/list and sorting share the shelf’s saved preferences.

Create notebooks and subfolders in the current folder. Notebook menus and long-press selection support moving, tagging, favorites, and deletion; individual notebooks can also be renamed or duplicated. The existing shelf supports individual and bulk tags too. Move pickers display each destination’s parent path, so equally named folders in different branches remain distinguishable. A folder cannot be moved into itself or a descendant. Removing a folder promotes its notebooks and direct subfolders to its parent and keeps their contents.

User tags are separate from structured exam details. Tags are trimmed, limited to 40 characters, deduplicated without case sensitivity, and limited to 32 per notebook. Bulk tagging preserves mixed tags unless explicitly changed. Library searches also match these labels.

## Tablet files

**Connect folder** uses Android’s Storage Access Framework. Folio remembers up to 32 explicitly selected trees and their persisted access. The system controls which locations can be selected; for individual files in Downloads or restricted roots, the existing PDF/Folio file pickers remain available. Local storage, SD cards, and installed document providers can participate. No all-files permission is requested.

Browse subfolders with breadcrumbs, search the current folder, sort by name/date/size, and filter to PDF/Folio files. PDF imports enter the existing review panel, including its destination chooser; Folio archives use the existing notebook importer. Imported notebooks are copies. Other files open in their usual app, and sharing grants temporary read access to the selected content URI.

Document-provider flags determine which create, rename and delete actions are offered. Non-virtual files can be copied or moved: choose an action in the file menu, navigate to a writable destination, then paste. Copying streams on an IO worker with a bounded buffer; incomplete copies are removed where the provider permits it. Moves remove the original only after writing the complete copy. If source deletion fails, the copy remains and Folio explains that the original could not be removed. Folder copying/moving between device locations is not offered.

Document queries and mutations run off the UI thread. Loading, empty, read-only and unavailable-folder states are explicit. Revoked access offers Retry and Reconnect. Disconnecting removes a location from Explorer without deleting files or revoking a grant another Folio feature may still need.

## Persistence

`Folder.parentId` is optional (`parent` in `library.json` and backup manifests). `Notebook.tags` is an optional `tags` array in both notebook codecs. Missing fields mean a top-level folder and no tags; empty values are omitted. Existing version numbers and binary page/journal formats are unchanged. Native and legacy library backup readers preserve folder parents; restore remaps both folder IDs and parent references. Invalid backup hierarchies are rejected before installation. An older Folio can read the metadata but cannot preserve the new organization when rewriting it.

## Verification

Run the canonical `./build.sh -p`, plus `./gradlew :app:prepareBackupSmoke` and `node tools/backup-smoke.cjs`. The smoke runner covers hierarchy paths, duplicate IDs, missing parents, cycles, forbidden moves, restore ID remapping, native/legacy manifest round trips, absent-field defaults, tag normalization/limits, both notebook codecs, and tag search.

Device checks are still required; JVM checks do not exercise Compose layout, Android permissions or real document providers:

1. Open Explorer in portrait, landscape, split screen, dark mode and large text. Check responsive collections, keyboard resizing, scrolling, row targets, TalkBack labels, long-press selection, list/covers and Back behavior. Open a notebook, return, switch sources and rotate; confirm navigation/filter/scroll state remains usable.
2. Create three levels of folders, including the same name in different branches. Move a subtree, rename it, and remove an intermediate folder. Confirm descendants stay reachable, notebooks remain intact, illegal destinations are absent, and the shelf’s paths/pickers agree.
3. Assign individual and mixed bulk tags, search by multiple title/tag terms, remove tags, and exercise the 32-tag limit. Restart Folio and confirm tags and folder parents persist.
4. Export/import a `.folio`, export/restore a portable library, and create/restore automatic backup points with nested folders and tags. Restore twice alongside existing notes. Check fresh folder identities, correct parent/notebook references, labels, ink, PDFs, and undo/redo. Open an older notebook and backup with neither new field.
5. Connect local and SD/cloud-provider folders. Restart and revisit them; revoke access externally and use Retry/Reconnect. Cancel the picker, disconnect a location, and verify originals remain and automatic-backup folder access still works.
6. Browse, search, sort and share files; import multiple PDFs together and a Folio archive. Confirm the review destination, editor contents and untouched originals. Check a read-only provider and virtual documents.
7. Create/rename/delete a disposable device folder and file. Copy a large file across folders/providers, move a disposable file, and check byte contents. Exercise same-folder copy, refused writes/deletes, interruption and source-deletion failure. Confirm unsuccessful copying keeps the original and move failures report the retained copy.
