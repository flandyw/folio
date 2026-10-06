# Music

**Music** is a peer of Library, Mistakes and Study: it opens inside the same shell (navigation
rail on wide screens, bottom bar on phones) and highlights there. Use **Import** (or its menu) to
add one or several unlocked sheet-music PDFs. Folio copies them into Music, so the originals can
be moved or removed after import and scores remain available offline.

## Shelf

The shelf follows the notebook shelf: a split **Import** button, a **Continue playing** card for
the last score opened, the same search field, and chips for **All scores**, **Favorites**, each
set list and, once a shelf holds more than one, each composer, in the place folders take for
notebooks. **Annotated** and **Unfinished** chips narrow to scores that carry pencil marks and to
ones started but not finished; **Clear filters** appears while any filter is on. Each set-list chip
has its own rename/duplicate/delete menu, and **New set list** sits at the end of the row. Search
covers title, composer, instrument/part, rehearsal notes and rehearsal-mark names, and every
whitespace-separated term must match, so a cue written in the notes finds its score. Scores show
their first page as the cover (a blank manuscript page until it is drawn) and can be switched to a
list; the choice is remembered separately from the notebook shelf (`AppPrefs.MUSIC_LIST`). Sort by
**Recently added** (import order), **Title A–Z** / **Z–A**, **Composer A–Z**, **Instrument A–Z**,
**Most pages**, **Fewest pages**, **Most rehearsal marks** or **Recently played**; the chosen order
is remembered between sessions. A score that has been read carries a thin progress bar along the
bottom of its cover or row (and the Continue playing card, which shows the percentage read), so an
unfinished piece is visible at a glance; each card and row names the score's rehearsal-mark and
pencil-note counts, and the section's count chip reads out the scores and their page total (its
accessibility label adds the pencil marks). The score menu holds details and rehearsal notes,
**Add to set list**, **Duplicate score** (which copies the PDF and its annotations; the copy starts
unopened), part extraction, export and delete. Back clears the composer and filter chips before it
leaves Music.

Selecting a set list shows its header (score and page counts, an estimated running time at roughly
half a minute a page, **Play set**, **Add scores**, **Reverse**, **Shuffle** and **Share**) and the
running order with numbered positions and **earlier / later** arrows. **Reverse** flips the order,
**Shuffle** randomises it, and **Share** sends the numbered order as plain text through the system
share sheet. Reordering is disabled while a search narrows the list. Removing a score from a set
does not delete it. **Play set** opens the first score; **Next: <title>** replaces the next-page
button on the final page/spread.

## Reader

Opening a score hides the shell's navigation, like Mistakes review. The reader uses the
editor's chrome and the editor's canvas contract, with anchored popovers instead of dialogs.
`MusicReader.kt` is laid out like `EditorScreen.kt`: the canvas is the editor's desk
(`surfaceContainerLow`), the sheet is the one bright surface on it, and the glass docks float
above the desk as real overlays rather than sitting in layout rows of their own. Both docks are
measured with `onGloballyPositioned` and the page is inset by their heights (the editor's
`floatingToolbarTop` idea), so a whole page or spread always fits between the docks and no chrome
ever covers the music. `MusicPaper` is the sheet colour only — a rendered page is white whatever
the theme — and each page is clipped to `FolioShapes.medium`, the shape an editor page wears.

- **Tool strip**: the editor's own `FloatingInkToolbar` (`InkToolbar.kt`) floats flush to the top on its
  own, with no row of score pills above it. Its full height (quick colour/width bar included) is
  measured and the pages are inset by that and a 4dp gutter, applied once it settles so opening or
  closing the bar re-fits the page once rather than every animation frame. It is the same strip
  with the same `ink-tools` preferences: pen, shapes (line, rectangle, ellipse, triangle, diamond,
  pentagon, hexagon, star), highlighter, eraser, text and lasso, shared quick colours,
  width/opacity popovers, saved presets, undo/redo, the ⋯ overflow and *Edit toolbar*. Score
  options can hide the whole strip; the page re-fits to the freed desk space and the choice is
  remembered (`AppPrefs.MUSIC_TOOLBAR`). **Marking**
  and **sticky notes** are left out (they belong to a marked response / a notebook page), graph
  axes are dropped from the shape picker, and the Behaviour menu shows only what applies.
