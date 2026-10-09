# Tidy up shapes

Enable **Tidy up shapes** in Writing settings or the pen's tool options. Draw with the
pen tool, then pause at the end with the tip still down. After 650 ms by default the
drawing previews as a clean shape. Lift to keep it. Moving more than 8 dp resumes the
original freehand stroke; another pause can tidy it again. The hold delay is adjustable
from 300–1500 ms in either place. Lifting without a hold leaves handwriting unchanged.

Supported drawings are lines, circles and ovals, triangles, rectangles, diamonds and
other convex quadrilaterals, pentagons, hexagons, five-point star outlines, and one-stroke
arrows. To draw an arrow, draw the shaft to the tip, draw one wing, return to the tip,
then draw the other wing. A V-shaped head returning to the tip also works. Open curves,
tiny marks, scribbles and loops drawn twice are left alone. Highlighter strokes do not tidy.

Recognition uses samples spaced by distance rather than event count, so drawing speed
and stationary pen samples do not change the fit. Rectangles and ovals retain their
orientation. Axis-aligned boxes and ovals use existing shape tools; rotated rectangles,
polygons, stars and arrows use ordinary line strokes, and a rotated oval uses a clean,
uniform-pressure pen outline. Colour, width, opacity and layer carry over. There is no
storage format change. A held shape takes priority over scribble-to-erase, and does not
trigger writing-follow movement. One undo restores the freehand drawing; another removes it.

Run `node tools/shapes-smoke.cjs` after the canonical build has populated the Kotlin
compiler cache. It compiles the pure recognition and hold rules directly from source.

## Stylus device check

- With tidy-up enabled, draw a line and lift immediately: it stays freehand. Draw another
  and hold: the preview changes while the stylus is still touching the screen, including
  on devices that stop sending MOVE events when stationary.
- Test the minimum, default and maximum delay. Small tip jitter must not postpone the
  conversion; deliberate movement after conversion must restore the freehand preview.
- Draw each supported shape, including a tilted rectangle, tilted oval, diamond,
  pentagon, hexagon, star and arrow. Test drawing in either direction and starting halfway
  along a polygon side. Try the same drawings on a zoomed canvas and PDF page.
- Lift after conversion, then undo twice and redo twice. The first undo restores the
  actual drawn stroke and its pressure; the next removes it. Redo restores both steps.
- On a non-base layer, verify the shape stays on that layer and respects hidden/locked
  layers. Test with snap-to-grid enabled and disabled; only a standalone tidied line snaps.
- Cancel a stroke, switch tools, disable tidy-up, turn a score page, or navigate away
  during the hold. A delayed preview or commit must never appear afterwards. Palm contacts
  and Android-canceled contacts must not commit ink or interrupt an unrelated pen.
- With scribble-to-erase enabled, a held star or arrow must not erase the ink beneath it.
  Ordinary scribble erasing must still work. Highlighter strokes and ordinary handwriting
  must retain their existing behaviour.
