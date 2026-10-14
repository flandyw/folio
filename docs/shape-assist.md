# Tidy up shapes

The Shapes picker has **Draw & hold with the pen**, which turns on recognition and returns to
the pen without replacing the last explicit shape. Pen/highlighter settings and Settings →
Writing → Tidy up shapes share the same controls:

- Quick (350 ms), Balanced (650 ms) and Deliberate (1100 ms) hold speeds, plus a custom delay.
- Independent recognition of lines, arrows and closed shapes.
- Optional exact circles/squares for nearly round/square drawings (short/long side ratio ≥ 0.8).
  Deliberately elongated rectangles and ovals keep their proportions.
- Optional equal-sided triangles, pentagons, hexagons and five-point outline stars. Orientation
  and centre follow the original drawing; quadrilaterals retain their own geometry.
- Straight highlighter underlines that preserve the highlighter tool, opacity, colour and width.
- A disposable practice area with Undo and Clear, using the real editor input pipeline.

Draw, pause gently with the tip down, adjust, then lift. A clean preview, a screen-sized
“Move to adjust” pill and an Android haptic tick confirm recognition. Settling allows 14 dp of
pen drift and gives excursions up to twice that radius an 80 ms grace period. Larger or
sustained movement restarts the delay; brief wobbles returning inside the radius keep the
original timer. Recognition averages the settling tail instead of treating repeated tremor
as extra drawn corners; the saved freehand source retains every original sample. After recognition the shape stays latched: moving the pen adjusts a line's
endpoint with its start fixed, resizes/rotates an arrow about its shaft start, or resizes/rotates
a closed shape about its centre. A continuous 5 dp adjustment dead zone absorbs tip tremor.
Every update derives from the original fit, preserving proportions, angles, line width and
style without cumulative drift. Adjustment movement is excluded from the original freehand
samples and document capture, so Undo never restores a dragging tail. Lifting without a completed hold keeps the drawing. Cancellation, tool changes,
detachment and changes to recognition options discard the preview. The live feature is still
off until enabled; existing enabled/delay preferences retain their values.

## Existing diagrams

Lasso ink (including sticky-note ink), open selection options and choose **Tidy up shapes…**.
The panel recognises on a worker, shows Original/Tidied previews and the number of changed
source strokes, and lets the user change recognition options for this cleanup. It works even
when live hold recognition is off. Apply replaces recognised strokes in their original positions;
unrecognised ink, text, images, stacking order and layer assignments are retained. Note ink stays
in note-local coordinates. Hidden and locked layers are never changed.

Multi-page selections use the same panel. Pages and selection are frozen for the preview;
Apply validates notebook identity, current revisions and editable layers, then uses the existing
revision-checked document transaction. One Undo restores the selected original content across
all affected pages. Live page drawing retains its existing two-step history: Undo restores the
freehand source, then a second Undo removes it. A live sticky-note stroke retains its existing
single text-edit undo behavior.

`ShapeAssist.kt`, `ShapeRecognition.kt`, `ShapeHold.kt`, `ShapeAdjustment.kt` and `ShapeSelectionCleanup.kt` keep the
rules independent of Android UI. Options use one append-only preference string under
`AppPrefs.SHAPE_ASSIST_OPTIONS`; no notebook format or new ink tool is introduced. Recognition
rejects small marks, open loops, scribbles and multiply traced shapes. Highlighter recognition
accepts only straight lines, never closed shapes. Existing grid/15° snapping still applies to
held lines (ruler-constrained lines keep their ruler angle); lasso cleanup retains the original diagram placement without grid snapping.

## Checks

- `node tools/shapes-smoke.cjs`: hold timing/drift/grace/cancellation, live adjustment and both winding directions,
  rotated geometry, polygons/stars/arrows, rejected writing, all option round trips, exact
  circles/squares, elongated shapes, highlighter style and cleanup ordering.
- `ShapeSelectionCleanupTest`: positional replacement, journal inversion back to the original
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
6. Lasso a mix of writing, rough diagrams, pictures and sticky notes. Compare previews, cancel,
   then apply. Check overlap order, editable layers, one-step Undo/Redo, reopening and export.
7. Repeat cleanup across two pages. While a preview is open, change the document through another
   surface and confirm applying the stale preview is refused.
8. Check the controls and previews in light/dark themes, portrait/landscape, narrow split panes
   and larger text settings; repeat live hold on a music sheet and on an infinite canvas.
