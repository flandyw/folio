# Writing follow and peek

Writing follow uses transient handwriting geometry; it does not change saved ink or notebook storage.
Printed rules anchor a page's baseline and spacing. Blank pages and infinite canvases infer the body
from recent strokes, keep descenders out of the baseline estimate, and confirm ambiguous line changes.
A clear cursive word on a new line can provide enough evidence in a single stroke.
Ambiguous line changes need nearby forward writing in the selected writing direction: retracing
the same letter or adding a distant annotation does not confirm a return. Retracing preserves the
original candidate without extending its lifetime. After 15 seconds, the next valid stroke clears
unconfirmed evidence, including when that stroke is only a finishing dot.

New-line placement stays pending across pen-down interruptions. Glides stop as soon as the pen
touches down and replan from the actual view after the next accepted stroke. Returns aim at an
absolute writing height, so stopping halfway and resuming cannot apply a second full vertical step.
Actual consumed pan determines whether a return arrived; horizontal movement alone cannot advance
the tracked baseline when vertical scrolling is clamped. Back records only movement actually applied.

Automatic returns use the full configured pause. Sideways glides and new-line placement wait at
most half of it, but adapt to the writer (`FollowRhythm`): a fixed wait outlasted a fluent writer's
pause between words, so every touch-down cancelled the glide and the view only moved when they
stopped. Pen-up pauses of 30–1500 ms are learned; once six are known the wait is at most 70% of
the typical word pause (their 75th percentile). Each wait cancelled by touch-down before moving
shortens the next by a third, until a glide moves. Writing near the visible edge (past the follow
trigger on either axis) shortens the wait by up to 75%; new-line placement is not hurried. The wait
never drops below 90 ms or exceeds the configured half pause, so a slow writer is unaffected, and
navigation resets the cancellation count but keeps the learned rhythm. Glide speed is unchanged:
the visible edge never accelerates the glide itself. Every glide honours
the configured duration as a minimum and takes at least 700 ms per viewport of travel on
either axis (a half-screen pan takes at least 350 ms); *Next line speed* (`follow.lineSpeedMs`, 250–1500 ms,
default 700) changes that pace for next-line moves only. Missed frames advance the animation by at
most 32 ms, so a busy frame slows movement instead of catching up in a jump. Dots and crossbars
near the final word, or anywhere along the stroke just written (a cursive word is dotted and crossed
after it is finished), can resume an interrupted request after a fresh pen-up pause; corrections
to earlier words hold the view. Rejected palm contacts do not cancel a pending glide.

## Handwriting height and the writer's margin

A line's height is the median of its own body strokes once it has three; until then the writer's
hand fills in: the last 24 body-sample heights survive returns, natural new lines, navigation and
tool changes (`WritingFollowState.heightSamples`), so the first strokes of a line, the line-change
threshold and spacing no longer fall back to a fixed 24-unit guess. Only a page change forgets it.

Full lines teach the writer's margin (`lineEnds`, `learnedLineEnd`). The end of a line is recorded
on a natural new line or a downward return when the line spans at least six letter heights; the
densest cluster of the last six ends (within 2.5 letter heights) is the margin, so a paragraph's
short final line or one overlong word does not move it. When the frontier reaches that margin
inside the area, the line counts as finished exactly as at the area's edge: automatic return
counts down, or *Next line ready* is shown. A writer who stops short of the column or page edge
therefore still gets their returns. Going up a line is a correction and records nothing.

Changing tool (eraser, lasso, highlighter and back) only stops movement in progress. The baseline,
frontier and Back history stay, so erasing a word mid-line and writing on continues the same line.

## Navigation, feedback and accessibility

- **Back is multi-step.** Each follow move (sideways glide, placement, return) is its own entry in
  `FollowBackHistory`, up to 8; Back (toolbar, menu, Alt+Backspace) reverses them newest first and
  restores the follow state from before each move. Manual navigation still clears the history.
- **Previous writing line** (options menu, Alt+↑) goes up one printed rule, or one line spacing on
  blank paper and canvases, to the line start (`FollowNavigation.previous`). It never leaves the
  answer block or the area's top. Alt+↓ is Next line.
