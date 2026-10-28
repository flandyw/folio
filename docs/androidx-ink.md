# AndroidX Ink rendering

Folio uses AndroidX Ink 1.1.0-alpha10 brush, geometry, strokes and rendering modules.
The 1.1 custom brush API supplies a circular nib with size driven by Folio's existing
pressure × taper multipliers. Its passthrough input model preserves the already
smoothed centreline. Synthetic, ordered timestamps are used only to build meshes;
they are not saved and do not drive speed or pressure behavior.

## Brushes

Every brush is one `BrushFamily` in `AndroidxInk`, picked by the stroke's `StrokeStyle`:

- **Ballpoint** (`SOLID`): Folio's pressure × taper width as the size (input = scale / 2), exactly as before.
- **Fountain**: pressure size 0.55–1.48× plus start and end tapers over three brush widths.
- **Pencil**: pressure size 0.8–1.15× and opacity 0.3–1×, with a procedural graphite grain tiled every
  16 page units (`TextureBitmapStore`; the texture is generated, not an asset). Always discards self-overlap.
- **Marker**: a rounded-square tip of one even width.
- **Calligraphy / Chisel**: a flat tip (0.22 aspect, 45°) with pressure size; the highlighter has none.

Brushes other than the Ballpoint receive the stored pressure / 2 (a normal press is 0.5, which lands on width 1);
the highlighter always sends 0.5. Textures and per-vertex opacity need the mesh renderer (API 34+, hardware or
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

Patterned shapes, selection halos, laser effects, prediction tails, and the optional
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
