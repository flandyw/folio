# Writing follow and peek

## Writing follow

Writing follow moves the view so the line being written stays in a comfortable place. It never
changes saved ink or the notebook format.

**State comes from the ink.** On every pen-up, `LineReader` (`WritingLine.kt`) re-reads the current
line from the strokes on the page around the stroke just written:

- Strokes belong to one line when their bodies sit at the same height. Each stroke's probe point is
  a third of the way down, which is above any descender and below any ascender.
- Strokes have to be joined by word-sized gaps (four line heights), so a second column stays separate.
- The read spans the whole printed response line or the canvas paragraph back to its start. Even
  tiny handwriting on a long line keeps its earlier words in the measurement at high zoom.
- The baseline is the tightest cluster of stroke bottoms, which keeps descenders and first-letter
  descenders from dragging it down. Printed rules (ruled paper, detected PDF rules) take priority.
- Unruled writing is rarely level, so a straight line is fitted through the strokes (`WritingLine.slope`)
  and the line is followed along it, up to about 19 degrees. A tilt under half a letter height across the
  whole line is treated as level. The next line begins one line spacing below where this one *began*, and
  the line just written never counts as ink on the next one, so a climbing or sinking line still returns.
- Line height, line spacing (the measured gap to the line above) and the line's extent all scale
  with the handwriting. No threshold is a fixed page unit.
- Dots and crossbars count as *minor*, long flat strokes as *rules*, and much taller strokes as
  diagrams. None of them is treated as a line.

Nothing is remembered between strokes except the writer's pen-up rhythm, a few canvas column edges
and the line placed by Next line. Undo, erasing, moved ink and corrections therefore cannot leave a
stale frontier behind.
Before a delayed move starts, the engine re-reads its source line and cancels if the ink changed.
Explicit Next line and finishing dots also re-read existing ink. External edits (including undo,
redo and sync) stop a running glide when the view binds the new stroke list.

**Moves are planned towards absolute targets** (`WritingFollow.kt`, with `FollowMotion.kt` doing
the easing). A move is planned after a pause and runs as one eased glide. Touching down cancels it,
and the next pen-up replans from wherever the view actually is, so an interrupted move can never be
applied twice. Back undoes the moves that actually happened, up to 12 of them; clamped travel is not
counted.
Interrupting a return restores its source cursor, so pressing Next line again resumes that same
destination rather than skipping an unwritten line.

**Pace.** A following glide never averages faster than 1 px/ms, however far it goes, and is never
shorter than the feel's glide (300-480 ms), so a long sideways move reads as a glide, not a snap. Moves
made with the pen at the visible edge are shorter (260-450 ms) but ease *out*, covering most of the
distance at once so a stroke that cuts them short still finds room. A carriage return is brisker
(2.5 px/ms, at most 650 ms): what a writer notices is the wait before it, not its speed.

Rules a writer can rely on:

- The first stroke after any navigation (pinch, pan, page jump, rotation, Back), tool change or
  loss of window focus never moves the view. Navigation clears the old paragraph and marker;
  Next line then reads ink in the newly visible view.
- A stroke behind the line's end (a correction) or somewhere new (a jump) holds the view still.
- Only progress moves it: the line growing towards its end, or the natural next line below.
- Sideways: once the end of the writing passes the feel's trigger (68–86 % across, earlier when
  large on-screen letters need room for the next stroke), the view glides
  so it sits at about 42 % (47 % when the hand covers the written side). It never glides past the
  line end, so the end can always be reached.
- Vertical: by default the page stays still vertically while you write along a line, and only moves
  the line back up to the writing height if it nears the bottom 12 % of the view. With *Keep my line
  at this height* on (Text mode), a baseline sinking more than the band (10-22 %) below the writing
  height is also nudged back up. Maths always does this. Next line always uses the writing height.
- Near the visible edge, the learned letter gap allows an earlier move (40–180 ms) with a short
  ease-out glide. Frames can run between strokes; touching down always cancels the remaining travel.
- Handwriting smaller than 8 px on screen is readable as it is, so the view does not follow it.
  Next line still works.

