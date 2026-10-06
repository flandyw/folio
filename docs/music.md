# Music

**Music** is a peer of Library, Mistakes and Study: it opens inside the same shell (navigation
rail on wide screens, bottom bar on phones) and highlights there. Use **Import** (or its menu) to
add one or several unlocked sheet-music PDFs. Folio copies them into Music, so the originals can
be moved or removed after import and scores remain available offline.

## Shelf

The shelf follows the notebook shelf: a split **Import** button, a **Continue playing** card for
the last score opened, the same search field (title, composer or instrument/part), and chips for
**All scores**, **Favorites** and each set list, in the place folders take for notebooks. Each
set-list chip has its own rename/delete menu, and **New set list** sits at the end of the row.
Scores show their first page as the cover (a blank manuscript page until it is drawn) and can be
switched to a list; the choice is remembered separately from the notebook shelf
(`AppPrefs.MUSIC_LIST`). Sort by title, composer or recently played. The score menu holds details
and rehearsal notes, **Add to set list**, part extraction, export and delete.

Selecting a set list shows its header (score and page count, **Play set**, **Add scores**) and
the running order with numbered positions and **earlier / later** arrows. Reordering is
disabled while a search narrows the list. Removing a score from a set does not delete it.
**Play set** opens the first score; **Next: <title>** replaces the next-page button on the
final page/spread.

## Reader

Opening a score hides the shell's navigation, like Mistakes review. The reader uses the
editor's chrome: glass docks on the editor's desk colour, with anchored popovers instead of
dialogs.

- Top dock: back (with title and composer/part or set position on wider screens); the
  **Pencil** toggle and **Undo**; then **Rehearsal marks**, **Metronome**, **Two pages in
  landscape** (in the score menu on phones), **Performance mode** and the score menu (details,
  favourite, export, rehearsal notes, part extraction, gesture help).
- Bottom dock: 48dp previous/next buttons around the page indicator. Tap the indicator for a
  page grid; pages with a rehearsal mark carry a dot.
- Reading position is saved for each score. Rehearsal marks are added inline in their popover
  and jump straight to their page.
- Tap the left/right half of a page to turn it. Pinch to zoom, drag to pan, and double-tap to
  reset zoom. Page taps turn only at fit-page scale.
- **Pencil** draws blue annotations in page-relative coordinates; while it is on, turn pages
  with the dock buttons. **Undo** removes the latest stroke on the visible page/spread.
  Annotations survive closing, rotation and restart, and stay aligned between single pages
  and spreads.
- The metronome has 30–240 BPM (±1 buttons, slider, tap tempo), 1–12 beats per bar and an
  opt-in audible click using media volume. While running, its dock button becomes a beat pill
  (dots and BPM). Tempo and meter are saved per score. Playback stops when leaving the score or
  backgrounding Folio.
- **Performance mode** hides both docks and fits the page, with immediate tap turns and zoom
  disabled to prevent accidental movement. Only a translucent exit button, the page (and beat)
  indicator, and **Next** on the last page remain. The display stays awake while a score is open;
  the previous keep-awake setting is restored on exit. Arrow keys, Page Up/Down and Space work
  with keyboards and pedals configured as HID keyboards. Held keys do not repeatedly turn pages,
  and keys are ignored while a popover is open. Volume-button and proprietary Bluetooth pedal
  modes are not mapped. Page turns stop at the score boundary; advancing to another piece requires
  the explicit **Next** button.

## Importing parts

After import, each PDF opens a full-window review: the page preview on the left (or top) with a
page dock and scrubber that names the part the page belongs to, and a card per suggested part
that expands to its instrument, page range and title once ticked. **Keep the complete PDF
instead** and **Skip PDF** remain available.

## Storage and scope

