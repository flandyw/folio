# AndroidX Ink rendering

Folio uses AndroidX Ink `1.1.0-alpha10` brush, geometry, stroke, and rendering modules. The 1.1 custom brush API provides a circular nib sized by Folio's existing `pressure × taper` multipliers, while its passthrough input model preserves Folio's smoothed centreline. Synthetic, ordered timestamps are used strictly to build meshes; they are never saved and never drive speed or pressure behaviour.

## Brushes

Each brush is a `BrushFamily` in `AndroidxInk`, selected by the stroke's `StrokeStyle`. Every brush receives `penPressureScale` (0.72–1.16, mapped to 0.36–0.58) as its pressure input so a normal press draws the same width across brushes; raw stylus pressure (typically 0.3–0.5) is never passed directly. Brush character maps over input 0.43–0.50 (stored pressure 0.25–1.0).

- **Ballpoint (`SOLID`):** Uses global soft ends (`InkTaper`), matching the legacy look.
- **Fountain:** Adds a 0.9–1.3× pressure swing (neutral at normal press) plus speed thinning baked into stored pressure at input. No distance taper, as start taper shrinks dots/full stops and end taper visibly shrinks strokes on lift.
- **Pencil:** Pressure-driven opacity (0.4–1×) with procedural smooth value-noise graphite grain tiled every 16 page units (`TextureBitmapStore`, generated in code so panning never sparkles). Always discards self-overlap.
- **Marker:** Rounded-square tip with uniform width.
- **Calligraphy / Chisel:** Flat tip (0.22 aspect, 45°); highlighter sends a constant 0.5. Textures and per-vertex opacity require the mesh renderer (API 34+); older Android falls back to flat paths (pencil drops grain and pressure shading).

## Routing

`InkRenderer.drawRendered` routes pen, highlighter, and solid shapes through `AndroidxInk` across editor pages, sticky ink, selections, thumbnails, committed raster caches, and bitmap/PDF exports. Each render thread owns its Canvas renderer, cached geometries own reusable native meshes (tessellated to 0.025 page units), and full Canvas transforms drive antialiasing. Translucent strokes discard self-overlap for consistent opacity; thumbnail cache keys include a renderer generation.

While writing, non-ballpoint brushes render strictly via their own live mesh, appending predicted tip points (`PenTipPrediction.ahead`) to `InProgressStroke`'s provisional input channel. Only opaque ballpoint stylus strokes use the front-buffer low-latency overlay (`AppPrefs.DEFAULT_LOW_LATENCY_INK`, on by default, not yet device-verified; see [Pen feel](pen-feel.md) and [Low-latency ink device checks](low-latency-ink-device-checks.md)). Navigation previews (`drawPreview`) draw cached meshes so ink keeps its brush look while panning and zooming.

If AndroidX Ink throws (native load or validation error), `AndroidxInk.failed` logs once and permanently falls back to `InkOutline` for the rest of the process. Persistence, hit-testing, erasing, palm rejection, recognition, and document transactions remain on Folio's native samples and format. Patterned shapes, selection halos, laser trails, and the ballpoint prediction tail/overlay keep their specialised Canvas paths.

## Verification

- **Automated:** `AndroidxInkTest`, `HandwritingInkTest`, and `./build.sh -p` (native packaging). No storage migration is required.
- **On device:**
  - Write with every pen and highlighter brush, comparing popover previews to the page; verify pencil grain stays anchored during pan/zoom, fountain weight responds cleanly, and calligraphy nib angle stays consistent.
  - Open a brush notebook in a previous release to confirm backward compatibility (plain pen fallback).
  - Test dots, small handwriting, long strokes, light/firm pressure, taper on/off, translucent self-crossings, highlighter overlaps, shape holds, patterned shapes, sticky ink, split view, cross-page ink, pinch/rotate, dense scrolling, lasso, erasing, undo/redo, thumbnails, and PDF/PNG exports.
  - Test on both API 26–33 (path fallback) and API 34+ (mesh renderer), and run [Low-latency ink device checks](low-latency-ink-device-checks.md) before promoting this alpha dependency.