**Line ends and Next line.** A line ends at its printed response line's end. On a PDF, the background
scan groups aligned solid, dashed or dotted response lines into answer areas. The area runs from
one line spacing above its first rule (or just under question text that reaches into that space) down to its last rule, with the rules' horizontal extent.
No enclosing rectangle is needed. A larger gap relative to the local line spacing, intervening
question text, or a different column starts a separate block. A long isolated response line also
forms an area; short isolated underlines, text, thick bars and table/box borders are rejected.
These boundaries are inferred from the unannotated background, so handwriting cannot change them.
Page furniture is rejected: a lone rule in the top 7 % / bottom 5 % of the page or with exactly the
running header's extent, a lone rule without about a line of clear writing room above it (a label on its own row is fine) or with text close beneath it, a leader line (text at both
ends), and gridlines (aligned rules with axis labels, or crossed by plotted data). Checked against the
2025 VCAA Mathematical Methods (NHT) 1 and Physical Education papers; the title-page dividers are
rejected by the room-above rule. Dashed and dotted rules count only when their gaps are evenly spaced, so
text rows cannot pass as dotted lines.
The scan runs only for the page being written on, off the UI thread: the raster is read one row at a time
into a dark-pixel mask (no copy of the page), and the result is cached per page for 32 pages.
On a blank page a line ends at the 36-unit margin. Next line goes to the next printed rule in the same answer block
and stops at the block's last rule, including when the next question has aligned response lines.
Without rules it goes one measured line spacing down, back to where the paragraph's lines start,
and stops at the bottom of a page. A short marker shows where the next line begins.

**Next line is a carriage return.** It goes down *and back* to the line's start, whatever the view
had travelled: with the whole line on screen the view only moves until all of it is visible, and
otherwise the start lands at the leading edge (8 % across), so every return ends in the same place.
Rules inside a ruled answer box count as response lines too; the box's own edges do not, and fewer
than three inner rules reads as a table instead.

