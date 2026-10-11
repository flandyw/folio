# LaTeX in text boxes and sticky notes

Text boxes and sticky notes render maths written between delimiters: `$x^2$` and `\(x^2\)` inline,
`$$\int_0^1 x\,dx$$` and `\[ … \]` on a line of their own. Nothing else is interpreted (no Markdown), and a note
with no maths is drawn exactly as before. `$5 and $6` is money: an inline `$` formula may not start or end on a
space or cross a blank line. `\$` types a literal dollar in a note that also contains maths.

The stored text is the source, untouched, so nothing in the storage format changed; search, backups and sync
keep seeing the LaTeX. While a box or note is being edited its field shows the source; the formula appears
when editing ends.

## How it draws

- `math/TextMath.kt` is the pure delimiter splitter (`node tools/text-math-smoke.cjs`).
- `InkRenderer.textLayout` turns each formula into a `FormulaSpan` (`FormulaSpan.kt`, a `ReplacementSpan`) in
  the box's `StaticLayout`, so wrapping, alignment, hit-testing and sticky padding keep one code path. Display
  formulas take their own line and are centred; a formula wider than the box shrinks to fit.
- Pictures come from `math/TextMathBitmaps.kt`, which renders through the same pooled offline KaTeX WebView as
  the rest of Folio (`KaTeXWebView.renderSnippet` also measures the text baseline so inline formulas sit on the
  line). The WebView paints at the screen's pixel ratio, so `renderSnippet` scales the request to land at exactly
  `PIXELS_PER_UNIT`; capturing at any other density leaves a small formula in a mostly blank picture. The canvas cannot wait for a WebView, so until a picture exists the layout shows the typed source; when
  it lands `InkView` repaints. Layouts are cached per `(box, which formulas are ready)`.
- Pictures are memory-only (24 MB LRU, cleared on trim-memory), so a cold start re-renders the formulas on
  screen. Exports and thumbnails use pictures that already exist and otherwise print the source, so open a
  page before exporting it. Rendering needs an attached editor window to host the WebView.

## Device check

- Type `$\frac{a}{b}+\sqrt{x}$` in a text box and a sticky note; finish editing: the formula replaces the source
  on the baseline, inline with surrounding words, wrapping with the box. Resize the box and the note.
- `$$\sum_{i=1}^n i$$` sits centred on its own line. A very wide formula shrinks to the box rather than clipping.
- Change colour, size and opacity; zoom in far (pictures render at 3 px per page unit, so very high zoom softens).
- Inline formulas match the surrounding words' size with no gap after them. Notes added from a ChatGPT review
  wait for their formulas before being sized, so their text is not clipped.
- Malformed `$\frac{$` shows KaTeX's error text, never a blank. Prose with `$5 and $6` is untouched.
- Undo/redo, move, lasso a formula box, duplicate a note, reopen the notebook, export a page to PDF after viewing it.
