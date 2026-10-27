# Notebooks and the library

[Wiki home](README.md) · [Writing](writing.md) · [Data and backups](data-and-settings.md)

## Library

The Library is Folio's notebook browser. Create notebooks or import PDFs and `.folio` files, then organize notebooks with folders, tags, stars and covers. Search and browse by place, folder or tag. On wider screens the library has a sidebar and folder tiles; notebook drag and drop uses the same organization rules. The Files destination browses files on the device separately from Folio's notebook library.

Deleting a notebook moves it to **Trash**, where it stays until restored or permanently deleted. Restore returns it to its original folder if that folder still exists, otherwise to the library root. Removing a folder moves its notebooks to the parent. Trash is device-local and excluded from library backups; external tablet files have a separate permanent-delete flow. See [Trash](../trash.md).

## Pages and navigation

A notebook contains finite pages or infinite canvases. Paper options include plain, ruled, dots, grids and specialist guides. Pages can be added, inserted, duplicated, deleted, reordered, bookmarked and given paper or title changes. Use the page browser and thumbnails to move around a notebook; the continuous editor scrolls through finite pages. Infinite canvases pan and zoom freely.

Undo and redo include page content and structural actions. Content history is persisted, including grouped edits across pages; page structure history is kept for the current session. Page navigation, viewport anchors and device checks are described in [page navigation](../page-navigation.md) and [continuous editor](../continuous-editor.md).

A **peek view** pins a region of a page for reference while working elsewhere: hold to glance or tap to keep it open. The saved anchor belongs to the notebook.

## PDFs and reference material

Importing a PDF creates a PDF-backed notebook that can be written on with the normal editor. PDF text, links and outline support navigation and search where the source provides them. The PDF reference view provides a read-only pane with search, contents, page jump, fit/zoom and link following. Export pages as PDF or PNG, or use `.folio` for a portable notebook with editable content and history. See [PDF rendering](../pdf-rendering.md).

## Tabs and split view

Open notebooks in tabs; drag tabs sideways to reorder them. The open tabs and their order are remembered. Use the editor's split button or notebook menu to open a chooser beside the current document. Choose the same notebook, an open tab or a recent notebook. The layout stacks on narrower windows. Drag the divider to resize or to an edge to close a pane.

Both panes use the editor's document surface. A pen can write in either pane with shared ink tools; a pen using an editor-only tool such as lasso moves the toolbar's editor focus to that pane. Finger navigation does not switch focus. A split is parked when its host notebook is left and restored when that notebook is reopened. See [split view](../split-view.md) for shortcuts, details and device checks.

## For maintainers

`library/LibraryScreen.kt`, `LibraryBrowser.kt` and `LibraryOrganization.kt` own browsing; `library/files/` handles device files. `workspace/` owns tabs, split layout and saved workspaces. The editor route starts in `editor/EditorScreen.kt`; `storage/NotebookPdfs.kt` handles imported PDF data. The [application architecture](../application-architecture.md) explains the shared document engine.