- **Floating rails** replace the old top pills and bottom dock. A glass rail at the left edge
  holds **Back**, **Rehearsal marks**, the **Metronome** (a tempo button that flashes on the
  downbeat while it runs) and **Previous page**; a rail at the right holds **Score options**
  (details, favourite, export, rehearsal notes, extract parts, **App settings**, hide/show the tool
  strip, a **Reading light** group that dims the sheet from untouched to 70% (`AppPrefs.MUSIC_DIM`),
  clear annotations on the pages in view, clear every annotation in the score, gesture help; the
  options header reports the score's pencil-mark count), **Performance mode**, the page readout
  (tap for the page grid; a dot marks rehearsal marks, a blue number counts pencil notes) and
  **Next page** — which becomes **Next: <title>** inside a set on the last page. The grid numbers
  every page and offers **First**/**Last** jumps and a scrubber for a long score. The pages sit
  between the rails.
- **Pages** are one horizontal strip (`LazyRow`) that slides when you turn. Portrait shows one
  page; landscape shows two (never more) unless **Show one page at a time** is chosen in Score options. Turning moves by the number of
  pages shown and the last window ends on the last page. The strip does not scroll by swipe so a
  finger or pen on the sheet is free to draw; turn with the rail buttons, a tap on a page's left/right half,
  or the keys below. Every real turn ticks the haptics, so a tap or pedal confirms without looking down.
- Reading position is saved for each score. Rehearsal marks are added inline in their popover and
  jump straight to their page; the popover's arrows step to the previous/next mark without leaving
  the score.
- The sheet clears the whole tool strip, quick colour/width bar included, so it never covers the top staves; the page re-fits once when that bar opens or closes. Tap the left/right half of a page to turn it. Pinch to zoom, drag to pan, and double-tap to
  reset zoom. Page taps turn only at fit-page scale.
- **Annotations** live in the music index itself (`MusicModels.kt`) in page-relative coordinates,
  so they survive closing, rotation and restart and stay aligned between single pages and spreads.
  A stroke carries the tool that drew it, its colour, width, opacity and line style; a label (the
  **Text** tool) is a typed mark in the same coordinates. A freehand stroke keeps its samples and a
  shape keeps only its two drag corners, exactly as the editor stores one, and the outline is built
  by the pure `MusicInk`. Those fields are optional, so a plain blue pen writes no style keys and an
  index without labels writes no labels array: `MusicCodec.VERSION` stays 1 and an older Folio still
  reads (and still draws) the score. **Undo/redo** step through annotation snapshots — a drag that
  edits frame by frame (moving a selection, erasing) is one undo, not one per frame.
- **Eraser ring**: as in the editor, a pen hovering over the glass previews the eraser's circle at its real size (pressure-scaled when that is on), and the ring follows the tip while it cuts.
- **Eraser** rubs out the ink it touches: the touched samples are removed and each surviving run
  becomes its own stroke, so a phrase can be shortened without losing the line. *Whole-stroke
  eraser* (Behaviour menu) drops a touched mark instead, and a touched shape always goes whole.
- **Lasso** draws a loop around ink and labels; a stroke is held only when every sample is enclosed.
  Drag a selection to move it, and use the pill above the dock to duplicate or delete it, make it
  dashed or solid, or thicken it. A new quick colour restyles the selection, as it does in the
  editor. A long press with the hand tool picks up the mark under the finger, so one stroke can be
  restyled or moved without lassoing it. The selection belongs to one page, so a spread never
  mirrors it onto its partner.
- **Text** taps a spot to type a label — rehearsal letters, dynamics, reminders — and taps an
  existing label to edit it; clearing its text deletes it. Typing offers quick presets (mf, pp, f,
  rit., A, B, cue) so a common cue needs no keyboard work.
- The metronome has 30–240 BPM (±1 and ±5 buttons, slider, tap tempo, and one-tap Largo–Presto
  presets, with the nearest marking named beside the readout), 1–12 beats per bar and an opt-in
  audible click using media volume. It can accent beat 1, tick a subdivision of 2, 3 or 4 inside
  the beat, and lead in with one bar of **Count in**. While running, its dock button becomes a beat
  pill (dots and BPM). Tempo and meter are saved per score; the click, accent, subdivision and
  count-in are remembered app-wide (`AppPrefs.MUSIC_CLICK`, `MUSIC_ACCENT`, `MUSIC_SUBDIVISION`,
  `MUSIC_COUNT_IN`). Playback stops when leaving the score or backgrounding Folio.
- **Performance mode** hides both docks and fits the page, with immediate tap turns and zoom
  disabled to prevent accidental movement. Only a translucent exit button, the page (and beat)
  indicator, and **Next** on the last page remain. The display stays awake while a score is open;
  the previous keep-awake setting is restored on exit. Arrow keys, Page Up/Down and Space work
  with keyboards and pedals configured as HID keyboards, and Home/End jump to the first/last page.
  Held keys do not repeatedly turn pages, and keys are ignored while a popover is open. Volume-button and proprietary Bluetooth pedal
  modes are not mapped. Page turns stop at the score boundary; advancing to another piece requires
  the explicit **Next** button.

## Importing parts

After import, each PDF opens a full-window review: the page preview on the left (or top) with a
page dock and scrubber that names the part the page belongs to, and a card per suggested part
that expands to its instrument, page range and title once ticked. A filmstrip of page thumbnails
sits under the preview: tapping a page previews it and adds or removes it from the active part's
range, so a range can be built by eye rather than counted. **Show** on a card chooses which part
the strip edits (a header names it), and **Merge selected parts** unions the ticked parts into one,
keeping every page once in order and joining their instruments. **Keep the complete PDF
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
page ranges, extracted annotation/bookmark remapping, and shelf ordering, search scope, reading
progress and rehearsal-mark navigation. Newer coverage includes the shelf sorts, filters and
multi-term search, the score-state and set-total helpers, tempo names, the selection helpers
(bounds, stroke hit-test, restyle clamps, normalise) and the filmstrip page-toggle and part-merge
helpers. The canonical app check is `./build.sh -p`.

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
   navigation in portrait and landscape. Draw with each tool in turn: pen and highlighter colours,
   widths and opacity, every shape dragged in both directions, a dotted and a dashed shape, the
   eraser (rubber and whole-stroke, including *part* of a long line), text labels added, edited and
   deleted, and a lasso selection moved, restyled, duplicated and deleted — then undo and redo each
   one. Confirm an old-style plain blue stroke still opens in the same place and thickness, and
   that annotations from before this change (no style keys) render unchanged. Test phone width and
   large accessibility text: the top dock must fit at 360dp, the desk should show only as the
   gutter around the sheet (no bands behind the docks), the tool strip must stay centred whatever
   the side pills contain, and the shell's rail/bottom bar must return after closing a score.
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

10. Turn on **Reading light** and hide the tool strip, then close and reopen a score: both must be
    remembered and the page must re-fit to the freed desk. Run the metronome with **Accent beat 1**,
    a subdivision and **Count in**, then background Folio: the click must stop and the choices must
    survive a restart.
11. Duplicate a score and a set list, then **Reverse**, **Shuffle** and **Share** the set: the copy
    must open unopened, the original and every set place must be untouched, and the shared text must
    match the running order. In the import review, build a range by tapping the filmstrip, merge two
    ticked parts, and confirm the union's page count and instruments.
