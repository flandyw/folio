# Writing follow and peek

## Writing follow

Writing follow moves the view so the line being written stays in a comfortable place. It never
changes saved ink or the notebook format.

**State comes from the ink.** On every pen-up, `LineReader` (`WritingLine.kt`) re-reads the current
line from the strokes on the page around the stroke just written:

- Strokes belong to one line when their bodies sit at the same height. Each stroke's probe point is
  a third of the way down, which is above any descender and below any ascender.
- Strokes have to be joined by word-sized gaps (four line heights), so a second column stays separate.
- The baseline is the tightest cluster of stroke bottoms, which keeps descenders and first-letter
  descenders from dragging it down. Printed rules (ruled paper, detected PDF rules) take priority.
- Line height, line spacing (the measured gap to the line above) and the line's extent all scale
  with the handwriting. No threshold is a fixed page unit.
- Dots and crossbars count as *minor*, long flat strokes as *rules*, and much taller strokes as
  diagrams. None of them is treated as a line.

Nothing is remembered between strokes except the writer's pen-up rhythm, a few canvas column edges
and the line placed by Next line. Undo, erasing, moved ink and corrections therefore cannot leave a
stale frontier behind.

**Moves are planned towards absolute targets** (`WritingFollow.kt`, with `FollowMotion.kt` doing
the easing). A move is planned after a pause and runs as one eased glide. Touching down cancels it,
and the next pen-up replans from wherever the view actually is, so an interrupted move can never be
applied twice. Back undoes the moves that actually happened, up to 12 of them; clamped travel is not
counted.

Rules a writer can rely on:

- The first stroke after any navigation (pinch, pan, page jump, rotation, Back) never moves the view.
- A stroke behind the line's end (a correction) or somewhere new (a jump) holds the view still.
- Only progress moves it: the line growing towards its end, or the natural next line below.
- Sideways: once the end of the writing passes the feel's trigger (68–86 % across), the view glides
  so it sits at about 42 % (47 % when the hand covers the written side). It never glides past the
  line end, so the end can always be reached.
- Vertical: by default the page stays still vertically while you write along a line, and only moves
  the line back up to the writing height if it nears the bottom 12 % of the view. With *Keep my line
  at this height* on (Text mode), a baseline sinking more than the band (10-22 %) below the writing
  height is also nudged back up. Maths always does this. Next line always uses the writing height.
- Near the visible edge, a letter gap is enough to start a move, and the glide is quicker.
- Handwriting smaller than 8 px on screen is readable as it is, so the view does not follow it.
  Next line still works.

**Line ends and Next line.** A line ends at its printed rule's end. On a blank page it ends at the
36-unit margin. On an infinite canvas it ends where the visible edge was when the line started. Next
line goes to the next printed rule in the same answer block and stops at the block's last rule.
Without rules it goes one measured line spacing down, back to where the paragraph's lines start,
and stops at the bottom of a page. A short marker shows where the next line begins. Automatic line
return does the same after a pause, but only once a full line (at least 40 % of its width) has
reached its end.

**Maths** reads the block of working touching the stroke (fractions included) and only moves down.

**Settings:** Feel (one value controlling the pauses, trigger, band and glide), Text/Maths,
automatic line return, direction, hand, writing height and *Keep my line at this height*. Keys are listed in `FollowPrefsStore`.
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

## Checks

```sh
./gradlew :app:assembleDebug :app:lintDebug
node tools/writing-follow-smoke.cjs
```

The smoke check compiles the pure engine (`WritingFollow.kt`, `WritingLine.kt`, `WritingGuides.kt`,
`FollowMotion.kt`) with Gradle's cached compiler. It drives the engine through a fake view with
deterministic handwriting traces, needs Node and JDK 17, and downloads nothing.

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
5. On the canvas, write a long line through several glides. The end must stay reachable, and the
   return must go back to the line start. Pinch or rotate mid-line: the next stroke holds.
6. Repeat right-to-left and with the left hand selected. Use Maths with a fraction: down only.
7. Peek: pin a view, hold the eye, then release. Tap to keep it open, pan inside, then close it
   with Back. Delete the pinned page: the eye turns back into a pin. Duplicate the notebook and
   peek in the copy.
8. Storage round trip: on a build from before this change, pin a peek view and export a `.folio`.
   Install this build, then confirm the peek still opens, re-pin it, restart, export and re-import.
