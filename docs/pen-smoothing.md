# Pen smoothing

Pen tool options and **Settings → Writing → Input** share a **Pen smoothing** slider (default 35%; 0% turns input smoothing off). Changes take effect on the next pen stroke, including pen ink on sticky notes and sheet music. Highlighter, eraser, lasso, shapes, and ruler-constrained strokes keep their own input paths.

## How it works

`PenSmoothing.kt` filters historical samples in time order with an adaptive position cutoff:

- Faster motion reduces filtering delay; tip lag is capped at 4 screen pixels at 100% (1.4 pixels at the default 35%).
- Pressure values are preserved untouched.
- Pen-down starts a fresh filter; pen-up preserves the exact endpoint (even if the tracked stylus lifts before another pointer). Cancellation discards the filter with the draft.
- Filtered positions become ordinary stroke samples, so live rendering, saved ink, thumbnails, and exports match with no storage format change. Curve interpolation still runs when input smoothing is off.

**Corner preservation:** Two legs of at least 4 screen pixels turning by more than ~55° raise the cutoff and tighten the lag budget for several samples so letters keep crisp corners. Tremor on shorter legs does not trigger a corner, nor do the first ten samples of a stroke where the hand settles at pen-down. The apex sample itself is emitted before the turn is known and keeps the normal lag bound; leaving legs recover within a sample or two. See [Pen feel](pen-feel.md).

## Verification

- **Smoke test:** Run `node tools/pen-smoothing-smoke.cjs` (after a build populates the Kotlin compiler cache). It covers jitter reduction, exact endpoints and pressure, zoom invariance, bounded lag, long strokes, disabled smoothing, and timestamp resets.
- **On device:**
  - Compare Off, 35%, and 100% on slow diagonals, small letters, tight loops, and fast zigzags across zoom levels.
  - Confirm dots and short crossbars retain their endpoints, ruler lines stay locked to the edge, and shape hold and scribble-to-erase still work.
  - Write on a sticky note and a music score, undo/redo, reopen, and export to confirm identical appearance.
  - Cancel a stroke with navigation and start a new stroke elsewhere to verify no ink bridges the two gestures.
