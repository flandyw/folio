# Library and Explorer

The Library and the old Explorer are one screen (`LibraryScreen.kt`, with the sidebar, place chips and folder tiles in `LibraryBrowser.kt`). Its scope rules are pure (`LibraryBrowse` in `LibraryOrganization.kt`) and checked by `node tools/backup-smoke.cjs`. **Tablet files** is a place inside the Library: the first sidebar row on tablets or the tablet icon in the heading on phones. It keeps the Library heading and sidebar visible, with Tablet files selected; choose a notebook place, folder or tag to return to notebooks. The Library rail item remains selected while browsing files.

## Notebooks

The shelf shows one of three **places** (All notebooks, Favorites, Unfiled), an open folder, or a tag:

- **All notebooks** lists every notebook, with folders in the sidebar on wide windows and as tiles above notebooks only on narrow windows. **Unfiled** is the spatial root: notebooks that are in no folder (plus top-level folder tiles on narrow windows). **Favorites** shows starred notebooks and no folders.
- **Opening a folder** shows its own notebooks; subfolders remain in the sidebar, or appear as tiles on narrow windows. Breadcrumbs and Android Back go up through the hierarchy, and the ⋮ beside the breadcrumbs (or on a tile) offers New folder inside, Rename, Move folder and Remove folder. Searching an open folder widens to its whole subtree and matches folder names too.
- **A tag** shows tagged notebooks across the library, whatever folder was open.
- Back steps out in order: drag → selection → search → filters → tag → folder → place → All notebooks.

On wide windows (≥ 840 dp) a side panel holds the places, the whole folder tree (expand/collapse with the chevrons; opening a folder unfolds its branch), tags with counts, and Tablet files. Narrow windows show the places and tags as a row of chips, and folders only as tiles. Covers/list, sorting, type and exam filters, Continue writing, Quick note/Canvas, the exam countdown and Retry save all come from the old shelf; quick actions and Continue writing show only on the unscoped All notebooks view.

The New menu (split button on phones, held rail button on tablets) creates a folder inside the open folder; a new notebook lands in the open folder. Notebook menus and long-press selection support moving, tagging, exam details, covers, backup exclusion, favorites and deletion. Move pickers display each destination’s parent path, so equally named folders in different branches remain distinguishable. A folder cannot be moved into itself or a descendant. Removing a folder promotes its notebooks and direct subfolders to its parent and keeps their contents.

User tags are separate from structured exam details. Tags are trimmed, limited to 40 characters, deduplicated without case sensitivity, and limited to 32 per notebook. Bulk tagging preserves mixed tags unless explicitly changed. Library searches also match these labels.

### Drag and drop

Hold a notebook, then move to lift it; a stationary hold still starts selection. Dragging a selected notebook lifts the whole visible selection, captured before navigation changes the view. A mouse can drag directly. Drags show a floating title/count, faded source cards, outlined destinations and a release hint. Existing Move and Tags menus remain available for keyboard and accessibility use. Picking a notebook for the workspace browses folders but never drags.

Drop on a folder tile, a sidebar tree row, an ancestor breadcrumb, Unfiled, Favorites, a tag, or the shelf's background. The background files into whatever the shelf is showing (the open folder, Unfiled, Favorites or the tag); All notebooks and search results have no background target. Folder tiles and sidebar tree rows can also be dragged to move their entire subtree; dropping a folder on Unfiled or the Library crumb moves it to the top level. Hover over a sidebar folder for 750 ms to expand its children without changing the notebook grid, then release over the desired folder to move the dragged notebooks. Folder tiles, breadcrumbs and Unfiled still open on hover while continuing the same drag. Edges scroll the shelf, the sidebar, the place/tag chip row and breadcrumbs. Favorites adds a star; a tag target adds that label while preserving other tags. Each successful drop offers **Undo**.

No-op, missing, self/descendant, duplicate-sibling-name and tag-limit destinations reject drops. Invalid foreground targets block background drops. Hover navigation remains possible through the original parent even when moving there would be a no-op. Ending outside a destination or pressing Back leaves the organization intact. Drops accept only Folio's internal notebook sessions. Source buttons use Material 3 Expressive tonal toggles with single-line labels; narrow windows and larger text scroll the row instead of wrapping **Tablet files**.

## Tablet files

**Enable all files access** opens Folio’s Android storage-access settings on Android 11+. Enable **Allow access to manage all files** once, then Explorer opens internal storage directly and lists mounted SD/USB volumes. The permission is checked again on resume, so granting or revoking it is reflected without restarting Folio. Android 8–10 uses legacy shared-storage runtime permissions (and the Android 10 legacy-storage flag). Android still protects other apps’ private directories.

**Connect folder** remains available for cloud/document providers and uses Android’s Storage Access Framework. Folio remembers up to 32 explicitly selected trees and their persisted access. The system controls which locations can be selected; for individual files in Downloads or restricted roots, the existing PDF/Folio file pickers remain available. Local storage, SD cards, and installed document providers can participate. The manifest declares `MANAGE_EXTERNAL_STORAGE`; the user must explicitly grant it in Android settings.

Browse subfolders with breadcrumbs, search the current folder, sort by name/date/size, and filter to PDF/Folio files. PDF imports enter the existing review panel, including its destination chooser; Folio archives use the existing notebook importer. Imported notebooks are copies. Other files open in their usual app, and sharing grants temporary read access to the selected content URI.

