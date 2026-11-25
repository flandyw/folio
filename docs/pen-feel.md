# Pen feel

How a stylus stroke looks and responds while writing. None of these features alter the storage format: saved strokes remain ordinary `InkPoint` lists, and each option is either a render-time look or a different way of computing the stored coordinates and pressures.

## Latency

- **Predicted tail:** `InkView` feeds stylus events to `PenTipPrediction.record` (wrapping AndroidX `MotionEventPredictor`). After each move, `StrokePrediction.clip` (pure) anchors the predicted path at the *drawn* tip and clips it to 28 screen pixels; `PenTipPrediction.drawTail` draws it without ever saving it. Before the predictor warms up, a two-sample linear guess (`StrokePrediction.predict`) fills in.
- **Low-latency ink (on by default; not yet device-verified):** `AppPrefs.LOW_LATENCY_INK` (`input.lowLatencyInk`, default `true`) is controlled on the pen tool's **Behaviour** page on Android 10 (API 29) and newer. `LowLatencyInk` maintains one transparent `SurfaceView` per window backed by `CanvasFrontBufferedRenderer`, activating only for opaque (`≥ 0.99`) ballpoint (`!StrokeStyle.isBrush`) stylus strokes starting on an editable page (not ruler-locked, not on a sticky note; Mistake **Paper** is eligible). All other tools, brushes, translucent strokes, finger ink, ruler strokes, and sticky-note ink stay on the page path.
  - The page draws the same stroke underneath and owns all saved samples, undo, and journal entries. The overlay clears ~48 ms after lift (or immediately on `cancel`, pen-start borrow, or shape preview). Panes in one window share the layer, clipped to the visible page rect (not notes or floating chrome). See [Low-latency ink device checks](low-latency-ink-device-checks.md).

## Smoothing

`PenSmoothing` ([Pen smoothing](pen-smoothing.md)) protects sharp corners: two legs of 4+ screen pixels turning >55° raise the cutoff and tighten lag for several samples so `t`-bars, `e`-loops, and `a`s stay crisp at high smoothing. Short-leg tremor and the first ten pen-down samples never trigger a corner.

## Hold at stroke start

**Settings → Pen & touch → Hold at stroke start** (`AppPrefs.PEN_START_HOLD`) picks **Highlighter** (default), **Eraser**, or **Off**. Holding the pen tip within 8 dp of pen-down for 500 ms arms the tool (confirmed by a haptic tick); moving outside the radius borrows it until lift. Lifting inside the radius keeps an ordinary pen dot. Moving past the radius before 500 ms disarms switching for the rest of the contact so end-of-stroke shape hold still works. `PenStartHold` is pure.

The borrowed tool uses its saved options (including notebook-local options), replaces the uncommitted pen draft without leaving a dot or extra undo step, and supports eraser settings, highlighter tilt, and end-hold straightening. Finger ink, hardware erasers, and stylus eraser buttons keep their normal behaviour.

## Tool popover and brushes

The tool popover splits into **Style** (preview, brush, width, opacity, colours, line style, presets) and **Behaviour** (smoothing, soft ends, speed, pressure, low-latency ink, shape assist, scribble to erase), composing only the open page. Option writes debounce by 250 ms; smoothing and soft-ends commit on release.

**Brushes** are stored per stroke in `StrokeStyle` (appended entries, no format change; older builds read unknown styles as plain `SOLID` pen):
- **Pen:** Ballpoint, Fountain, Pencil, Marker, Calligraphy. **Highlighter:** Round, Chisel.
- Each pen brush keeps its own three width slots (`WidthPresets.group`); presets save the brush and **Restyle selection** can change it.
- *Soft stroke ends* and *Thinner when fast* apply to Ballpoint only. Fountain has no distance taper and always thins with speed; Pencil varies opacity with pressure; Marker ignores pressure. Every brush uses Ballpoint's pressure width so a normal press draws the same width across brushes.

## Width and stroke rendering

