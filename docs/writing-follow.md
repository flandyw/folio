# Writing follow and Zoom pane

Open **Writing & peek → Open zoom pane** in the editor. The upper page becomes a navigation view, with an accent outline showing the exact page rectangle displayed by the lower writing pane. Ink, erasing, layers and Undo use the normal editor and save path. The overview shows the live pen stroke; it never commits another copy.

Drag the source frame to relocate the window. Drag its lower-right handle or pinch inside the pane to change magnification. Drag the divider to resize the pane; the preferred height is stored in AppPrefs. Resizing preserves the current page-space scale where possible, and rotation restores the latest window rather than its original opening position. The overview stays stable until the source frame leaves its usable visible area.

The pane controls are **Back view**, **Reveal ahead**, **Next line**, **Pause**, **Options** and **Close**. Reveal ahead keeps 30% context overlap and stops at the selected answer margin. Back view reverses camera movement without removing ink. Closing pauses following and restores handwriting tracking/explicit indent to the main editor. Text, lasso, sticky-note editing and laser mode hand back to the full editor.

Both views use WritingFollow, WritingGuides, FollowRhythm and FollowGlide. A visible-window edge causes horizontal reveal; it is not an answer-line boundary. Printed guide blocks and selected answer areas bound line returns. On PDFs, a labelled first rule ("Advantage ____") belongs to the full-width line below it, so its return goes back to the margin under the label, and the next label starts a new block; label letters resting on a rule are trimmed off it rather than widening it. Corrections/finishing marks cancel pending movement, natural line breaks supersede a pending return, and the final guide stops at the answer area. Touch-down freezes the camera before new samples are drawn. System cancellation and pane layout changes cancel live input rather than committing it.

Sideways follow learns word pauses and urgency. Return timing starts with the configured minimum pause and can learn a longer pause from three confirmed natural line breaks. It retains the pause before the first stroke of a candidate line, not the shorter pause before the confirming letter. Canceled horizontal glides never shorten the return delay. The next-line destination is previewed in the writing view and overview, with a countdown in the follow status.

The Writing & peek menu groups **New paragraph · leave a blank line**, **Indent next lines** and **Reset indent** under **Lines & indentation**. Paragraph advances remain inside the selected block. Explicit indents take priority over inferred list indents, including on printed guides; reset restores the area's margin. In Maths, line returns remain manual.

Infinite canvases still require an explicit CanvasWritingSession. Opening the pane starts a response only when needed. Each new response starts with automatic return off, and its column survives pan, pinch, resize and view changes. See all working uses the upper overview while pausing the pane; Return to working restores that overview camera. Finish response closes the pane and ends the session.

All global controls are in **Settings → Writing follow**. The editor menu and zoom pane's Options button open that category directly. Controls save immediately and the open editor observes the same preferences without writing a stale copy back.

The page is grouped by the question a writer asks, and each behaviour has exactly one control:

- **Start here** — follow on pages, Text/Maths, writing direction, hand.
- **Feel** — three independent knobs: **Pause before moving** (`returnDelayMs`; sideways waits half), **Room before moving** (`room`, 0–1, which derives both the sideways trigger `edgeThreshold` .60–.90 and the downward dead band `verticalDeadBand` .07–.27) and **Movement speed** (`pace`, 0–1, which derives both the minimum glide 700→140 ms and next-line pace 1500→300 ms/screen, piecewise through the defaults at .6). **Start from** Relaxed / Balanced / Responsive sets only these three; Balanced is the default, and any other mix reads as Custom. Presets never touch hand, direction, writing position, line settings, line-end zone or automatic return.
- **Where your writing sits** — writing height, position after a sideways move, and the **Pale edge strip** (0–35%, default 10%, mirrored for RTL). The strip is a visual hint only; Room before moving decides when the view moves, and the movement-zone preview shows the real triggers.
- **Next line** — automatic return, **Line-end zone** (`endMargin`, a writing habit kept out of the presets), landing guide, learned/starting line spacing.
- **When following runs** — one **Move the view** choice (sideways and down / down only / sideways only / only when I tap; Maths offers down or tap) over the `horizontalFollow`/`verticalFollow` pair, and the minimum page zoom.
- **Answer areas**, **Zoom pane & peek**, **Reset**.

Older builds stored `follow.edgeThreshold`, `follow.verticalDeadBand`, `follow.glideMs` and `follow.lineSpeedMs` separately. `FollowPreferenceStore` reads them once to seed `follow.room`/`follow.pace` (averaging each pair onto the 5% slider grid; the old defaults map exactly to Balanced) and drops them on the next write. These are app preferences, not notebook data.

The editor menu keeps answer-area selection and line/indent/peek actions, plus **Auto-return for this response** as a per-visit canvas choice. Global return and mode defaults never start canvas following or turn on a new response's automatic return.

**Peek** keeps one view per notebook (`Notebook.peekAnchor`). The eye beside the follow controls opens it: hold to glance and release to return, or tap to keep it open. Pinning from the eye or **Writing & peek → Peek view…** confirms with a short message. A kept-open peek can be panned and zoomed, and its bar offers **Re-centre** (back to the pinned framing), **Pin** (replace the pinned view with what the peek shows now; hidden while whole-page auto peek is on for a fixed page), **Go to** (close the peek and go to that page; on a canvas the editor frames the peeked area) and **Close**. The bar names the page and its title. None of this touches the storage format.

No notebook, journal or snapshot format changes are involved. Pane cameras, source frames and visit state are presentation state.

## Verification

Run `node tools/writing-follow-smoke.cjs` for reveal boundaries/overlap, source movement/resize, natural-break return timing, explicit indents, RTL, existing correction/undo/guide rules and interrupted motion. Run `node tools/palm-rejection-smoke.cjs` for the shared router rules. The repository's Android check is `./build.sh -p`.

On a OnePlus Pad 3, check:

- Open a ruled page and a PDF answer block; write, erase, Undo, close, reopen and reload. Ink must align in both views, retain pressure, and produce only one ordinary stroke/undo entry.
- Approach the pane edge with answer space left: reveal stays on the same baseline. Approach the answer margin: preview/return targets the next guide. The last guide never advances into another question.
- Dot/cross a letter during a countdown and touch down during a return. The view must stop before new ink is sampled, with no doubled line step. Back view restores actual movement.
- Drag/resize the source frame, pinch the pane, rotate and resize the app window. Check coordinate alignment and restored magnification. An interrupted live stroke is canceled, not saved as a partial stroke.
- Keep a palm on the divider/overview while writing, including Android-canceled contacts. The palm must not move the pane or finish/cancel an unrelated pen.
- Start a canvas response with global automatic return on: the new session is still manual. Pan/zoom/switch views and inspect all working; the captured column remains fixed.
- Change every follow setting while an editor is open, close Settings and reopen it: values must agree, without resetting a canvas column. Open settings from both editor Options buttons and verify the Writing follow category opens directly. Set the pale strip to Hidden, 10% and 35%, test LTR/RTL, and confirm movement still begins at the same configured threshold. Check reset preserves saved answer areas and pinned peek views.
- Check RTL, dense pages, hidden/locked layers, indents, paragraph spacing, reduced motion and small split-screen widths.

Two-line PDF calibration, a side dock, independently pinned question context and a continuous overlap-transfer zone remain extensions described in the design proposal; the current pane uses pen-up reveals and the existing answer-area/spacing controls.