- **Line spacing scales with the handwriting.** Until two natural breaks teach the spacing, blank
  pages and canvases use at least 1.6× the median stroke height (`SPACING_PER_HEIGHT`, after three
  strokes), so large writing or a zoomed-out canvas does not land Next line on top of the last line.
  The configured spacing remains the minimum; *Adapt spacing* off uses it exactly.
- **Undo and erasing retract the line.** Removing pen ink cancels a pending return or glide and
  pulls the frontier back to the furthest writing still on the line (`WritingFollow.retract`), so
  an undone word cannot trigger an early return; a line emptied completely starts afresh at the
  same height.
- **Landing marker.** While a return counts down, during a line move and after arrival until the
  next accepted stroke, a short dashed baseline and dot in the accent colour mark where the next
  line starts. *Mark where the next line starts* in the settings (`follow.showLanding`) hides it.
- **Hints when the view holds.** Messages that explain a still or blocked view (outside the area or
  column, zoom too small, end of the answer area, view edge, first line) appear for about three
  seconds above the follow controls as a polite live region; the full status stays in the menu.
- **Reduced motion.** With system animations off, follow moves land in one frame instead of
  gliding. Pauses before moving are unchanged.

## Infinite canvas: free working and responses

Canvases open in free working, including mistake reviews. Global writing-follow enablement and
Text/Maths mode never authorize canvas movement. Maths, short answers, diagrams and mind maps
stay still until the student pans or zooms. The Next line stylus shortcut does not silently start
canvas follow. Finite pages retain their existing controls.

**Write a response** explicitly starts a prose session for this canvas visit. It captures a column
from the visible viewport with proportional margins and draws its two edges. Column coordinates
stay fixed through follow pans, manual navigation, zoom, resize and natural line breaks. **Start a
new column here** deliberately replaces it. Manual Next line returns to the column margin; an
automatic list wrap can still use a hanging indent. Writing outside the column holds the view.
A canvas has no edge to stop a pan, so a response frames its column rather than aiming the line
start at a screen position (`CanvasWritingSession.framedLeft`): while the column fits on screen,
sideways follow, natural line placement and returns never slide the view sideways (a column
pushed partly off screen comes back with the least movement); once zoomed in past the column's
width, sideways movement follows the writing but never shows more than a small margin beyond
either column edge. The session and the See all working return point are saved UI state, so
rotation, window resizing and split screen keep them.

Automatic return starts off in every new session, regardless of the global page setting. It can
be enabled in **Response settings** for that session only. **Finish response**, changing pages or
leaving the editor ends the session. Sessions are transient, with no notebook or ink format changes.
Navigation still cancels motion and resets handwriting evidence, but does not redefine the column.
Pages use the configured minimum zoom; canvases use handwriting's actual height on screen.

**See all working** saves the exact canvas camera before fitting the content. **Return to working**
restores its position and zoom, including after panning around the overview. Follow is disabled
while inspecting the overview. These actions work in free working as well as during a response;
the pinned peek remains available for checking the question without moving the working view.

A small cluster at the start of a line (`1.`, a bullet, a dash) followed by a clear gap is treated
as a list marker. When such a line runs to its end and returns automatically, the wrapped text hangs
from where the text began; a manual Next line still returns to the marker column for the next item.
Nothing is stored. A one-letter first word can look like a marker, which only lands the return one
word in.

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

## Answer area box

The box drawn around the selected or detected answer area is a dashed outline in the app's accent colour
(`MaterialTheme.colorScheme.primary`, so it follows the theme). Its dash and width are fixed on screen
(sized in dp and divided by the canvas scale), so they look the same at every zoom. *Follow options →
Answer areas… → Show answer area box* (`follow.showAnswerAreas`, on by default) hides it; this only
hides the drawing, never the area itself, and an area being dragged out is always shown.

## Checks

Run the canonical signed experimental build/publish check, then the pure regression smoke check:

```sh
./build.sh -p
node tools/writing-follow-smoke.cjs
node tools/katex-smoke.cjs
```

