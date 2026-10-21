# UX improvements implementation

The IDs below match the 50 proposals in the UX review. This is a source-based implementation; there was no attached Android device for stylus, touch, TalkBack or visual verification. Existing native side-button erasing was retained; existing pinned presets, page naming and bookmarked export were extended rather than duplicated.

Notebook, snapshot and journal formats are unchanged. New preferences are in `AppPrefs`; handwriting recognition uses a disposable revision-keyed cache. Snippets copy their own image assets so deleting their original notebook does not break them.

| ID | Delivered behaviour | Main implementation |
| --- | --- | --- |
| 1 | Ctrl+K command search, arrow navigation, Enter to run and disabled-action reasons | `UxShortcuts.kt`, `EditorWorkspaceContent.kt` |
| 2 | Canvas-only tool letters with editable, validated bindings | `EditorKeyBindings.kt`, `UxSettings.kt` |
| 3 | Copy, cut, paste, duplicate, delete and select-all keys, including complete spanning selections | `EditorWorkspaceContent.kt` |
| 4 | Ctrl+Tab / Ctrl+Shift+Tab notebook switching | `EditorWorkspaceContent.kt` |
| 5 | Page Up/Down and Ctrl+Home/End for ordinary notebooks | `EditorWorkspaceContent.kt` |
| 6 | New notebook, insert-after and duplicate-page keys | `EditorWorkspaceContent.kt` |
| 7 | Hold Space for a temporary hand without replacing the selected tool or clearing its selection | `EditorWorkspaceContent.kt` |
| 8 | Shortcut help includes writing navigation, new commands and the user's current tool bindings | `EditorSearchPanels.kt`, `UxShortcuts.kt` |
| 9 | Dismissible editor/library hints with a global preference; sharing text respects its Off default | `EditorUxPanels.kt`, `LibraryUx.kt` |
| 10 | Settings results scroll to and highlight the actual control or relevant group | `SettingsScreen.kt`, `SettingsComponents.kt`, `UxSettings.kt` |
| 11 | Searchable gesture guide available through the command palette and stylus settings | `UxShortcuts.kt` |
| 12 | Disposable pencil-shortcut practice pad, supporting real pencil events and a double-tap simulation | `UxSettings.kt` |
| 13 | Command reasons, import state explanations, layer restrictions and study-edit restrictions | `UxShortcuts.kt`, `EditorUxPanels.kt`, `StudyTimeline.kt` |
| 14 | Optional names beside toolbar icons | `ExpressiveComponents.kt`, `UxSettings.kt` |
| 15 | Larger actual toolbar, dock and selection targets with matching width calculations | `InkToolbar.kt`, `FolioToolbar.kt`, `SelectionContextMenu.kt` |
| 16 | Named presets can be applied and pinned directly from tool options; existing toolbar pins retained | `ToolOptions.kt`, `InkToolbar.kt` |
| 17 | Opt-in notebook-specific tool settings, last tool and pinned toolbar layouts; global setup remains available | `EditorScreen.kt`, `EditorWorkspaceContent.kt` |
| 18 | Existing public native primary stylus-button erasing retained and documented; normal tool returns on the next stroke after release | `InkView.kt`, `UxShortcuts.kt` |
| 19 | Undo notices identify ink/text/picture/layer actions and named page-organisation operations | `DocumentEngine.kt`, `FolioViewModel.kt`, `EditorUxPanels.kt` |
| 20 | Readable ordered undo preview with affected pages and Undo/Redo controls | `EditorUxPanels.kt`, `FolioViewModel.kt` |
| 21 | Alignment and distribution preserve stacking and layers; spanning selections process each page in one revision-checked group | `SelectionPlacement.kt`, `EditorUxPanels.kt`, `EditorDocumentSelection.kt` |
| 22 | Optional movement snapping to spacing, margins, centres and nearby visible objects; Shift bypasses it | `ObjectSnapping.kt`, `InkView.kt`, `DocumentInputCoordinator.kt` |
| 23 | Named cross-notebook snippets containing ink, text and independent pictures | `EditorUxPanels.kt`, `EditorSelectionController.kt`, `FolioViewModel.kt` |
| 24 | Original-ink preview plus editable recognised text before one revision-checked replacement, including spanning selections and sticky ink | `InkConversionPreview.kt`, `DocumentInputCoordinator.kt` |
| 25 | Layer controls remain in the existing toolbar popover; blocked-layer feedback retained | `InkViewHost.kt` |
| 26 | One search combines PDF text, typed notes and optional handwriting; results identify their source | `EditorSearchPanels.kt` |
| 27 | Opt-in on-device handwriting search, revision-keyed cache and indexing progress/incomplete-state feedback | `NotebookSearchIndex.kt`, `EditorSearchPanels.kt` |
| 28 | Previous/next matching-page controls preserve the query and reopen results | `SearchMatchBar.kt`, `EditorSession.kt` |
| 29 | Back/forward remembers page identity, column anchors and camera state; deleted pages are skipped | `EditorViewportCoordinator.kt`, `EditorWorkspaceContent.kt` |
| 30 | Batch page selection, duplication, deletion, export and block movement; move is one undo step | `BatchPagesPanel.kt`, `PageBatchRules.kt`, `DocumentEngine.kt` |
| 31 | Page-list reorder gains edge autoscroll and an insertion indicator | `EditorPageBrowser.kt` |
| 32 | Named pages and embedded PDF labels work in jump/search; `label:3` distinguishes printed labels from physical page numbers | `NotebookPageLabels.kt`, `NoteRepository.kt`, `EditorPageBrowser.kt` |
| 33 | Five-notebook Continue working strip across library scopes, preserving the main browser's filters and order | `LibraryUx.kt`, `LibraryScreen.kt` |
| 34 | Named saved library views preserve query, place, folder, tag, type, sort and exam filters | `LibraryUx.kt` |
| 35 | Reversible notebook trash actions expose Undo, waiting for an in-progress move to finish | `LibraryScreen.kt` |
| 36 | Configurable left/right notebook swipes: favourite, trash, tags, move or PDF sharing | `LibraryScreen.kt`, `UxSettings.kt` |
| 37 | Drag-to-select ranges in Select mode, including autoscroll, while retaining explicit selection controls | `LibraryUx.kt` |
| 38 | Create another like this keeps geometry, paper, cover, folder and descriptive tags, with no source content or completed status | `NotebookTemplates.kt`, `NotebookWorkflowService.kt` |
| 39 | Apply subject, year, company and exam tags across a reviewed PDF batch | `PdfImportPanel.kt` |
| 40 | Content-hash duplicate checks offer Open existing or explicitly allow another copy | `PdfImportPanel.kt`, `NoteRepository.kt` |
| 41 | Flagged and annotated export selections extend current/bookmarked/odd/even selections | `ExportPagesDialog.kt` |
| 42 | Preview a real sample through the selected PDF/PNG exporter and quality mode | `ExportPreview.kt` |
| 43 | Named workspace pairs retain page positions, zoom, order, link state, offset and split; unavailable documents are identified | `SavedWorkspaces.kt`, `WorkspaceManager.kt` |
| 44 | Per-pair linked-page offsets, preserving existing PDF matching at zero | `SavedWorkspaces.kt`, `WorkspaceManager.kt` |
| 45 | Expand either pane and restore the split; hidden panes retain their measurement/camera; Ctrl+backslash toggles the editor | `WorkspaceScreen.kt` |
| 46 | Configurable review confirmation; default retains the deliberate double activation | `MistakeReviewScreen.kt`, `SettingsPages.kt` |
| 47 | Review keys: Space reveal, S skip, 1–4 rate; text-entry, busy and failed-save guards apply | `MistakeReviewScreen.kt` |
| 48 | Session details explain timer, pending edit and sync restrictions beside the affected block | `StudyTimeline.kt` |
| 49 | Persistent save-failure indicator with reason and retry in the main editor | `EditorUxPanels.kt` |
| 50 | Protection panel separates device saves, Focal study/mistake sync and library backup; includes retry, backup-now and direct backup/restore settings | `EditorUxPanels.kt`, `FolioApp.kt` |