Dot-prefixed device folders are hidden by default, including from search results. **Show hidden folders** in the file toolbar reveals them for both direct storage and connected document providers; the choice is remembered across folders and restarts. When only hidden folders remain, the empty state explains how to reveal them.

Direct disk browsing uses `java.io.File` inside canonical shared-storage roots; path traversal and symlink escapes outside these roots are rejected. Copies reserve a fresh name instead of overwriting an existing file, and renames refuse existing destinations. Imports/open/share wrap selected disk files in read-granted FileProvider content URIs, so file URIs are never sent to another app. Storage roots cannot be renamed or deleted.

Document-provider flags determine which create, rename and delete actions are offered. Non-virtual files can be copied or moved: choose an action in the file menu, navigate to a writable destination, then paste. Copying streams on an IO worker with a bounded buffer; incomplete copies are removed where the provider permits it. Moves remove the original only after writing the complete copy. If source deletion fails, the copy remains and Folio explains that the original could not be removed. Folder copying/moving between device locations is not offered.

Document queries and mutations run off the UI thread. Loading, empty, read-only and unavailable-folder states are explicit. Revoked access offers Retry and Reconnect. Disconnecting removes a location from Explorer without deleting files or revoking a grant another Folio feature may still need.

## Persistence

`Folder.parentId` is optional (`parent` in `library.json` and backup manifests). `Notebook.tags` is an optional `tags` array in both notebook codecs. Missing fields mean a top-level folder and no tags; empty values are omitted. Existing version numbers and binary page/journal formats are unchanged. Native and legacy library backup readers preserve folder parents; restore remaps both folder IDs and parent references. Invalid backup hierarchies are rejected before installation. An older Folio can read the metadata but cannot preserve the new organization when rewriting it.

## Verification

Run the canonical `./build.sh -p -- :app:prepareBackupSmoke` and `node tools/backup-smoke.cjs`. The smoke runner covers hierarchy paths, duplicate IDs, missing parents, cycles, forbidden moves, restore ID remapping, native/legacy manifest round trips, absent-field defaults, tag normalization/limits, both notebook codecs, tag search, drag selections, mixed/no-op drops, stale IDs, folder collisions, hover navigation and edge scrolling.

Device checks are still required; JVM checks do not exercise Compose layout, Android permissions or real document providers:

1. Open the Library in portrait, landscape, split screen, dark mode and large text. Confirm wide notebook grids contain no folder tiles, Tablet files keeps the sidebar with the correct selection, and sidebar folders return to their notebooks. Check responsive collections, keyboard resizing, scrolling, row targets, TalkBack labels, long-press selection, list/covers and Back behavior. Open a notebook, return, switch sources and rotate; confirm navigation/filter/scroll state remains usable.
2. Create three levels of folders, including the same name in different branches. Move a subtree, rename it, and remove an intermediate folder. Confirm descendants stay reachable, notebooks remain intact, illegal destinations are absent, and the shelf’s paths/pickers agree.
3. Assign individual and mixed bulk tags, search by multiple title/tag terms, remove tags, and exercise the 32-tag limit. Restart Folio and confirm tags and folder parents persist.
4. Export/import a `.folio`, export/restore a portable library, and create/restore automatic backup points with nested folders and tags. Restore twice alongside existing notes. Check fresh folder identities, correct parent/notebook references, labels, ink, PDFs, and undo/redo. Open an older notebook and backup with neither new field.
5. Connect local and SD/cloud-provider folders. Restart and revisit them; revoke access externally and use Retry/Reconnect. Cancel the picker, disconnect a location, and verify originals remain and automatic-backup folder access still works.
6. Browse, search, sort and share files; import multiple PDFs together and a Folio archive. Confirm the review destination, editor contents and untouched originals. Check a read-only provider and virtual documents.
7. Create/rename/delete a disposable device folder and file. Copy a large file across folders/providers, move a disposable file, and check byte contents. Exercise same-folder copy, refused writes/deletes, interruption and source-deletion failure. Confirm unsuccessful copying keeps the original and move failures report the retained copy.

8. On Android 11+, deny all-files access, grant it from Explorer, and return: internal storage should open immediately. Browse Downloads, Documents, Android/media and mounted SD/USB volumes without individual folder grants. Repeat after restarting, backgrounding and revoking access. Android/data and other protected paths should produce a clear unavailable state. Confirm connected cloud folders still work when all-files access is off.
9. Repeat direct create/rename/copy/move/delete with disposable disk files, including duplicate names, disk-to-provider and provider-to-disk copies, and sharing/importing from both internal and removable storage. Unmount a volume while browsing and check retry. Test the legacy permission flow on Android 8–10 when available.
10. Hold without moving to select, scroll normally, then hold-and-move single notebooks and selected groups in list and cover modes. Drop into nested folders, onto breadcrumbs and empty folder backgrounds; hover through several levels and scroll long lists/chip rows at their edges. Move a folder subtree. Check Favorites, mixed tags and Undo after each action; reject self/descendant, duplicate names, full tags, same-folder and outside drops. Cancel with Back. Restart to confirm moved groups and subtrees persist. Repeat with stylus and mouse, and verify ordinary taps, long-press selection and TalkBack Move/Tags menus still work.
