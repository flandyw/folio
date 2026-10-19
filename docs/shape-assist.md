# Tidy up shapes

The Shapes picker has **Draw & hold with the pen**, which turns on recognition and returns to
the pen without replacing the last explicit shape. Pen/highlighter settings and Settings →
Writing → Tidy up shapes share the same controls:

- Quick (350 ms), Balanced (650 ms) and Deliberate (1100 ms) hold speeds, plus a custom delay.
- Independent recognition of lines, arrows, closed shapes, and curves & brackets.
- Optional exact circles/squares for nearly round/square drawings (short/long side ratio ≥ 0.8).
  Deliberately elongated rectangles and ovals keep their proportions.
- Irregular triangles (scalene, right-angled, acute or obtuse) retain their drawn sides, angles
  and orientation, including a short side or shallow corner. Nearly flat retracing stays ink.
- Optional equal-sided triangles, pentagons, hexagons, heptagons, octagons, four-, five- and
  six-point outline stars, and pentagrams. Orientation and centre follow the original drawing;
  quadrilaterals retain their own geometry.
- Straight highlighter underlines that preserve the highlighter tool, opacity, colour and width.
- A disposable practice area with Undo and Clear, using the real editor input pipeline.

## What it recognises

| Family | Shapes |
| --- | --- |
| Lines | Straight lines (and straight highlighter underlines) |
| Arrows | A shaft with a head, drawn as one stroke from either end; the shaft may be straight or a true arc |
| Closed shapes | Circles and ovals (any angle), boxes, rounded boxes and pills, triangles, any convex polygon up to eight sides, quadrilaterals (trapezoids, parallelograms, diamonds), four-, five- and six-point stars, one-stroke pentagrams, hearts |
| Curves & brackets | Arcs up to about 215°, square brackets `[ ]`, curly braces `{ }` (any orientation) |

Everything is ordinary ink. Lines, polygons and arrow parts are `LINE` strokes, boxes and ovals
use the native `RECTANGLE`/`ELLIPSE` tools when level, and the rest (rotated ovals, hearts, arcs,
brackets, braces, rounded boxes, curved shafts) are uniform-pressure `PEN` outlines, so every
existing codec, eraser and selection tool already understands them. Rounded boxes and pills are
`PEN` outlines because the editor has no rounded-rectangle tool.

## How it decides

Live draw-and-hold uses Google's on-device ML Kit Digital Ink Recognition 19.0.0 `SHAPES`
classifier for rectangles, ellipses, triangles and arrows. `InkGestureRecognizer` shares one
client and downloaded model across editor and practice surfaces. Settings show model download
status and offer Retry; these four families stay freehand while the model is unavailable.
The model chooses the family; `ShapeRecognition.fitClassified` and `ShapeFit` fit ordinary ink
with the original style and layer. Lines/highlighter underlines and Folio's extra families
(rounded boxes, other polygons, stars, hearts, arcs and brackets) retain deterministic recognition,
as Google's model has no labels for them. Lasso cleanup keeps its existing deterministic,
explicit preview workflow, including shapes drawn in multiple strokes.

Fitting and inference run off the input thread. Only a deliberate pen-down hold can request a
snap. Lifting, cancellation, a restarted hold, page/revision changes, detachment, tool/layer or
option changes discard pending results; a late classifier never converts committed handwriting.
ML Kit scores are not treated as probabilities, and no uncalibrated numeric threshold is used.
Real-device classifier accuracy still needs checking against maths handwriting.

Recognition fits models instead of counting corners, so a wobbling pen cannot add or remove one
(`ShapeFit.kt`, `ShapeRecognition.kt`):

- **Closed outlines.** A dynamic-programming fit finds the best closed outline of 3–12 straight
  sides starting from the sharpest turns. Corners are then re-found where lines fitted through
  each side meet (so rounded corners and bowed sides do not move them). The result is classified
  as a convex polygon, a star (alternating turns, outer/inner radii) or a pentagram (five turns of
  about 144°). It must fit within 3% of the drawing's size (less across a thin shape), every
  corner must sit on a real sharp turn in the stroke, and a polygon of many sides must beat the
  best-fit ellipse clearly and have corners much sharper than its sides. A star must be nowhere
  near an ellipse, which keeps scalloped clouds freehand.
- **Round outlines.** A least-squares ellipse (Sampson distance) with the drawing's own axes. A
  rounded-box fit (exact signed distance) wins only when it is at least twice as accurate as both
  the ellipse and the sharp polygon and the corners are rounded by at least ~45% of the half
  thickness, so slightly softened boxes stay rectangles.
- **Hearts.** A heart template stretched to the drawing's box and turned up to 30°; it must beat a
  pentagon/quadrilateral/ellipse fit of the same stroke.
