# Writing follow and Zoom pane

Open **Writing & peek → Open zoom pane** in the editor. The upper page becomes a navigation view, with an accent outline showing the exact page rectangle displayed by the lower writing pane. Ink, erasing, layers and Undo use the normal editor and save path. The overview shows the live pen stroke; it never commits another copy.

Drag the source frame to relocate the window. Drag its lower-right handle or pinch inside the pane to change magnification. Drag the divider to resize the pane; the preferred height is stored in AppPrefs. Resizing preserves the current page-space scale where possible, and rotation restores the latest window rather than its original opening position. The overview stays stable until the source frame leaves its usable visible area.

The pane controls are **Back view**, **Reveal ahead**, **Next line**, **Pause**, **Options** and **Close**. Reveal ahead keeps 30% context overlap and stops at the selected answer margin. Back view reverses camera movement without removing ink. Closing pauses following and restores handwriting tracking/explicit indent to the main editor. Text, lasso, sticky-note editing and laser mode hand back to the full editor.

Both views use WritingFollow, WritingGuides, FollowRhythm and FollowGlide. A visible-window edge causes horizontal reveal; it is not an answer-line boundary. Printed guide blocks and selected answer areas bound line returns. Corrections/finishing marks cancel pending movement, natural line breaks supersede a pending return, and the final guide stops at the answer area. Touch-down freezes the camera before new samples are drawn. System cancellation and pane layout changes cancel live input rather than committing it.

Sideways follow learns word pauses and urgency. Return timing starts with the configured minimum pause and can learn a longer pause from three confirmed natural line breaks. It retains the pause before the first stroke of a candidate line, not the shorter pause before the confirming letter. Canceled horizontal glides never shorten the return delay. The next-line destination is previewed in the writing view and overview, with a countdown in the follow status.

The Writing & peek menu groups **New paragraph · leave a blank line**, **Indent next lines** and **Reset indent** under **Lines & indentation**. Paragraph advances remain inside the selected block. Explicit indents take priority over inferred list indents, including on printed guides; reset restores the area's margin. In Maths, line returns remain manual.

Infinite canvases still require an explicit CanvasWritingSession. Opening the pane starts a response only when needed. Each new response starts with automatic return off, and its column survives pan, pinch, resize and view changes. See all working uses the upper overview while pausing the pane; Return to working restores that overview camera. Finish response closes the pane and ends the session.

All global controls are in **Settings → Writing follow**. The editor menu and zoom pane's Options button open that category directly. The page groups setup, movement feel, screen position, the pale edge strip, next-line return, spacing, answer areas, zoom/peek and fine movement controls. Movement thresholds and glide timings are expandable; controls save immediately and the open editor observes the same preferences without writing a stale copy back.

**Pale edge strip → Strip width** has a live preview and ranges from Hidden (0%) to 35% of the view; the default is 10%. It mirrors for RTL. This changes only the visual hint, not the sideways movement threshold or the answer margin. A separate movement-zone preview shows the actual horizontal/vertical triggers. The editor menu keeps answer-area selection and line/indent/peek actions, plus **Auto-return for this response** as a per-visit canvas choice. Global return and mode defaults never start canvas following or turn on a new response's automatic return.

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
