# Notebooks and the library

[Wiki home](README.md) · [Writing](writing.md) · [Data and backups](data-and-settings.md)

## Library

The Library is Folio's notebook browser. Create notebooks or import PDFs and `.folio` backups, then organise them with folders, tags, favourites, and custom covers. Browse by place, folder, or tag, or search across titles and content.

On wider screens, the Library displays a collapsible sidebar and folder tiles, and supports drag-and-drop organisation using the same rules. **Files** is a separate destination for browsing files on your device outside Folio's library.

Deleting a notebook moves it to **Trash**, where you can restore it or delete it permanently. Restoring returns the notebook to its original folder if it still exists, or to the library root otherwise. Removing a folder moves its notebooks up to the parent folder. Trash is device-local and excluded from library backups; external device files use a separate permanent-delete flow. See [Trash](../trash.md).

## Pages and navigation

Notebooks hold either finite pages or infinite canvases. Built-in paper styles include plain, ruled, dotted, grid, and specialist guides. You can add, insert, duplicate, delete, reorder, bookmark, and rename pages, or change a page's paper style at any time.

The continuous editor scrolls smoothly through finite pages, while the page browser and thumbnails let you jump quickly. Infinite canvases pan and zoom freely in every direction.

Undo and redo cover both page content and structural edits. Content history (including grouped cross-page edits) is saved to disk; page structure history is kept for the current session. See [Page navigation](../page-navigation.md) and [Continuous editor](../continuous-editor.md) for viewport anchors and device checks.

A **peek view** pins a region of a page for quick reference while you work elsewhere: hold to glance, or tap to keep it open. The saved peek anchor belongs to the notebook.

## PDFs and reference view

Importing a PDF creates a PDF-backed notebook you can annotate with the full editor. Embedded text, links, and outlines power search and navigation when the source PDF provides them.

The **PDF reference view** opens a read-only pane with contents, text search, page jump, zoom/fit, and link following. Export annotated pages as PDF or PNG, or save a `.folio` backup to preserve editable content and history. See [PDF rendering](../pdf-rendering.md).

## Tabs and split view

Open multiple notebooks in tabs and drag tabs sideways to reorder them; open tabs and their order persist across launches.

Use the toolbar's split button or notebook menu to open a second pane beside the current document. Pick the same notebook, another open tab, or a recent notebook. On narrow screens, panes stack vertically. Drag the divider to resize panes, or drag it to an edge to close a pane.

Both panes run on the editor's document surface:
- A stylus can write in either pane with shared ink tools.
- Using an editor-only tool (such as lasso) with a stylus shifts toolbar focus to that pane; finger navigation never switches focus.
- Leaving a split notebook parks its companion pane and restores it when you return.

See [Split view](../split-view.md) for shortcuts, details, and device checks.

## For maintainers

- `library/LibraryScreen.kt`, `LibraryBrowser.kt`, and `LibraryOrganization.kt` own browsing; `library/files/` handles device files.
- `workspace/` owns tabs, split layout, and saved workspaces.
- `editor/EditorScreen.kt` is the editor route; `storage/NotebookPdfs.kt` handles imported PDF metadata and rendering.
- See [Application architecture](../application-architecture.md) for the shared document engine.