## Boundaries

- Search navigation moves between matching **pages**, not individual word rectangles. OCR may misread handwriting; unindexed and unreadable states are shown.
- Alignment treats each selected stroke, text box and picture as an item. Spanning selections align within each page and commit together.
- Batch page moves undo together. Batch duplicates and deletions use the existing per-page structure undo steps.
- Snippets, saved views, custom tool settings and saved workspaces are device-local and outside library backups; the protection panel states this.
- PDF labels can collide with physical page numbers or names. Use `label:` or `name:` to disambiguate; ambiguous names are refused.
- Supported stylus side-button erasing is the existing public Android input path, not a new vendor integration. Releasing the button changes the next stroke, not an in-progress contact.

## Verification

Run the canonical signed experimental build with `./build.sh -p` (publishes to Folio's server), unit tests with `./gradlew :app:testDebugUnitTest`, and prepare the JVM smoke runner with `./gradlew :app:prepareBackupSmoke`. Focused smoke checks: backup, page navigation, palm rejection and writing follow. No lint or plain assembleDebug check is required.

The new JVM tests cover invalid/colliding key bindings, physical/named/PDF label resolution, independent equal stroke instances, preservation of layers/stacking/untouched content, saved exam filters, blank templates, snapping and randomized page-block order preservation.

## Device checks still required

1. On a tablet keyboard, enter text in a text box, sticky note, search, command palette and settings field; confirm tool letters and document commands do not intercept text entry. Exercise key repeat and window focus loss while holding Space.
2. Select ink, pictures and sticky notes on one page and across a seam. Copy/cut/paste, align, snap with/without Shift, save/reinsert a snippet and undo. Confirm complete spanning selections and locked/hidden layers remain protected.
3. Preview/correct handwriting on sticky notes and spanning ink. Change the document before applying a prepared conversion and confirm revision rejection leaves original ink intact.
4. Search a mixed notebook containing PDF pages, typed notes and handwriting; check indexing progress, result-source labels, matching-page navigation and exact Back/Forward restoration after reorder/delete.
5. Reorder through a long page list, drag-select library cards through an edge, and batch-move non-contiguous pages. Confirm one undo restores a batch move.
6. Import duplicate PDFs and a metadata batch. Preview and open exported samples in both PDF modes and PNG. Check flagged/bookmarked/annotated selection against pages that were initially unloaded.
7. Restore a workspace while its editor is already active; test both finite pages and infinite canvases, a deleted saved page and a missing notebook. Expand each pane, rotate the tablet, then restore the split without losing position.
8. Turn on names and larger controls; check narrow split panes, portrait/landscape, large system fonts and TalkBack focus order. Touch targets require device measurement rather than a source-only accessibility claim.
9. Fail a device save and revoke backup-folder permission. Confirm visible recovery actions, accurate device/sync/backup distinctions, and that a notebook in Trash can be restored after its move completes.
10. Exercise review confirmation modes and all review keys; confirm busy, failed-save and text-entry guards. Verify study restrictions on unsynced timer sessions and pending block edits.

## Ten further opportunities

These are follow-on proposals, not changes included in this implementation.

1. **Links to notebook pages and regions, with backlinks.** Link a worked solution to its question and find every note referring to it.
2. **Pinned Android notebook shortcuts and widgets.** Open a specific notebook or its current page directly from the home screen.
3. **Reusable multi-page starter packs.** Extend blank setup reuse with user-designed page sequences, headings and starter diagrams.
4. **Portable workspace and snippet backup.** Export/import personal tool setups, saved views, snippets and named workspace pairs.
5. **Search and batch selection in Trash.** Recover selected notebooks by title, folder or deletion date without restoring everything.
6. **Selective recovery from a restore point.** Preview differences and recover one notebook or page without importing the entire library.
7. **Resumable VCAA downloads.** Extend batch import with a persistent queue, individual retry and resume after interrupted downloads.
8. **Accessible document text view.** Present typed notes, PDF text and indexed handwriting in a structured, scalable reading view for TalkBack and text selection.
9. **Editable notebook section outlines.** Group pages into named sections and navigate them from a collapsible contents tree.
10. **Music rehearsal loops.** Define an A–B page range or rehearsal-mark pair, then return to its start using a pedal or one visible action.

The permanent Actions / Back / Forward / History / save-status row has been removed. History and saving, sync and backup details are available through Ctrl+K; navigation shortcuts and the save-failure retry banner remain available.
