# Pen feel

How a stylus stroke looks and feels while it is written. Nothing here changes the storage format: the
saved samples are ordinary `InkPoint`s, and every option is either a render-time look or a different way of
choosing the numbers that were always stored.

## Latency

- **Predicted tail.** `InkView` feeds every stylus event to `PenTipPrediction.record`, which wraps AndroidX
  `MotionEventPredictor` (platform model on newer Android, a Kalman filter before). After each move
  `InkView.refreshPrediction` asks it for the predicted path and `StrokePrediction.clip` (pure) shifts it to start at the
  *drawn* tip (the pen filter can hold that a pixel behind the real pen) and cuts it at 28 screen pixels.
  `PenTipPrediction.drawTail` paints it past the tip; it never enters the stroke. While the predictor has nothing
  yet (the first samples of a stroke) the old two-sample linear guess (`StrokePrediction.predict`) is used.
- **Low-latency ink (on by default).** Settings › Writing › Pen feel. `LowLatencyInk` puts one
  transparent `SurfaceView` over the window and draws each new piece of an opaque pen stroke straight into
  an `androidx.graphics` `CanvasFrontBufferedRenderer` front buffer, skipping the app/compositor frame queue.
  The predicted tail is drawn there too (otherwise the layer would show the real tip ahead of the page's guess and
  hide it): each input event wipes the previous tail's box, redraws the stroke segments under it, then draws the new
  tail, the same scheme androidx.ink's `InProgressStrokesView` uses. Widths match the page's live stroke (pressure
  scale and start taper). The page keeps drawing the same stroke underneath, so a late or failed layer costs nothing
  but a slightly heavier edge while writing; it is wiped ~48 ms after pen-up, and the tail is wiped by the lift
  sample itself. Pen, on the page only (not notes, ruler, highlighter or translucent ink), API 29+.

## Smoothing

`PenSmoothing` (see `pen-smoothing.md`) also reads the **turn** at each sample: two legs of 4+ screen pixels
turning by more than ~55° shrink the lag budget and raise the cutoff for a few samples, so t-bars, e-loops and
a's keep their points at high smoothing. Tremor on short legs is not a corner, and the first ten samples of a
stroke never count (pen-down wobble must be smoothed, not passed through). An earlier version also relaxed
smoothing when the pen braked; that let pen-lift jitter through as a hook at stroke ends and was removed.

## Hold at stroke start

Settings › Writing › **Hold at stroke start** chooses Highlighter (default), Eraser or Off
(`AppPrefs.PEN_START_HOLD`). With the pen selected, keep the stylus within 8 dp of its landing point
for 500 ms to borrow that tool until lift; a haptic tick confirms the switch. Finger ink, the
hardware eraser and the stylus eraser button retain their usual behavior. Moving beyond the radius,
including a historical sample, disarms switching for the whole contact, so an ordinary end hold
still recognises pen shapes. `PenStartHold` keeps this rule independent of Android.

The borrowed tool uses its saved options, including notebook-local options. Conversion replaces the
uncommitted pen draft and restarts document capture with the new tool; no pen dot or extra undo step
is saved. The eraser uses its own diameter, pressure and whole-stroke settings, including over sticky
notes and across pages. The highlighter keeps its colour, opacity, width and optional contact tilt;
with **Tidy up shapes** and **Straighten highlighter** enabled, a separate end hold straightens it
and supports the usual adjustment and undo. Lift, cancellation and tool changes clear the borrowed tool.

## Tool popover and brushes

The tool popover has two pages: **Style** (sample, brush, width, opacity, colours, line style, presets) and
**Behaviour** (smoothing, soft ends, speed, pressure, low-latency ink, shape assist, scribble to erase).
Only the open page is composed. Option changes reach the page at once, but `EditorToolState.changeOptions`
writes the preference 250 ms after the last change (flushed on tool change and when the editor leaves), and
the smoothing and soft-ends sliders commit on release, since a taper change restyles every pen stroke.

**Brush** is per stroke: the pen offers Ballpoint / Fountain / Pencil / Marker / Calligraphy and the
highlighter Round / Chisel (`StrokeStyle.brushes`). The brush is stored in the stroke's existing `style` name
(appended `StrokeStyle` entries, so no format change; an older build reads an unknown name as `SOLID` and draws
its plain pen), so a page can mix brushes and changing brush never restyles old ink. Each brush card, the
popover sample and the width-slot sample are drawn by the real renderer (`InkSample`). Each pen brush keeps
its own three width slots (`WidthPresets.group(tool, style)`); picking a brush keeps the width if it is one of
that brush's slots, else takes its middle slot. The quick bar has a brush button, the pen's toolbar icon shows
its brush, presets save the brush, and Restyle selection can change it (`InkGeometry.restyle(…, brush)`, which
never puts a line pattern on freehand ink).

