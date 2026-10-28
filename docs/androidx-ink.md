# AndroidX Ink rendering

Folio uses AndroidX Ink 1.1.0-alpha10 brush, geometry, strokes and rendering modules.
The 1.1 custom brush API supplies a circular nib with size driven by Folio's existing
pressure × taper multipliers. Its passthrough input model preserves the already
smoothed centreline. Synthetic, ordered timestamps are used only to build meshes;
they are not saved and do not drive speed or pressure behavior.

## Brushes

Every brush is one `BrushFamily` in `AndroidxInk`, picked by the stroke's `StrokeStyle`:

Every brush's pressure input is the ballpoint's pressure width / 2 (`penPressureScale`, 0.72–1.16, so input
0.36–0.58), and every brush except the marker takes it as its size. A normal press is therefore the same width
on every brush; brushes add their character on top. Stored pressure is the raw stylus reading (often 0.3–0.5),
so it must never be fed to a brush directly: that drew the fountain pen at about two thirds of the ballpoint.

- **Ballpoint** (`SOLID`): the size includes the global soft ends (`InkTaper`), exactly as before.
Brush character is mapped over input 0.43–0.50, which is stored pressure 0.25 (the floor) to 1 (firm):
`penPressureScale` squeezes the usual band that hard, so a wider source range leaves ordinary writing
using a sliver of the effect.

- **Fountain**: an extra 0.9–1.3× pressure swing, neutral at an ordinary press, plus speed thinning baked
  into stored pressure at input. No distance taper of any kind: a start taper shrinks dots and full stops
  (no distance travelled) and tapers tidied shapes and both halves of an erased stroke; an end taper thins
  and re-swells the ink under the moving tip, and the stroke visibly shrinks at lift.
- **Pencil**: pressure opacity 0.4–1×, with a procedural graphite grain tiled every 16 page units
  (`TextureBitmapStore`; generated, not an asset). The grain is smooth value noise, not per-pixel static,
  because the texture is drawn shrunk without mipmaps and static sparkles while panning. Always discards
  self-overlap.
- **Marker**: a rounded-square tip of one even width.
- **Calligraphy / Chisel**: a flat tip (0.22 aspect, 45°); the highlighter sends a constant 0.5. Textures and per-vertex opacity need the mesh renderer (API 34+, hardware or
software canvas); older Android falls back to flat paths, so the pencil loses its grain and pressure shading there.

## Routing

`InkRenderer.drawRendered` routes pen, highlighter and solid shapes through
`AndroidxInk`. This includes editor pages, sticky ink, selections, thumbnails,
committed raster caches, and bitmap/PDF exports. The ~90 ms pinch/pan navigation
preview (`drawPreview`) keeps one uniform Canvas path per stroke: it rasterizes on a
software canvas, where a mesh would be redrawn as a path anyway. Cached geometries own
reusable native meshes. Each render thread owns its Canvas renderer. The complete
Canvas transform is supplied for antialiasing, including zoom and export scale.
Translucent strokes explicitly discard self-overlap so hardware and software output
keep the same opacity at crossings; opaque strokes may use the faster mesh renderer.
Cached thumbnail names include a renderer generation so old previews are regenerated.

While writing, a brush stroke is only ever drawn by its own mesh. The front-buffer low-latency overlay and
the Canvas prediction tail draw round ballpoint segments, so they serve the Ballpoint only; for other
brushes the predicted pen path (`PenTipPrediction.ahead`: the motion predictor's tail, else the
two-sample guess) is appended to the mesh's provisional input
instead, so the prediction is drawn in the brush's own shape and replaced on the next frame. Navigation
previews (`drawPreview`) also draw the cached meshes, so ink never changes look while panning or zooming.

Live freehand builders expose their settled prefix. Only new settled points are
appended to `InProgressStroke`; its predicted-input channel holds the replaceable
spline tail (not additional predicted document ink). A change in the handwriting
smoothing regime replaces that mesh. Completed geometry becomes an immutable Ink
stroke, and its in-progress stroke and input batches are released. At pen-up the
cached copy takes over the live mesh and only appends the final tail.
Meshes are tessellated to within 0.025 page units, so curves stay smooth at high zoom.

If AndroidX Ink throws (native load or input validation), `AndroidxInk.failed` is set,
the error is logged once, and every later draw uses the previous Canvas renderer
(`InkOutline`) for the rest of the process. All persistence, hit tests, erasing, palm rejection, recognition and document
transactions continue to use Folio's original samples and format.

Patterned shapes, selection halos, laser effects, and the Ballpoint's prediction tail and
front-buffer overlay retain their specialized Canvas drawing. Text, pictures, paper
and ruler guides are not Ink strokes. AndroidX authoring does not own MotionEvents:
Folio must still route cross-page ink, sticky targets and cancelled palms itself.

## Checks

`AndroidxInkTest` checks width preservation and stable live prefixes, alongside
`HandwritingInkTest`. The standard signed experimental build checks native packaging.
No notebook format migration or journal change is involved.

On a tablet, write with every brush (pen and highlighter) and compare the popover samples with the page;
check the pencil grain stays put while panning and zooming, fountain tapers while writing and after lift,
and the calligraphy nib's thick/thin direction. Open a notebook with brush strokes in the previous release
to confirm they read as plain pen ink. Compare dots, small handwriting, long strokes, hard/light pressure,
taper on/off, translucent pen self-crossings and highlighter overlaps. Exercise
shape holds, solid and patterned shapes, sticky ink, split panes, cross-page writing,
pinch/rotate, scrolling dense pages, lasso movement, erasing and undo/redo. Compare
thumbnails and PDF/PNG exports with the editor at different zoom levels. Repeat with
the optional low-latency overlay enabled; it still draws the leading edge separately.
Check both a software bitmap export and a hardware Canvas (API 26–33 path fallback
and API 34+ mesh rendering). This dependency is an alpha and visual/latency behavior
must be checked on real digitizers before promoting the experimental build.