- **Open strokes.** A straight line stays within a pen's wobble of the chord. An arrow is the best
  four-run chain through the stroke's corners, measured to true segment distance, tried from both
  ends; a curved shaft is five or six runs whose shaft part fits an arc. Arcs, brackets and braces
  are compared, in a frame where the stroke's ends sit at (0,0) and (1,0), with three templates
  (circular arc through the ends, square bracket, curly brace) and the closest wins if it is
  within 3% of the chord.

Open curves need at least 50 page units and a bulge of at least 6% of the chord, and an arc stops
at about 215°, so a letter C, S, L, V, U or J, or a check mark, stays as written.

Recognition forgives how people actually draw: overshooting the start of a closed shape (the
overlap is cut at the crossing), stopping a little short of it, a short hook where the nib
landed or flicked off a line, and boxes with rounded corners. Retracing a large part (a spiral,
a second lap, a "6") is not an overshoot and stays freehand.

Draw, pause gently with the tip down, adjust, then lift. A clean preview, a screen-sized
“Move to adjust” pill and an Android haptic tick confirm recognition. Settling allows 14 dp of
pen drift and gives excursions up to twice that radius an 80 ms grace period. Larger or
sustained movement restarts the delay; brief wobbles returning inside the radius keep the
original timer. Recognition averages the settling tail instead of treating repeated tremor
as extra drawn corners; the saved freehand source retains every original sample. After recognition the shape stays latched: moving the pen adjusts a line's
endpoint with its start fixed, resizes/rotates an arrow about its shaft start, or resizes/rotates
a closed shape about its centre. Straight and curved arrows keep the fitted arrowhead's original
size while their shaft length changes; the head moves with the tip and rotates with its direction.
Head recognition uses the head's own dimensions, so a small head can finish a long shaft.
Graph-tool axis arrowheads also keep a fixed size as the graph frame grows. A continuous 5 dp adjustment dead zone absorbs tip tremor.
Every update derives from the original fit, preserving proportions, angles, line width and
style without cumulative drift. Adjustment movement is excluded from the original freehand
samples and document capture, so Undo never restores a dragging tail. Lifting without a completed hold keeps the drawing. Cancellation, tool changes,
detachment and changes to recognition options discard the preview. The live feature is still
off until enabled; existing enabled/delay preferences retain their values.

## Existing diagrams

Lasso ink (including sticky-note ink), open selection options and choose **Tidy up shapes…**.
The panel recognises on a worker, shows Original/Tidied previews and the number of shapes (and
the source strokes they replace), and lets the user change recognition options for this cleanup.

Existing ink is cleaned as a whole (`ShapeCleanup.kt`), because diagrams are rarely one stroke:

- Boxes and polygons drawn line by line become one outline. Corners are where neighbouring lines
  meet, so sides that stop short of a corner or run past it both work.
- Outlines drawn in pieces (a circle in two halves, a box as two L shapes) join end to end and are
  recognised as one stroke.
- An arrow whose head is a separate V, or two separate wing strokes, joins its shaft. Both ends
  of a shaft can carry a head.
- Each end of a part joins at most its closest partner, parts must share colour and layer, and a
  joined shape must be at least 40 page units. Groups with no freehand member (ink tidied
  before) are left alone, and re-tidying ink that is already clean reports nothing to do.
- Marks under 40 units with another small mark beside them are handwriting and stay as written;
  a small circle with a label inside it still tidies.
- Nearly level or upright single lines (within about 2°) are made exactly so about their middle.
- A heart, arc, bracket, brace, rounded box or curved arrow drawn in one stroke tidies like any
  other single stroke; a closed one drawn in pieces (a heart in two halves) joins first.

A joined shape takes the stacking position of its first source stroke. It works even
when live hold recognition is off. Apply replaces recognised strokes in their original positions;
unrecognised ink, text, images, stacking order and layer assignments are retained. Note ink stays
in note-local coordinates. Hidden and locked layers are never changed.

Multi-page selections use the same panel. Pages and selection are frozen for the preview;
Apply validates notebook identity, current revisions and editable layers, then uses the existing
revision-checked document transaction. One Undo restores the selected original content across
all affected pages. Live page drawing retains its existing two-step history: Undo restores the
freehand source, then a second Undo removes it. A live sticky-note stroke retains its existing
single text-edit undo behavior.

`ShapeAssist.kt`, `ShapeRecognition.kt`, `ShapeFit.kt`, `ShapeHold.kt`, `ShapeAdjustment.kt`, `ShapeCleanup.kt` and `ShapeSelectionCleanup.kt` keep the
rules independent of Android UI. Options use one append-only preference string under
`AppPrefs.SHAPE_ASSIST_OPTIONS` (the seventh flag, curves & brackets, defaults on for preferences saved before it);
no notebook format or new ink tool is introduced. Near-level boxes and ovals (within about 4°) are
made exactly level. Recognition
rejects small marks, open loops, scribbles and multiply traced shapes. Highlighter recognition
accepts only straight lines, never closed shapes. Existing grid/15° snapping still applies to
held lines (ruler-constrained lines keep their ruler angle); lasso cleanup retains the original diagram placement without grid snapping.