*Soft stroke ends* and *Thinner when fast* belong to the Ballpoint only. The fountain pen has no distance taper (it
would shrink dots and taper tidied shapes and erased halves); it swings wider with pressure and always thins when fast (`InkView` bakes speed into
its stored pressure like the Ballpoint setting). Pencil pressure darkens it; the marker ignores pressure.
Every brush takes the Ballpoint's pressure width, so a normal press is the same width whichever brush is chosen.
The low-latency overlay is Ballpoint-only; other brushes draw the predicted tip through their own mesh.

## Width

- **Response curve + Light / Normal / Firm** (pen tool options › Pressure). `PenPressure.sample` raises the raw
  reading to `curve` before sensitivity; the presets only fill the sliders. Stored per tool in `ToolOptions`.
- **Thinner when fast** (`input.penSpeedWidth`). `PenPressure.SpeedWidth` smooths speed in screen px/ms and
  feeds a 0.25–1 factor into the pressure maths, so it composes with (or replaces, when pressure is off) the
  pressure width. It is baked into each sample's stored pressure.
- **Tilt widens the highlighter** (`input.highlighterTilt`). A highlighter has one width per stroke, so the
  tilt (`AXIS_TILT`) at pen-down sets the stroke width (`PenPressure.tiltScale`). There is no pencil tool yet.
- **Soft stroke ends** (`input.inkTaper`, 0 = off). `InkTaper` thins the first and last 3–14 page units down to a quarter of the width. AndroidX Ink consumes the per-point pressure × taper width through a custom brush. It is
  render-only, so it restyles old ink too; `InkTaper.version` makes every `InkView` drop geometry and rasters
  built under the old look. The live stroke only thins its start (the end is unknown until pen-up), and the
  live-geometry hand-off to the cache is skipped when taper is on.

## Drawing the stroke

Pen, highlighter and solid shapes render through AndroidX Ink, including live strokes, sticky ink,
rasters, thumbnails and exports. Folio still supplies the smoothed centreline and pressure × taper
width; a passthrough input model avoids applying a second smoothing or pressure response. Live
strokes append their settled prefix and replace their provisional tail. Completed geometries cache
an immutable native mesh. Patterned shapes, selection halos and the navigation preview keep their
Canvas paths, and `InkOutline` remains the fallback if AndroidX Ink fails. See [AndroidX Ink rendering](androidx-ink.md) for scope and device checks.

`StrokeEnds.settle` runs when a pen stroke is stored: the pen-down and lift samples take their neighbours'
pressure (Android often reports a full reading there, which drew a blob, a neck, then the stroke). Positions
are never trimmed: an earlier version dropped short sharp turns at the ends as pen skids, which cut the close
off an "o" and broke cursive joins. The live stroke fixes its first sample's pressure as soon as the second arrives.

## Stored strokes

- **Keep notebooks small** (`input.refitStrokes`, default on). `InkView.refitted` runs `StrokeRefit.simplify` as a
  plain pen stroke is finished: Douglas–Peucker at 0.04 page units, no kept gap over 3 units (the renderer
  stops smoothing across 5), pressure drift counted. On synthetic handwriting (0.6-unit steps) it dropped about 60–80% of samples.
  `HandwritingInkTest` checks the rendered curve stays within 0.2 units of the original.

## Tidy up handwriting

Lasso › … › **Tidy up handwriting…** (also the document-selection menu). `HandwritingTidy` (pure) groups pen
strokes into lines, levels each by sliding strokes vertically along a Theil–Sen slope of their centres
(max ~10°), and eases uneven letter and word gaps halfway by sliding clusters of strokes sideways. Strokes
only translate. `HandwritingSelectionTidy` turns that into one positional page edit; `HandwritingTidyPanel`
previews it and commits through `commitDocumentEdits` with the same revision checks as shape cleanup.

## Checks

- `node tools/pen-smoothing-smoke.cjs`, `node tools/handwriting-smoke.cjs`, `node tools/shapes-smoke.cjs` (pure maths; need a prior build for the Kotlin compiler cache).
- `./gradlew :app:testDebugUnitTest --tests '*HandwritingInkTest*'` (refit vs. the real renderer, tidy through the journal).

On a device (OnePlus Pad 3, ColorOS) — none of this can be seen in the smoke tests:

