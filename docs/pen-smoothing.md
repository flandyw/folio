# Pen smoothing

Pen settings and Settings → Writing → Input share a Pen smoothing slider. The default is 35%;
zero turns input smoothing off. Changes apply from the next pen stroke, including pen ink on
sticky notes and music sheets. Highlighter, eraser, lasso, shapes and ruler-constrained ink keep
their existing input paths.

`PenSmoothing.kt` filters each historical sample in time order with an adaptive position cutoff.
Faster motion reduces the filtering delay, and the tip can lag by at most four screen pixels at
100% (1.4 pixels at the default). Pressure is retained exactly. Pen-down starts a new filter;
lift retains the exact endpoint, including when the tracked stylus lifts before another pointer.
Cancellation discards the filter with the draft. Filtered positions are ordinary stroke samples,
so live rendering, saved ink, thumbnails and exports share the result without a format change.
Existing curve interpolation still applies when input smoothing is off.

Sharp turns are protected: two legs of at least three screen pixels turning by more than about 55°, or a
pen braking hard from writing speed, raise the cutoff and shrink the lag budget for a few samples so letters
keep their corners (tremor on shorter legs is not a corner). The apex sample itself is emitted before the
turn can be seen, so it keeps the normal lag bound; the legs leaving it recover in a sample or two. See `pen-feel.md`.

Run `node tools/pen-smoothing-smoke.cjs` after the Kotlin compiler cache is populated by a build.
It checks jitter reduction, exact endpoints/pressure, zoom invariance, bounded lag, long strokes,
disabled smoothing and timestamp resets.

On a device, compare Off, 35% and 100% with slow diagonals, small letters, tight loops and fast
zigzags at several zoom levels. Check a dot and a short crossbar retain their endpoints, a ruler
line stays on its edge, and shape hold and scribble-to-erase still work. Write on a sticky note
and a music sheet, undo/redo, reopen and export to confirm the same appearance. Cancel a stroke
with navigation and start another elsewhere to check that no ink bridges the two gestures.