**Automatic line return** does the same after a pause (0.3-0.7 s by feel, never shorter than a little
over this writer's word gap; about half that once the writing has reached the line's end, and 150 ms
after a glide that only made room), and the writer does not have to reach the edge. A line is full when another word would not fit (the last ~4.5 letter heights before the
response line's end, at least half the line written) or when it reaches where this writer has been
wrapping: Next line and wrapping to a new line teach the engine the fraction of the line (from
70 %) the writer usually fills, and a line within 6 % of that, and past 60 %, also counts as full.
If the pen is at the edge of the screen with line left to write on, the view makes room first and
the return follows once it has settled. The end zone is about a word, not a page fraction, so at
high zoom it cannot skip several visible words. The final answer line still glides to keep its
ending visible, even though there is no next line. A different detected answer block is a jump,
not the natural continuation of the previous answer.

**Canvas paragraphs.** With automatic return off, sideways writing continues without an artificial
edge; Next line still goes back to the paragraph start. With it on, *Canvas line length* chooses
1–4 screen widths (default 2), measured at the paragraph's starting zoom, with a minimum of eight
letter heights. The edge stays fixed through glides and line returns. Continuing beyond a cancelled
wrap extends the edge by another screen. Panning, zooming or starting a separate paragraph measures
a fresh width. Exam response lines always supply their own width.

**Existing work and interruptions.** Automatic return holds if the destination already contains
handwriting; explicit Next line can still enter it. It does not skip filled lines into another
question. Paused writing stays still and explicit Next line reads the most recently written visible
line. Corrections, underlines, diagrams, reverse-direction jumps and different columns continue to
hold the view rather than starting a return. Text mode follows lines; Maths mode remains the choice
for two-dimensional working. Continuous pen contact never moves the camera, so joined-up writing
still needs a pen-up pause to reveal more room.

**Maths** reads the block of working touching the stroke (fractions included) and only moves down.

**Settings:** Feel (one value controlling the pauses, trigger, band and glide), Text/Maths,
automatic line return, *Outline detected answer areas* (dashed frame in the selection blue, `follow.showAreas`, display only),
canvas line length, direction, hand, writing height and *Keep my line at this height*.
Keys are listed in `FollowPrefsStore` / `AppPrefs`.
The earlier tuning keys and per-page `follow.region.*` answer areas are removed the first time the
editor opens.

## Peek

A notebook keeps one peek view: a page and a rectangle on it, in page units (`PeekAnchor`), so it
frames the same content on any screen. It is stored on the notebook in `note.json` (and in `.folio`
archives) under `peekAnchor`. Earlier versions stored it on one of the pages, and that is still read
when the notebook-level field is missing. Deleting the page clears it. Duplicating a notebook remaps
it. A view saved partly off a fixed page is clamped to the paper.

Pin it with the pin button, or with *Pin this view* or *Pin this whole page* in the options menu.
Press and hold the eye to look, and release to come back. A quick tap keeps the peek open, where it
can be panned and zoomed, until you tap the eye, press Close or press Back. TalkBack's double-tap
toggles it. A held peek closes if the window loses focus. Peeking cannot start mid-stroke.

**Auto peek** (options menu → *Auto peek: whole page*) needs no pin: the eye always shows the whole
of the page you are writing on, fitted to the screen, and works the same way (hold or tap). It is
a device preference, and a pinned view is kept for when it is off. An infinite canvas has no page
edge, so there the eye still uses the pinned view. The view is fixed when the peek opens.

## Checks

```sh
./gradlew :app:assembleDebug :app:lintDebug
node tools/writing-follow-smoke.cjs
```

The smoke check compiles the pure engine (`WritingFollow.kt`, `WritingLine.kt`, `WritingGuides.kt`,
`FollowMotion.kt`) with Gradle's cached compiler. It drives the engine through a fake view with
deterministic handwriting traces and synthetic exam rasters (solid/dashed/dotted rules, separate
questions, columns, single-line answers and rejected borders/text), needs Node and JDK 17, and
downloads nothing.
The fake clock runs scheduled frames between pen strokes as well as during pauses, so tests cover
partial glides in a fast writing rhythm, not only fully completed animations.

## Scenario coverage

| Use case | Intended behaviour | Regression coverage |
| --- | --- | --- |
| Exam, high zoom and small handwriting | Reveal room between strokes, read the full response line, return near its actual end, stop at the last rule | Multi-line exam at 8×, fast 120 ms gaps, final answer line, occupied destination, return a word before the end, learned wrap point, carriage return to the start, ruled answer boxes |
| Infinite canvas, long horizontal notes | Glide across any number of screens when automatic return is off | Long lines in both directions, explicit return to paragraph start |
| Infinite canvas, paragraphs | Wrap at a stable chosen width through several lines; extend when the writer continues past it | 1, 2 and 4 screen widths, both directions, repeated returns, cancelled wrap |
| Interrupted return | Repeat Next line to reach the same destination; Back undoes actual travel | Partial return and double-tap, clamped travel, interrupted sideways glide |
| Undo, erase, correction or navigation | Stop stale moves and use current ink/current viewport | Pending undo, deleted line, finishing dot, new visible paragraph, answer-block jump |
| Mixed writing habits | Preserve pause, explicit controls, Maths, tiny writing and correction behaviour | Paused writing plus Next line, fractions, dots, capitals, descenders, diagrams and underlines |

## Device check

Use a stylus at a comfortable zoom on a ruled page, a blank page, a PDF with several answer blocks,
and an infinite canvas.

1. Write words with `f g j p y`, including as the first letter, plus capitals and joined-up words.
   The view should glide sideways only after pauses and never while the pen is down.
2. Correct an earlier word: the view holds. Dot an `i` at the end of the line: an interrupted move
   resumes. Rest a palm during a pause: nothing is cancelled.
3. Undo the last few letters, then continue writing. The view should not jump to where the undone
   ink used to end.
4. Use Next line repeatedly on ruled paper and on a PDF block. It should stop at the block's last
   rule. Turn on automatic return and finish a full line. A short note near the edge must not return.
   On an exam PDF, repeat with solid and dotted response lines, a single-line answer and adjacent
   questions/columns. The return should use the response line's edge and never cross to another
   answer block. Check that a standalone printed rectangle is not treated as an answer area.
5. On the canvas with automatic return off, write across at least four screen widths. With it on,
   try 1, 2 and 4 screen widths for three consecutive lines; each return should keep the same left
   edge and width. Continue beyond a cancelled return to get more room. Pinch or rotate mid-line:
   the next stroke holds and a new paragraph width is measured.
6. Repeat right-to-left and with the left hand selected. Use Maths with a fraction: down only.
7. Peek: pin a view, hold the eye, then release. Tap to keep it open, pan inside, then close it
   with Back. Delete the pinned page: the eye turns back into a pin. Duplicate the notebook and
   peek in the copy.
8. Storage round trip: on a build from before this change, pin a peek view and export a `.folio`.
   Install this build, then confirm the peek still opens, re-pin it, restart, export and re-import.
9. At high zoom, fill two full exam lines with small handwriting at normal writing speed, then
   finish the last line. Return should wait until near the visible line end; the final line should
   still glide. Interrupt a return halfway and press Next line again: it must not skip a rule.
10. Write ahead on the next rule, then complete the current line: automatic return should hold.
    Undo during a glide, erase the whole current line, switch tools, pan to another question, and
    leave/re-enter the app. Check that no stale move runs and Next line uses the visible work.