Settings › Writing › **Hold at stroke start** can borrow the highlighter for one pen contact.
Its end hold uses this same recognition and adjustment path, with an independent timer and the
highlighter's saved styling. The toolbar keeps the pen selected; validation follows the live draft's
tool so the borrowed highlighter's recognition result can still be shown. See `docs/pen-feel.md`.

## Checks

- `node tools/shapes-smoke.cjs`: hold timing/drift/grace/cancellation, live adjustment and both winding directions,
  rotated geometry, polygons/stars/arrows, rejected writing, all option round trips, exact
  circles/squares, elongated shapes, highlighter style and cleanup ordering, overshoot/hook/rounded
  corner forgiveness, multi-stroke boxes, triangles, split outlines and arrows, handwriting left
  alone and idempotent re-cleanup.
- `node tools/shapes-smoke.cjs --accuracy [-v] [-h]` is not a pass/fail check. It draws a few thousand seeded
  hand-style strokes of every shape (wobble, rounded corners, a start anywhere, either direction, overshoot
  or a gap, uneven pen speed, rotation; `-h` adds landing hooks and flicks) at careful, ordinary and hurried
  effort, prints how often each is recognised and what it was taken for, checks that writing-like strokes
  (zigzags, W/M/N/Z/S/U/J/D/P, spirals, loops, waves, small circles, a nearly closed C, a cloud) stay
  freehand, and times recognition. Run it before and after changing any threshold. `-x <shape> [-l 0|1|2] [-a]`
  explains the misses of one shape and `-s` prints the error, corner-contrast and sharp-turn statistics the thresholds came from.
- `ShapeSelectionCleanupTest`: positional replacement (including a box drawn line by line), journal inversion back to the original
  samples, locked/hidden layers, sticky coordinates and preserved page content.
- Canonical signed build: `./build.sh -p`.

On a device:

1. Use the new Shapes shortcut and try all hold speeds. Verify the hint is visible in a clipped
   scrolling page and disappears on lift, navigation, cancellation and tool change. Wobble gently
   during the delay: recognition should still fire. Keep drawing deliberately: the timer should reset.
   Once recognised, drag to adjust lines, arrows, circles, boxes and rotated ovals. Verify jitter
   does not resize them, and Undo restores the freehand source without adjustment tails.
2. Draw normal handwriting, dots, open arcs and scribbles; confirm these remain freehand.
   Turn each family off and check the other families still work.
3. Compare near-round/near-square and elongated shapes with exact geometry on/off. Check
   rotated triangles, boxes, ovals, polygons and stars in both drawing directions.
4. Hold a highlighter underline. Confirm its transparency and width, and that highlighting
   normal text does not tidy unless held. Test grid snapping and a ruler at a non-15° angle.
5. Practice with pen and finger; restore freehand using Undo and clear the test area. Navigate
   away and confirm the practice drawing was not saved in any notebook.
6. Lasso a mix of writing, rough diagrams drawn in several strokes (boxes line by line, arrows
   with separate heads, circles in two halves), pictures and sticky notes. Labels and words must
   stay as written. Compare previews, cancel,
   then apply. Check overlap order, editable layers, one-step Undo/Redo, reopening and export.
7. Repeat cleanup across two pages. While a preview is open, change the document through another
   surface and confirm applying the stale preview is refused.
8. Draw (and hold) a heart, a pill, a rounded box, an octagon, a four- and six-point star, a pentagram in
   one stroke, an arc, `[ ]` and `{ }` at several angles, and an arrow with a curved shaft; confirm each
   becomes clean ink that can be moved and rotated while held, that Undo restores the freehand drawing,
   and that turning off "Curves & brackets" or "Closed shapes" in the practice area stops exactly those.
   Write a page of ordinary handwriting with the hold speed on Quick: nothing should tidy unless held.
9. Check the controls and previews in light/dark themes, portrait/landscape, narrow split panes
   and larger text settings; repeat live hold on a music sheet and on an infinite canvas.

ML Kit device checks (the JVM checks exercise fitting and history, not Google's downloaded models):

- Enable recognition, observe the download status, retry a failed download, then test in airplane mode.
- Draw and hold rotated boxes, ovals, triangles and arrows; check their style, adjustment and Undo.
- Lift or move away while recognition is pending; switch page/tool/layer or Undo and verify no late snap.
- Scribble over paper, a sticky note and across page boundaries. Undo restores both the ink and scribble;
  another Undo removes the scribble. Repeat with hidden/locked layers and with a missing model.
- Write maths (`≋`, equals signs, x, 0/O), hatch graphs, and write normal text/Chinese characters over
  existing ink. Check that these stay as ink. Calibrate score thresholds only from real device samples;
  model scores are not probabilities, and this implementation relies on label agreement plus geometry.
