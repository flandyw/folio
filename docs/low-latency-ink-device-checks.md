# Low-latency ink device checks

Manual plan for the front-buffer pen path. It has **not** been run. Until the results table below is filled and the pass rule at the end is met, keep describing the path as on by default and not yet device-verified.

How it behaves is in [pen feel](pen-feel.md). This file is the on-device procedure. Nothing here is covered by `node tools/pen-smoothing-smoke.cjs`, `node tools/handwriting-smoke.cjs` or `HandwritingInkTest`: those exercise prediction clipping, smoothing, refit and tidy, and they never create a `SurfaceView`.

## What you are checking

`LowLatencyInk` is one transparent `SurfaceView` per window, drawn with `androidx.graphics.lowlatency.CanvasFrontBufferedRenderer` (graphics-core 1.0.4). `obtain` returns null when the window decor is not a `FrameLayout`; a normal activity decor is one. `InkView.startLowLatency` turns it on at pen-down when all of these hold:

- Android 10 / API 29 or newer. `minSdk` is 26, and both the Settings switch and the pen Behaviour switch are hidden below API 29.
- `AppPrefs.LOW_LATENCY_INK` (`input.lowLatencyInk`) is true. The default is true, so a fresh install is on.
- `InkView.frontBuffer` is true. It defaults to true and no screen sets it false. The mistake-review sheet filters the printed paper only.
- The tool is the pen, the contact is a stylus, and the landing point is on the page. A stroke that starts on a sticky note stays on the page path. Opacity is at least 0.99, the stroke is ballpoint (`StrokeStyle.isBrush` is false), the stroke is not locked to the ruler, and the view is not read-only.

The page draws the same stroke underneath. Samples, pressure, undo and the journal come only from that page stroke. The layer is wiped about 48 ms after lift, or immediately on `cancel`. The overlay tail is the platform prediction clipped to 28 screen pixels. The short two-sample guess, used while the predictor has nothing yet, is drawn on the page canvas only.

If the API is too old, `attach` throws, or the surface is not ready yet, the stroke still commits through the page. There is no toast.

## Setup

Use two devices when you can:

1. A stylus tablet, such as a OnePlus Pad 3 on ColorOS with its pencil. Note the ColorOS version before calling a glitch an app bug; the digitizer has its own failures. See [palm rejection](palm-rejection.md).
2. A finger-only phone on API 29 or later, with **Draw with a finger** on, so the phone can actually lay down ink.

The APK packages `arm64-v8a` and `armeabi-v7a` only (`abiFilters` in `app/build.gradle.kts`, because of the OCR natives). Install on an ARM device. An x86 emulator will not take this APK. An ARM emulator can show a crash or a missing surface, and it is a poor stand-in for a pencil.

Install a build that contains this source. A debug install is enough to start the list. `./build.sh -n` makes the signed experimental APK and does not publish. Do not use `./build.sh -p` for this check. A release claim also needs the same list on an R8 build (`./build.sh -n -r`): release shrinking is on for that build and there is no graphics-core keep rule. Record which build you installed in the table. A debug-only pass does not clear the unverified note for a shipped release.

On the tablet, confirm Settings › Writing › Pen feel › **Low-latency ink** is present and on, and that the pen tool's Behaviour page shows the same switch. Search for "Low-latency ink" (the search keywords still include "experimental"; the switch itself does not say the feature is off). On the phone, do the same, then turn **Draw with a finger** on.

Have a blank finite notebook, an infinite canvas, a PDF page, a page with a sticky note, and a mistake-review question that can open **Paper**. Developer options › Show refresh rate is optional; display Hz is covered in [display refresh](display-refresh.md).

While writing, compare the setting on and off for the same gesture. "The overlay is in use" means a fast ballpoint stroke's leading edge stays closer to the pencil than with the switch off, and the tail clears at lift. If on and off look the same, including latency, record **overlay not observed**. That device has exercised the page fallback only. Correct committed ink is still required.

## Checks

Do these with the switch on unless a step says otherwise. After every stroke that should be saved: the ink matches the path you drew, pressure and start taper included, one undo removes it, one redo puts it back, and nothing else appears. The overlay is not an undo entry.

1. **Stroke start and end.** On a finite page, draw a dot, a short tick, a slow line and a fast loop with the ballpoint. The dot stays a dot. The line starts where the pencil landed and ends where it lifted. After lift, within a couple of frames, only that stroke remains: no spike, no second copy, no hole where the tail was. A heavier edge that disappears as soon as the ~48 ms wipe finishes is the handoff to the page. A heavier edge that stays, or a gap, is a fail. Repeat in a dark theme and a light theme, in red, blue and black.

2. **Prediction overshoot.** Draw fast loops, small `e` / `a`, and sharp zigzags. The tail stays within about 28 screen pixels of the tip (`StrokePrediction.MAX_TAIL_PX`). Reversing direction must not leave the old tail behind. Pause with the tip down, then continue: no tail remains during the pause. Lift: no tail remains. The first few samples may show only the page's short guess, because that guess is not copied into the front buffer.