The follow smoke check compiles the actual pure Kotlin helpers with Gradle's cached compiler and
runs deterministic handwriting and animation traces. It needs Node, JDK 17, and the compiler cache
populated by the Android build; it downloads nothing and leaves no generated files in the repo.

## Device check

Use a stylus on a ruled page, a blank page, an imported PDF with several separate answer blocks,
and an infinite canvas. Repeat at a comfortable writing zoom and with automatic return enabled.

1. Write words containing `f`, `g`, `j`, `p`, and `y`, including as the first and final letter.
   Check sideways following and the next-line return. Repeat with small letters and joined-up words.
2. Finish near the visible edge, then dot an `i` or cross a `t`. Sideways movement should wait
   through the brief lift and restart its full pause after the mark. Compare glides just before
   and past 90% across the viewport: the speed should not jump. Repeat with Gentle selected,
   at different zoom levels, and after stopping a glide partway through. Larger pans should
   take longer. At the answer area's end, the return should also restart its pause.
   Correct an earlier word instead: the view should hold. Rest a palm during the pause: rejected
   palm contacts should leave the request intact.
3. Start a natural next line with a tall capital, then a short letter or a descender. Continue
   writing during the placement pause and touch down midway through a glide. The next pen-up
   should replan placement without losing the line or moving an extra line down.
   On blank paper, retrace the first letter before continuing: retracing should hold the view,
   and a nearby following letter should confirm the line. Repeat in RTL. Also try a distant
   annotation at the same height; it should not corroborate the first mark as a new line.
4. Use Next line, stop its glide halfway, then either continue the old line or write the new one.
   Check that the resumed movement targets the actual position. At a scroll limit, sideways
   movement must not falsely report a successful vertical return. Check Back after each case.
5. Open a mistake-review canvas with global page follow and automatic return already enabled.
   Write a fraction, a short answer, a diagram and a mind map; the camera must stay still. The
   Next line stylus shortcut must not enable follow. Tap Write a response: two column edges
   appear, with automatic return off. Write paragraphs, use Next line, and try an RTL response.
   Zoom, pan, resize and move above/left of the origin: the column edges must retain their canvas
   coordinates. Across several lines and Next line, the column's margin must stay where it was
   on screen (no sideways slide, never mid-screen). Zoom in past the column width: following
   must stay within the column. Rotate and resize mid-response and in the overview: the column and
   Return to working must survive. Outside-column annotations must hold the view. Try Start a new column here,
   Finish response, switching pages, leaving/reopening and enabling auto return for one session.
   See all working, pan around, then Return to working: the original position and zoom should
   return exactly. Repeat during a response and verify there is no follow movement in overview.
6. Write on the final printed rule with descenders below it. Sideways following should continue;
   automatic return must not spill into the next question or an adjacent answer column.
7. Write three lines with follow and auto return, then press Back three times: each press should
   undo one move. Use Previous writing line and Alt+↑/↓/Backspace with a keyboard attached. Write
   large on a blank page: the first Next line must clear the descenders. Undo the last word during
   a return countdown: the return must cancel and not fire until the line reaches the end again.
   Watch the landing marker appear during the countdown and vanish on the next word. Write outside
   an answer area: the hint should show briefly. Turn on *Remove animations*: moves should jump.
8. Write a paragraph quickly without pausing between words: sideways following should happen
   within the first line or two, during word gaps, rather than only when you stop. Write slowly:
   waits should not get shorter than configured. Keep a margin well inside a wide column for three
   full lines with automatic return on: the fourth line should return at your margin. Erase a word
   mid-line with the eraser, switch back to the pen and continue: the line must continue without
   a new placement. Write very small and very large: the first stroke after each return must stay
   on its line.
9. In Maths mode, grow a fraction/equation downward and use Next line. Horizontal follow should
   remain off. Check Pause/Resume, both follow-axis toggles, fixed/adaptive timing, and shape tidy.

These traces exercise geometry and state transitions, but they do not substitute for real stylus,
palm rejection, frame timing, and page-scroll checks on a device.
