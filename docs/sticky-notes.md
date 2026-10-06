# Sticky notes

Choose **Sticky note** in the toolbar (under the overflow menu by default), then drag diagonally to create a rectangle. The note offers Type, Draw and Delete in place. Type opens an editor anchored to the note; Done, Back or tapping away saves. Draw lets the current sticky tool write in that note; the pen, highlighter and eraser tools also work inside notes. Move a note with the sticky, text or hand tool, and drag its lower-right grip to resize. After choosing Draw, use Text or Hand to move/resize that note.

Finite pages have a workspace gutter. A sticky exports only when its entire rectangle is within the page. A note overlapping an edge or entirely outside stays out of PDF/image exports and thumbnails. Native notebook exports and backups retain all notes. Infinite canvas notes export with the rest of the canvas.

A sticky is a `TextBox` with optional `stickyHeight` and `stickyInk` fields. Its ink coordinates are local to the rectangle. These fields use the existing JSON text payload inside binary snapshots and journal edits, so moving/deleting the note remains one atomic, undoable text edit and the binary versions remain unchanged. Ordinary text writes no new fields. Older builds ignore sticky-specific fields, so they cannot preserve sticky backgrounds or ink if they resave those pages.

The backup smoke runner checks text codec and portable round trips, binary snapshot/history preservation, undo inversion, scaling, legacy text payloads and export placement rules:

```
./gradlew :app:prepareBackupSmoke
node tools/backup-smoke.cjs
```

Device round-trip check before a stable release:

1. Create a typed note inside a PDF page and a handwritten note in its gutter. Add highlighter ink; erase part of a stroke. Resize and move each note, then undo and redo.
2. Close and reopen the notebook; restart the app. Confirm text, ink, sizes and outside placement survive. Repeat on a blank finite notebook and an infinite canvas.
3. Export PDF and an image. Check the interior note appears and the outside/edge-overlapping note is absent. Move the outside note wholly inside and repeat.
4. Export/import a `.folio` and restore a library backup. Confirm both notes, their local ink and undo history survive. Check a notebook without sticky notes is unchanged.
5. Check stylus palm rejection, finger drawing off, keyboard dismissal, changing pages while typing, hidden/locked layers, lasso duplicate/move/resize and page-to-page selection drops.