3. **Pressure and width.** With pressure on, write one word light and one word hard, then with **Thinner when fast** on. Width while the pencil is down should track the page stroke you see with the switch off. Turn **Soft stroke ends** on and confirm the start thins and a dot does not vanish. Committed ink after lift must match the switch-off look for the same settings. Reset pressure to Normal when you finish.

4. **Tools that must stay on the page path.** For each of these, the stroke (or the trail) commits or fades exactly as it does with the switch off, with no extra leading edge and no ghost:
   - Highlighter, including translucent highlighter.
   - Pen at an opacity below about 99%.
   - Fountain, pencil, marker and calligraphy. Their prediction is in the brush mesh.
   - Eraser, including pressure size and whole-stroke mode, over ink you just drew.
   - Hardware eraser tip and the stylus primary button, while the pen tool is still selected.
   - Laser. Nothing is saved.
   - A stroke that starts on the ruler band. It stays on the ruler edge for its whole length.
   - A stroke that starts on a sticky note. It is note ink, clipped to the note, and undo affects the note.

5. **Tool changes and hold gestures.** Start a ballpoint stroke and switch to the eraser, lasso or hand before lifting. The stroke is cancelled, and no overlay ink remains. Hold at stroke start set to Highlighter, then to Eraser: the borrowed stroke uses that tool, the initial pen mark does not stay on the overlay, and undo is one step. Hold a scribble until the page tints the ink for deletion (hold to erase). The overlay may keep showing the scrub until lift. After lift the scrub is gone, the erased ink is gone, and one undo restores the erased ink. Hold for a shape preview. The next pointer event clears the overlay at once; lifting without that event clears it on the usual wipe. Afterwards only the shape remains. Move during the hold: the shape cancels and the stroke stays freehand.

6. **Undo and redo immediately.** Draw a stroke and undo as fast as you can, then again after a short pause. Undo during the wipe window can still show the overlay until that clear finishes. Once it has cleared, the page is blank. Redo shows only the committed stroke. Repeat with the toolbar undo and, if **two-finger tap to undo** is on, with that gesture. Close the notebook, reopen it, and confirm undo and redo still match the page. The layer must not come back on a stroke that was undone before you left.

7. **Rotation and configuration changes.** Finish a stroke, rotate, and write again. Committed ink stays in the right place on the page, and the new stroke tracks the pencil. Rotate mid-stroke: the contact is cancelled, the partial stroke is not saved, and no overlay remains after the new layout. Repeat after switching portrait and landscape twice more, then write a page of ordinary notes. Foldables or a resize that does not recreate the activity should keep writing; if the surface is destroyed, the next stroke may be page-only and the one after it should use the overlay again. An overlay that never returns is a fail.

8. **Split view and multi-window.** Open split view with a notebook in each pane. Write in the editor pane, then in the other pane. The two panes share one layer. After each lift, ink is only on the page you wrote on. Nothing sits on the divider, the other page, or the floating toolbar once the wipe has finished. During the stroke the surface is above the window and clipped to the writing view, so the leading edge can paint across floating chrome or a sticky note that overlaps that rect. Record whether it does. After the wipe, page ink is under notes and chrome again. Drag the divider while idle and while the pencil is down. Enter split-screen with another app and resize Folio's window between strokes and, if the pencil stays down, during a stroke. After lift, committed ink follows the page. A mid-gesture resize may leave the already drawn overlay in the old screen position until the wipe; that clears, and the next stroke aligns.

9. **Zoom and pan.** Pinch-zoom and pan with the pencil up, then write. The new stroke sits on the page at the new scale, on a finite page and on a canvas. Scroll a finite page so the stroke crosses the screen edge: the overlay stops at the paper, and the saved stroke does not smear along the edge. With the pencil down, a second contact is palm rejection's problem (check 10); the pen stroke must either finish intact or cancel cleanly, with no detached copy of the line left on screen. Open the zoom pane (**Writing & peek → Open zoom pane**). Write in the lower pane. The upper overview shows the live stroke and must not show a second front-buffer copy. After lift both views show one stroke. Drag the zoom divider, pinch inside the pane, and use Reveal ahead / Next line between strokes. Ink stays aligned in both views.

10. **Palm rejection.** With finger drawing on, rest a palm and write. No palm ink, and the pencil stroke is intact. Brush the palm and lift it, then bring the pencil in at once: the brush mark is withdrawn and undo does not restore it. A palm or a second finger on the divider or the overview, while the pencil writes in the zoom pane, must not move the pane or cancel the pencil. Cancel a contact (palm the system cancels, or lift the pencil into a rejected state if you can provoke `ACTION_CANCEL`). The stroke is not committed and the overlay clears. With the switch on, scrolling the library, tapping the toolbar and dragging the split divider must all still work after you have written. The surface is not focusable or clickable; if it eats touches, that is a fail.

