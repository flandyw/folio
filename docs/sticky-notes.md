# Sticky notes

Choose **Sticky note** in the toolbar, then drag diagonally to create a note; with finger drawing off, a finger pans and the pen draws notes. The new note is focused: an outline and a lasso-style icon menu above it offer Type, Draw and Delete. Tapping a note with any tool focuses it and shows that menu (a tap with the pen, highlighter or eraser leaves no ink; a drag does); tapping the focused note again with the sticky tool types in place, as does Type: a transparent field sits over the note at its own size, so its ink stays visible. The check mark, Back, tapping away or changing tools ends typing, and the words commit as one undoable edit. Draw switches to the pen; pen, highlighter and eraser strokes that start inside a note go into the note's own ink. With the sticky tool, drag a note to move it and its lower-right grip to resize it; lasso selections move notes too. The hand tool always pans.

Pen, highlighter and eraser (including the pen's eraser end and barrel button) work on a note exactly as on the page: the eraser honours whole-stroke mode and pressure, and shows its ring. Note ink is not clipped to the note, so a stroke begun inside may run out over the page, and page ink under a note shows through its paper (below the note's own ink and words, also in exports). An eraser that starts over only page ink erases the page. The note menu also changes paper colour (`TextBox.stickyColor`, 0 = classic yellow, written only when set), duplicates the note and clears its drawing.

A document is wider than its pages: each side has one page width of workspace (more if a note already sits further out), and horizontal panning is clamped to that, so the paper can never be lost. Panning still just offsets the page list. A page grows above or below its paper only as far as a saved note hangs past it, so ordinary page spacing is unchanged. A sticky exports only when its entire rectangle is within the page; a note overlapping an edge or entirely outside stays out of PDF/image exports and thumbnails. Native notebook exports and backups retain all notes. Infinite canvas notes export with the rest of the canvas.

A sticky is a `TextBox` with optional `stickyHeight` and `stickyInk` fields. Its ink coordinates are local to the rectangle. These fields use the existing JSON text payload inside binary snapshots and journal edits, so moving/deleting the note remains one atomic, undoable text edit and the binary versions remain unchanged. Ordinary text writes no new fields. Older builds ignore sticky-specific fields, so they cannot preserve sticky backgrounds or ink if they resave those pages.

The backup smoke runner checks text codec and portable round trips, binary snapshot/history preservation, undo inversion, scaling, legacy text payloads and export placement rules:

```
./gradlew :app:prepareBackupSmoke
node tools/backup-smoke.cjs
```

Device round-trip check before a stable release:

1. Create a typed note inside a PDF page and a handwritten note beside it. Add highlighter ink; erase part of a stroke. Resize and move each note, then undo and redo.
2. Close and reopen the notebook; restart the app. Confirm text, ink, sizes and outside placement survive. Repeat on a blank finite notebook and an infinite canvas.
3. Export PDF and an image. Check the interior note appears and the outside/edge-overlapping note is absent. Move the outside note wholly inside and repeat.
4. Export/import a `.folio` and restore a library backup. Confirm both notes, their local ink and undo history survive. Check a notebook without sticky notes is unchanged.
5. Check stylus palm rejection, finger drawing off, keyboard dismissal, changing pages while typing, hidden/locked layers, lasso duplicate/move/resize and page-to-page selection drops.
