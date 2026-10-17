# Pen feel

How a stylus stroke looks and feels while it is written. Nothing here changes the storage format: the
saved samples are ordinary `InkPoint`s, and every option is either a render-time look or a different way of
choosing the numbers that were always stored.

## Latency

- **Predicted tail.** `InkView.recordForPrediction` feeds every stylus event to AndroidX
  `MotionEventPredictor` (platform model on newer Android, a Kalman filter before). After each move
  `refreshPrediction` asks for the predicted path and `StrokePrediction.clip` (pure) shifts it to start at the
  *drawn* tip (the pen filter can hold that a pixel behind the real pen) and cuts it at 28 screen pixels.
  `drawPredictedTail` paints it past the tip; it never enters the stroke. While the predictor has nothing
  yet (the first samples of a stroke) the old two-sample linear guess (`StrokePrediction.predict`) is used.
- **Low-latency ink (experimental, off).** Settings › Writing › Pen feel. `LowLatencyInk` puts one
  transparent `SurfaceView` over the window and draws each new piece of an opaque pen stroke straight into
  an `androidx.graphics` `CanvasFrontBufferedRenderer` front buffer. The page keeps drawing the same
  stroke underneath, so a late or failed layer costs nothing but a slightly heavier edge while writing; it is
  wiped ~48 ms after pen-up. Pen, on the page only (not notes, ruler, highlighter or translucent ink), API 29+.

## Smoothing

`PenSmoothing` (see `pen-smoothing.md`) also reads the **turn** at each sample: two legs of 4+ screen pixels
turning by more than ~55° shrink the lag budget and raise the cutoff for a few samples, so t-bars, e-loops and
a's keep their points at high smoothing. Tremor on short legs is not a corner, and the first ten samples of a
stroke never count (pen-down wobble must be smoothed, not passed through). An earlier version also relaxed
smoothing when the pen braked; that let pen-lift jitter through as a hook at stroke ends and was removed.

## Width

- **Response curve + Light / Normal / Firm** (pen tool options › Pressure). `PenPressure.sample` raises the raw
  reading to `curve` before sensitivity; the presets only fill the sliders. Stored per tool in `ToolOptions`.
- **Thinner when fast** (`input.penSpeedWidth`). `PenPressure.SpeedWidth` smooths speed in screen px/ms and
  feeds a 0.25–1 factor into the pressure maths, so it composes with (or replaces, when pressure is off) the
  pressure width. It is baked into each sample's stored pressure.
- **Tilt widens the highlighter** (`input.highlighterTilt`). A highlighter has one width per stroke, so the
  tilt (`AXIS_TILT`) at pen-down sets the stroke width (`PenPressure.tiltScale`). There is no pencil tool yet.
- **Soft stroke ends** (`input.inkTaper`, 0 = off). `InkTaper` thins the first and last 3–14 page units down to a quarter of the width. The renderer normally groups widths into half-unit runs; with taper on `drawTaperedLines` uses tenth-unit runs so the end narrows smoothly (coarse runs drew blunt, stepped nubs, and an old 0.68 floor clamp hid most of the taper). It is
  render-only, so it restyles old ink too; `InkTaper.version` makes every `InkView` drop geometry and rasters
  built under the old look. The live stroke only thins its start (the end is unknown until pen-up), and the
  live-geometry hand-off to the cache is skipped when taper is on.

## Drawing the stroke

Pen ink of varying width is one filled outline (`InkOutline`, pure): the union of a disc at each centreline
point and the tangent hulls between neighbours, all wound the same way so a non-zero fill paints it once.
Width follows every sample, so pressure ramps and soft ends are smooth (the old renderer drew width
*runs* snapped to half-unit steps, which showed as nubs and necks at stroke ends), and translucent ink no
longer darkens where runs overlapped. Discs are added only at the ends and where the stroke turns or changes
width; an even-width stroke is still drawn as one round-capped line, which is the same shape.

`StrokeEnds.settle` runs when a pen stroke is stored: the pen-down and lift samples take their neighbours'
pressure (Android often reports a full reading there, which drew a blob, a neck, then the stroke), and a
skid shorter than 1.5 pen widths (max 3 units) that turns more than ~57° off the stroke is dropped. The live
stroke fixes its first sample's pressure as soon as the second arrives.

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

- `node tools/pen-smoothing-smoke.cjs`, `node tools/handwriting-smoke.cjs` (pure maths; need a prior build for the Kotlin compiler cache).
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
9. **Low-latency ink:** with it on, write on a finite page, a canvas, in split view and with a floating toolbar; look for flicker or a ghost at pen-up, a double-heavy edge while writing, anything left behind after undo, and rotation/multi-window behaviour. Turn it off again and confirm the overlay is gone. This path has not been run on a device.