11. **Writing follow and the zoom pane.** On a ruled page with follow on, write to the edge strip, lift, and let the view glide. Touch down during the glide: the camera stops before the new samples, and no overlay piece is left at the old screen position. A natural line return and **Next line** in the zoom pane do the same. On a canvas, start **Write a response**, place the column with the first stroke, and write a second line. The overlay follows the writing view's camera. The column and the committed ink agree after each glide. Pause follow and pan by hand, then resume and write. Repeat in the zoom pane. Sticky-note ink and a stroke outside the answer area must not be carried along by follow, with or without the overlay.

12. **Background and resume.** Home in the middle of a stroke. On return the partial stroke is not on the page and no overlay ink is stuck on screen. Home after a finished stroke, open another app, come back, and write. The finished stroke is still there. If Android destroyed the surface, the first stroke after resume may be page-only; the following ballpoint stroke should use the overlay again. Open the recent-apps switcher and rotate from there if the device allows it. Then leave the editor for the library and scroll, open Settings, and come back: the clear surface must not block those screens.

13. **Toggling the switch live.** On a fresh API 29 install the switch is on in Settings and in the pen Behaviour page, and both stay in sync. Turn it off while idle. The next contact that reaches `startLowLatency` removes the layer. An eraser or laser stroke does not, because those return before that call; the next pen or highlighter contact does. With it off, ballpoint prediction remains on the page, and committed ink matches what you had with it on. Turn it off in the middle of a stroke: that stroke keeps the layer until lift, and the next eligible contact removes it. Turn it on again. The first stroke may be page-only while the surface is created; it still commits. The next ballpoint stylus stroke should show the overlay. Repeat the toggle from the pen Behaviour page with the editor open. Force-stop and reopen: the switch is whatever you saved.

14. **Fallback when the front buffer is unavailable.** With the switch off, work through checks 1, 4 and 6 once. That is the supported fallback, and it is the page path. On an API 26–28 device, if you have one, the switch is absent in Settings and in the pen popover, ink still commits, and writing, undo and palm rejection behave as with the switch off. If logcat or the "overlay not observed" result says `CanvasFrontBufferedRenderer` never attached, keep testing committed ink and record **fallback only**. Do not mark that device as a front-buffer pass. There is no in-app switch that simulates a failed renderer.

15. **Other surfaces that share the ink view.** In the music reader, with performance mode off, pencil a mark on a fitted sheet, pinch the sheet, and turn the page. The mark is ordinary page ink and lines up with the staff after the wipe. Performance mode uses the hand tool, so it does not start the layer. On a mistake-review **Paper** sheet, in light and dark theme (Android 12+ for the dark paper), write in red and blue and with the highlighter. Ink keeps the colour you picked during the stroke and after lift. The dark filter stays on the printed sheet. Share the sheet and confirm the picture matches the white paper and the ink colour. A read-only neighbour preview never starts the layer.

16. **Across a page boundary.** In the scrolling editor, draw from one finite page onto the next. Prediction stops once the stroke spans. After the wipe, each page has its own segment, the gutter has none, and one undo removes the whole gesture. No overlay line remains on the first page. Then start on the page and run the pencil onto a sticky note. The part on the note is note ink and the part on the paper is page ink. After the wipe, nothing is left painted on top of the note.

## Results

Copy this table into the test notes. One row per device. Leave **Pass/fail** blank until that device has finished the checks that apply to it.

| Device | Android / OEM skin | Build (debug, `build.sh -n`, or `build.sh -n -r`) and version | Stylus or finger | Overlay observed? | Pass/fail | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| OnePlus Pad 3 (or other stylus tablet) | | | Stylus | | | |
| Finger-only phone | | | Finger | | | |
| API 26–28 device, if available | | | | | | |

**Overlay observed** is yes only when a fast ballpoint stroke is visibly closer to the pencil than the same stroke with the switch off. **fallback only** means committed ink was fine and the front buffer was not seen.

## When this can be called device-verified

All of the following have to be true before pen-feel, AGENTS.md and the wiki drop the "not yet device-verified" note:

- A stylus tablet on ARM, API 29 or later, has **Overlay observed: yes** and **Pass** on every check above, on the build you want to call verified. For a release, that build is `./build.sh -n -r` (or the signed release CI produces), not a debug-only install.
- A finger-only phone on API 29 or later has **Pass** for the checks it can perform. Finger strokes never start the layer. With **Draw with a finger** on, dots, writing, undo, zoom and the library still work with the switch on, and the switch toggles without blocking touches.
- Check 14's switch-off pass was done on the stylus tablet. An API 26–28 device is required only if you want the hidden-switch case recorded; absence of that device does not block a pass for API 29+.
- No open fail for a wrong committed stroke, a ghost after the wipe, a tail left after lift or a pause, undo/redo bringing back overlay ink, a cancelled stroke being saved, the surface eating touches, or the overlay staying missing after the surface has had a stroke to attach.
- The notes column records what you saw for the structural limits: the leading edge over floating chrome or a sticky note during the stroke, the first stroke after enabling or resume, and a mid-stroke window resize. Those notes can be "seen, cleared on wipe" without failing the run. They stay in this file so the next change does not treat them as a surprise.
- The results table in the test notes names the device, Android version, build and date. Update the status line at the top of this file in the same change that removes the unverified note. Until that change, the path stays not device-verified.
