# Writing follow and Zoom pane

## Zoom pane

Open **Writing & peek → Open zoom pane** in the editor. The upper view becomes a navigation overview with an accent outline marking the exact rectangle shown in the lower writing pane. Ink, erasing, layers, and Undo all use the standard editor and save path; the overview displays the live stroke without committing a second copy.

- Drag the source frame to reposition the window, drag its bottom-right handle or pinch inside the pane to change magnification, and drag the divider to resize the pane (preferred height saves in `AppPrefs`).
- Resizing preserves page-space scale where possible, rotation restores the latest window, and the overview stays still until the source frame nears its edge.
- Controls: **Back view** (reverses camera moves without touching ink), **Reveal ahead** (keeps 30% overlap up to the answer margin), **Next line**, **Pause**, **Options**, and **Close** (pauses follow and returns tracking/indents to the main editor). Text, lasso, sticky-note editing, and laser return to the full editor.

## Follow rules and edge strip

Both views share `WritingFollow`, `WritingGuides`, `FollowRhythm`, and `FollowGlide`:

- A visible-window edge triggers horizontal reveal (not a line boundary); printed guide blocks and selected answer areas bound line returns.
- On PDFs, a labelled first rule (`Advantage ____`) belongs to the full-width line beneath it so returns align under the label, and the next label begins a new block (label letters resting on a rule are trimmed off).
- Corrections and finishing marks cancel pending moves; natural line breaks supersede pending returns; the final guide stops at the answer area; touch-down freezes the camera before sampling; system cancellation or layout changes cancel live input cleanly.
- **Edge strip:** The pale trailing strip is the exact sideways reveal zone. When a completed pen stroke reaches it and lifts, Folio waits the full **Pause before moving** delay before gliding (any new touch-down cancels the wait or glide). Because it checks raw stroke bounds rather than handwriting recognition, dots, retraced marks, tall letters, and held shapes reveal room even before a baseline is established, without inventing line-return evidence.
- **Room before moving** adjusts both the trigger boundary and strip width together (mirrored for RTL, hidden once the answer area's trailing edge is visible). Return timing starts at the configured minimum pause and adapts after three confirmed natural line breaks, keeping the pause before a candidate line's first stroke. The next-line target previews in both views with a status countdown.

**Lines & indentation** in **Writing & peek** groups **New paragraph · leave a blank line**, **Indent next lines**, and **Reset indent**. Explicit indents override inferred list indents; in Maths mode, line returns stay manual.

## Infinite canvases

Infinite canvases still require an explicit `CanvasWritingSession` (opening the zoom pane starts one only when needed). A new response inherits the global automatic-return setting:

- The trailing margin comes from the starting viewport; the first pen stroke sets the leading margin and column top (half a line above the stroke) so writing begun beside earlier work returns under its own first word.
- Until that first stroke, line/indent/reveal actions prompt you to start writing and no margins draw. **Start a new column** resets the column to wait for the next stroke.
- Columns survive pan, pinch, resize, and view switches. **See all working** switches to the overview while pausing follow; **Return to working** restores the camera; **Finish response** closes the pane and ends the session.

## Settings and answer-area detection

All global controls live in **Settings → Writing follow** (opened directly from both editor **Options** buttons), save immediately, and stay live-synced with open editors:

- **Writing follow:** Follow on pages, Text/Maths, writing direction, hand.
- **Movement:** **Feel** (`FollowFeel` presets tuning `returnDelayMs`, `room`, and `pace` together; non-preset mixes show as Custom), **Move the view** (sideways and down / down only / sideways only / only when I tap; Maths offers down or tap), and **Writing height**.
- **Next line:** Automatic return, learn my line spacing, line spacing.
- **Answer areas:** Detect automatically.
- **Reset writing follow:** Restores every default, including hidden tuned values (`horizontalPosition`, `endMargin`, `showLandingGuide`, `edgeStripOpacity`, `minimumZoom`, `autoSwitchAreas`, outlines, zoom height, auto peek).

**Detect answer areas automatically** (`WritingGuides.analyze`, pure) runs in scrolling view, one-page view, and the zoom pane once PDF rendering finishes, selecting the detected area nearest the viewport. It recognises:
1. Groups of printed response lines (solid, grey, dashed, or dotted, including inside ruled boxes),
2. Labelled form rows and inline blanks (`Oxygen demand ____`, `Answer ____ cm`), and
3. Empty answer boxes or table cells (including top/side labels like `Working space`).

It rejects chart gridlines, ticks, arrowheads, plotted lines/bars, blank sketch grids, dotted leaders, diagram borders, and isolated empty cells in filled reference tables. Tuned across 48 documents (VCAA 2019/2021/2023/2025 exams across 15 subjects, AQA GCSE papers, examination reports, IRS forms, and blank-less papers), with synthetic regressions in `writing-follow-smoke.cjs`. Neighbour previews and Music never auto-select areas, and pages with no rules never auto-enter manual area drawing.

Legacy preference keys (`follow.edgeThreshold`, `follow.verticalDeadBand`, `follow.glideMs`, `follow.lineSpeedMs`, `follow.edgeStripWidth`) migrate once on read in `FollowPreferenceStore` into `follow.room` and `follow.pace` and are removed on the next write.

## Peek view

**Peek** stores one anchor per notebook (`Notebook.peekAnchor`). Hold the eye button to glance and release to return, or tap to keep it open. A kept-open peek pans and zooms freely and offers **Re-centre**, **Pin** (updates the anchor to the current framing; hidden when whole-page auto peek is active on a fixed page), **Go to**, and **Close**, with the page name and title in its bar. No storage format change is required.

## Verification

- **Automated:** Run `node tools/writing-follow-smoke.cjs`, `node tools/palm-rejection-smoke.cjs`, and `./build.sh -p`.
- **On a OnePlus Pad 3:**
  - Test ruled pages and PDF answer blocks: write, erase, Undo, close, and reopen, confirming alignment, pressure, and single undo entries.
  - Check horizontal reveal stays on baseline, answer-margin return targets the next guide, and the final guide never crosses into another question.
  - Dot/cross a letter during countdown and touch down during a glide: motion must freeze before sampling with no double line step, and **Back view** must reverse actual moves.
  - Drag/resize the source frame, pinch, rotate, and resize the window; confirm cancelled live strokes are discarded and palms on the divider/overview never move the pane or interrupt the pen.
  - Test infinite-canvas response placement before/after first stroke, pan/zoom/overview, and rotation.
  - Adjust every follow setting while an editor is open, test **Room before moving** 1% steps across RTL/hands/landing positions, test strip hugging during glides/pans, verify dots/tall letters/held shapes reveal room while scribble/sticky/paused strokes do not, test strip visibility levels and Reset, verify PDF auto-detection across view switches (and never in Music), and check outside-area status stays in the menu without popups.
  - Check RTL, dense pages, hidden/locked layers, indents, paragraph spacing, reduced motion, and narrow split-screen widths.

*Note:* Two-line PDF calibration, a side dock, independently pinned question context, and a continuous overlap-transfer zone remain future extensions in the proposal doc.