Music owns `files/music/`: UUID-named source PDFs and a version-1 `library.json` index
containing score metadata, normalized pencil strokes, rehearsal marks and ordered set
lists, plus each score's optional last-opened time (`opened`, epoch millis; absent reads as
never, so no version bump). The index uses Android `AtomicFile`; a serialized ViewModel queue saves edits
before publishing them to the UI. PDF imports are staged in `music/pending/`, fsynced and validated before
review. Only accepted complete PDFs or extracted parts receive index entries. Extraction
saves all selected parts in one index update and rolls back outputs on write failure.
Skipped sources are deleted; abandoned pending imports are cleared on the next successful
library load. Pending reviews survive rotation, but must be restarted after process death. A failed/corrupt index is reported and never replaced with an
empty library. Import failures are counted without discarding successful imports.

This collection never goes through `NoteRepository` or the notebook index. Music
scores do not appear in notebooks, workspace pickers, progress, Focal sync, `.folio`
exports or notebook automatic/library backups. Existing notebook formats are unchanged.
**Export original PDF** saves the original source through Android's document picker;
it does not include Music pencil annotations, notes, set lists or rehearsal marks.
Music currently has no portable backup/restore or sync. Keep the original PDFs;
clearing app data or uninstalling removes the local Music collection and its annotations.

Pages render off the UI thread through `PdfRenderer`. Shelf covers come from a separate 12 MiB first-page cache, so leaving the reader never blanks
the shelf. A 32 MiB bitmap cache prefetches
up to two following pages. Each raster is bounded to 2000 pixels wide and 2400 pixels
high, independent of unusual PDF page dimensions. A render failure shows a retry action.
No network access, new dependency or Bluetooth permission is needed for this feature.

## Verification

`node tools/music-smoke.cjs` compiles the Android-free music models/codec against the
cached Kotlin compiler and `org.json` JVM jar. If that jar is absent, run
`./gradlew :app:prepareBackupSmoke` first. It checks metadata/ink round trips, annotation
undo and set order persistence, bounds clamping, unsafe IDs, and corrupt/newer indexes.
It also checks numbered/transposed part headings, ambiguous and scanned input, strict
page ranges, and extracted annotation/bookmark remapping. The canonical app check is
`./build.sh -p`.

Device checks (required before treating performance input as device-validated):

1. Import a multipage score and a batch containing one invalid or password-protected PDF.
   Confirm successful PDFs remain and the failure count is visible. Remove the source
   provider file, restart Folio, and open the local score. Verify the main notebook
   library and notebook exports contain no music scores.
2. Edit metadata, draw several strokes, undo one, add a rehearsal mark, and navigate.
   Rotate, close/reopen, then force-stop/relaunch. Verify ink alignment, page, tempo,
   meter and rehearsal marks. Cancel an import/export picker without changing anything.
3. Make and reorder a two-piece set. Remove/re-add a piece, play through both, and test
   an odd final page in two-page mode. Delete a set and confirm its scores remain.
   Delete a score and confirm its set entries disappear.
4. Test pinch/pan/reset, page jump, annotation input with finger and stylus, and back
   navigation in portrait and landscape. Test phone width and large accessibility text: the
   top dock must fit at 360dp, and the shell's rail/bottom bar must return after closing a score.
5. Pair a HID keyboard/pedal. Test all mapped keys, held keys, first/last-page bounds,
   and text entry in dialogs. Confirm performance mode cannot draw accidentally.
6. Run the metronome, change tempo/meter, toggle sound, then background and reopen.
   Confirm it stopped. Check screen wake restoration after leaving the reader.
7. Export the original PDF and compare it with the imported source. Test disk-full or
   unavailable-provider failures without losing existing scores. Interrupt an index save
   and verify the previous or completed AtomicFile index can still open.

8. Import a combined packet with numbered/transposed parts and unlabelled continuation
   pages. Select two instruments, adjust one range, rotate during review, then import.
   Verify the page counts, notation, fonts, rotation and exports of each resulting part.
   Test an inherited-font PDF and a source with PDF links to omitted pages.
9. Try a scanned packet and a full orchestral score. Verify manual selection is available
   and no staves are claimed to have been separated. Skip a PDF in a batch; keep another
   complete. Extract from an annotated existing score and check its originals, set lists,
   copied ink and remapped marks. Interrupt extraction and restart: saved scores must open.