1. **Prediction:** write fast loops, small `e`/`a`, and sharp zigzags with the tail on; no spikes beyond the tip, no tail left after lifting or after the pen pauses.
2. **Corners:** at 100% smoothing write `t`, `+`, `V`, `Z` quickly; apexes should stay sharp, and a held pen should not shiver.
3. **Pressure feel:** Light / Normal / Firm on the same sentence; Reset restores Normal.
4. **Thinner when fast:** with pressure on and off; slow writing keeps the chosen width.
5. **Soft ends:** on, off, and a notebook with old ink; dots stay dots; toggling while the editor is open restyles the page; PDF/PNG export follows the setting.
6. **Highlighter tilt:** upright vs. flat contact widths; the stroke width does not change mid-stroke.
7. **Keep notebooks small:** write a page, close and reopen, undo/redo, export: it must look the same; compare the `.fps` size with the option off.
8. **Tidy up handwriting:** lasso a few tilted lines with an underline and a highlighter; preview, apply, undo once; a locked layer is untouched.
9. **Low-latency ink:** with it on (default), write fast loops and zigzags: the tail must track the tip with no stale spikes left behind mid-stroke or after lift; write on a finite page, a canvas, in split view and with a floating toolbar; look for flicker or a ghost at pen-up, a double-heavy edge while writing, anything left behind after undo, and rotation/multi-window behaviour. Turn it off again and confirm the overlay is gone. The front-buffer path (and its prediction) has not yet been verified on a device.
10. **Start hold:** choose Highlighter, hold at pen-down, draw and hold again at the end with shape assist enabled. Check saved highlighter colour/width/opacity, straightening and adjustment, undo/redo, then write immediately: the next stroke is pen ink. Repeat with notebook-local tool settings, tilt and the ruler, on a canvas, in split/music/zoom layouts, across a page seam and on a sticky note. Choose Eraser; check its saved diameter, pressure, whole-stroke mode and undo, both on notes and across pages, with no initial pen dot. Quick dots, immediate writing, finger ink, the hardware eraser and eraser button must keep working. Move away and return to the start, pause at a pen stroke's end, cancel a contact, switch tools and leave the editor during the hold: none may trigger a late switch. Off disables the shortcut. Device verification remains required for the physical gesture and haptics.

## Writing feedback (OnePlus / OPPO Pencil Pro)

With Settings › Stylus & touch › *Buzz while writing* on (`AppPrefs.WRITING_FEEDBACK`, default on; independent of the Bluetooth double-tap buzz), `PenWritingFeedback` asks ColorOS's open capability service for `IPE_PENCIL_CLIENT` and, once granted, calls the pen service's `startFeedBackVibration`/`stopFeedBackVibration` on pen down/up (`InkView.writingFeedback`). Each tool group has its own `WritingFeel` preference (`AppPrefs.WRITING_FEEL`, `WRITING_FEEL_HIGHLIGHTER`, `WRITING_FEEL_ERASER`); an unset choice means Match the tool (ballpoint, marker, eraser respectively), never inheritance from the pen's choice. Explicit saved choices are preserved. Settings shows the live `FeedbackStatus`. Folio asks under the licensed app names (`LICENSED_APPS`: HiPaint, then StarNote), never its own name. If all are refused, it stays silent.

The tablet's supplied `ocs.apk` distinguishes two authentication paths. The bind-intent path (`s2.a` → `h.b` → `t2.d.e`) reads the stated package's installed manifest AUTH_CODE and signing certificate; the app must therefore be installed with a valid licence. Error **1004** means an empty target package or a missing AUTH_CODE. The direct `IServiceBroker` transaction 1 instead resolves the real Binder caller's UID (`service.b.g` → `h.a`) and checks that app, so calling it from Folio produces a Folio authentication attempt regardless of the bind extras. Do not call that broker as a fallback.

Each bind adds a fresh category because Android caches bindings using intent filter equality, which ignores extras. Reusing only the capability's MIME type can return a cached broker without running OCS's `onBind` authentication again. The category keeps each identity attempt and editor visit separate while preserving the capability type and extras. See Android's [bound service lifecycle](https://developer.android.com/develop/background-work/services/bound-services) and [Intent.filterEquals](https://developer.android.com/reference/android/content/Intent#filterEquals(android.content.Intent)). This explains a possible silent binding; it does not establish why StarNote itself stopped vibrating.

On success OCS's `l3.m` notifies the protected pen manager with the licence's permits, PID, UID and package (transaction `16777215`), then returns its binder to the listener. This APK does not contain the pen manager's enforcement code, so an installed licensed app and a successful callback do not prove physical feedback will work. Device check: keep HiPaint (`net.huanci.hsjpro`) or global StarNote (`com.onyx.galaxy.global.note`) installed, open a notebook and inspect `adb logcat -s PenWritingFeedback`. Check that each attempt receives an authentication result; repeated `1004` means the stated installed package lacks the required licence. Once connected, write, erase and highlight and confirm the buzz runs only while the tip is down, stops on lift, palm cancel, second finger, page detach and app backgrounding, and never runs with lasso/hand/text/laser. If authentication succeeds but vibration fails, inspect the matching pen-manager APK/logs next.