- **Pressure response (`PenPressure.sample`):** Raises raw readings to `curve` before sensitivity; Light / Normal / Firm presets fill the sliders per tool.
- **Thinner when fast (`input.penSpeedWidth`):** Smooths screen speed (`px/ms`) into a 0.25–1 factor baked into stored pressure.
- **Tilt widens the highlighter (`input.highlighterTilt`):** Captures `AXIS_TILT` at pen-down to set the stroke's constant width.
- **Soft stroke ends (`input.inkTaper`, 0 = off):** Render-only thinning of the first and last 3–14 page units down to 25% width (`InkTaper.version` invalidates cached rasters).
- **AndroidX Ink:** Pen, highlighter, and solid shapes render through AndroidX Ink with Folio's smoothed centreline and `pressure × taper` width; `InkOutline` remains the automatic fallback. See [AndroidX Ink rendering](androidx-ink.md).
- **`StrokeEnds.settle`:** Copies neighbour pressure onto pen-down and pen-up samples (preventing Android's full-pressure endpoint blobs) without trimming positions.

## Stored strokes and handwriting tidy

- **Keep notebooks small (`input.refitStrokes`, default on, no Settings switch):** Runs `StrokeRefit.simplify` (Douglas–Peucker at 0.04 page units, max gap 3 units, pressure-aware) at pen-up, dropping ~60–80% of samples on typical handwriting while staying within 0.2 units of the original curve (`HandwritingInkTest`).
- **Tidy up handwriting:** **Lasso → … → Tidy up handwriting…** runs pure `HandwritingTidy` (levels lines along a Theil–Sen slope ≤10° and eases uneven letter/word spacing by translating strokes only), previews via `HandwritingTidyPanel`, and commits with revision checks.

## Writing feedback (OnePlus / OPPO Pencil Pro)

**Settings → Pen & touch → Buzz while writing** (`AppPrefs.WRITING_FEEDBACK`, default on) binds ColorOS's open capability service for `IPE_PENCIL_CLIENT` under licensed app identities (`HiPaint`, then `StarNote`; never calling direct `IServiceBroker` transaction 1, which checks Folio's own UID). Each bind uses a unique intent category so Android's `Intent.filterEquals` cache never skips OCS authentication. Each tool group (`WRITING_FEEDBACK`, `WRITING_FEEL`, `WRITING_FEEL_HIGHLIGHTER`, `WRITING_FEEL_ERASER`) defaults to matching its tool. Settings displays live `FeedbackStatus`.

## Verification

- **Automated:** `node tools/pen-smoothing-smoke.cjs`, `node tools/handwriting-smoke.cjs`, `node tools/shapes-smoke.cjs`, and `./gradlew :app:testDebugUnitTest --tests '*HandwritingInkTest*'`.
- **On device (OnePlus Pad 3, ColorOS):**
  1. **Prediction:** Fast loops, small `e`/`a`, sharp zigzags: no tip spikes or lingering tails.
  2. **Corners:** At 100% smoothing, write `t`, `+`, `V`, `Z` quickly: sharp apexes, no held-tip shiver.
  3. **Pressure, speed, soft ends, tilt:** Test Light/Normal/Firm + Reset, **Thinner when fast** (pressure on/off), **Soft stroke ends** (dots stay dots; live restyle; export parity), and highlighter tilt.
  4. **Refit & tidy:** Verify reopen/undo/export parity and `.fps` size reduction; lasso tilted lines + underline + highlighter, preview, apply, undo once, and verify locked layers stay untouched.
  5. **Low-latency ink:** Run [Low-latency ink device checks](low-latency-ink-device-checks.md).
  6. **Start hold:** Test Highlighter (with end-hold straightening, notebook-local options, tilt, ruler, canvas, split/music/zoom, seams, stickies) and Eraser (diameter, pressure, whole-stroke, no initial dot), plus quick dots, hardware eraser, cancellation, and Off.
  7. **Writing feedback:** With HiPaint (`net.huanci.hsjpro`) or global StarNote (`com.onyx.galaxy.global.note`) installed, inspect `adb logcat -s PenWritingFeedback` (`1004` = missing licence) and verify buzz starts on tip-down, stops on lift/cancel/second finger/detach/background, and never runs on lasso/hand/text/laser.
