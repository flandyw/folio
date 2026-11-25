# Tidy up shapes

The Shapes picker includes **Draw & hold with the pen**, which turns on shape recognition and returns to the pen without replacing your last explicit shape. Pen/highlighter options and **Settings → Writing → Tidy up shapes** share the same controls:

- **Hold speed:** Quick (350 ms), Balanced (650 ms), Deliberate (1100 ms), or custom delay.
- **Independent toggles:** Lines, arrows, closed shapes, and curves & brackets.
- **Exact circles & squares:** Optional snapping when short/long ratio ≥ 0.8 (elongated rectangles and ovals keep their proportions).
- **Polygons & stars:** Irregular triangles keep their drawn angles and orientation; optional equal-sided triangles, 5–8-sided polygons, 4/5/6-point outline stars, and pentagrams preserve orientation and centre.
- **Straighten highlighter:** Straight highlighter underlines keeping tool, opacity, colour, and width.
- **Practice area:** Disposable canvas with Undo and Clear running the real input pipeline.

## Recognised shapes

| Family | Shapes |
| --- | --- |
| Lines | Straight lines and straight highlighter underlines |
| Arrows | Shaft + head drawn in one stroke from either end (straight or curved arc shaft) |
| Closed shapes | Circles and ovals (any angle), boxes, rounded boxes and pills, triangles, convex polygons up to 8 sides, quadrilaterals (trapezoids, parallelograms, diamonds), 4/5/6-point stars, one-stroke pentagrams, hearts |
| Curves & brackets | Arcs up to ~215°, square brackets `[ ]`, curly braces `{ }` (any angle) |

Everything saves as ordinary ink: lines, polygons, and arrows use `LINE`; level boxes and ovals use `RECTANGLE` / `ELLIPSE`; rotated ovals, hearts, arcs, brackets, braces, rounded boxes, and curved shafts use uniform-pressure `PEN` outlines. No storage format change is needed.

## Live draw-and-hold pipeline

Live draw-and-hold uses Google's on-device ML Kit Digital Ink Recognition (`19.0.0`, `SHAPES` model) for rectangles, ellipses, triangles, and arrows (`InkGestureRecognizer` shares one client across editor and practice; Settings shows download status and Retry). Lines, highlighter underlines, rounded boxes, other polygons, stars, hearts, arcs, and brackets use deterministic model fitting (`ShapeFit.kt`, `ShapeRecognition.kt`):

- **Closed outlines:** Dynamic-programming fit of 3–12 straight sides from sharpest turns, re-intersecting fitted side lines for corners (within 3% of size); polygons and stars must clearly beat ellipses so clouds stay freehand.
- **Round outlines & hearts:** Least-squares ellipse (Sampson distance); rounded boxes win only when ≥2× more accurate than ellipse and sharp polygon with ≥45% corner rounding. Hearts match a template up to 30° tilt.
- **Open strokes:** Lines stay within wobble of the chord; arrows fit 4–6-run chains from either end; arcs, brackets, and braces match normalised templates within 3% (min 50 page units, ≥6% bulge, ≤215° so `C`/`S`/`L`/`V`/`U`/`J` stay handwriting).
- **Human forgiveness:** Trims closed-shape overshoots, bridges small gaps, ignores landing/lift hooks, and rejects multi-lap retracing (`6`, spirals).

**Hold & live adjustment:** Pause with the tip down (14 dp settling radius with 80 ms grace for brief excursions; settling tremor is averaged out while the saved freehand source keeps raw samples). A preview, *Move to adjust* pill, and haptic tick confirm snap. Moving the pen adjusts endpoints, resizes/rotates arrows (keeping fixed arrowhead size), or resizes/rotates closed shapes about their centre with a 5 dp dead zone and zero cumulative drift. Adjustment motion never enters the freehand source, so Undo restores the exact original drawing cleanly.

Inference runs off the input thread; lifting, cancelling, restarting, changing pages/tools/layers/options, or detaching discards pending results so late callbacks never alter committed handwriting. **Hold at stroke start** (borrowing the highlighter) shares the same end-hold recognition path.

## Existing diagrams (lasso cleanup)

Select ink (including sticky-note ink) with the lasso and choose **Tidy up shapes…** (`ShapeCleanupPanel.kt` over pure `ShapeCleanup.kt` / `ShapeSelectionCleanup.kt`). It works even when live hold is off:

- Joins multi-stroke boxes/polygons, split outlines (two-half circles or L-boxes), and separate arrow heads/wings sharing colour and layer (≥40 page units).
- Leaves small adjacent handwriting marks (<40 units) and already-tidied shapes untouched; levels nearly horizontal/vertical lines (within ~2°).
- Preserves stacking order, layers, sticky-note coordinates, and unrelated content; protects hidden/locked layers.
- Multi-page selections commit as one revision-checked document transaction (single Undo across pages). Live single-page snaps keep two-step history (Undo restores freehand; second Undo removes it).

Options pack into one append-only preference string (`AppPrefs.SHAPE_ASSIST_OPTIONS`).

## Verification

- **Smoke & accuracy:** `node tools/shapes-smoke.cjs` (pass/fail suite); `node tools/shapes-smoke.cjs --accuracy [-v] [-h]` (recognition rates on synthetic ink; `-x <shape>` explains misses; `-s` prints threshold stats).
- **Unit tests & build:** `ShapeSelectionCleanupTest` and `./build.sh -p`.
- **On device:**
  1. Test **Draw & hold** across hold speeds, gentle wobble, deliberate movement reset, live resize/rotate, and two-step Undo.
  2. Verify handwriting, dots, open arcs, and scribbles stay freehand; toggle each family independently.
  3. Test exact circles/squares on/off, rotated shapes in both winding directions, highlighter underlines (with grid snap and angled ruler), and the disposable practice pad.
  4. Lasso mixed writing + multi-stroke diagrams (single-page and two-page), test preview/cancel/apply, stale-revision rejection, and one-step Undo.
  5. Test hearts, pills, rounded boxes, octagons, stars, pentagrams, arcs, brackets, braces, curved arrows, light/dark themes, narrow splits, large fonts, music sheets, and infinite canvases.
  6. **ML Kit checks:** Test model download, failed download retry, airplane mode (certain ≥5-line pen scrubs still erase; loose/highlighter scrubs and circled words stay ink), and maths/hatching/CJK text over existing ink.
