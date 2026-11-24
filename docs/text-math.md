# LaTeX in text boxes and sticky notes

Text boxes and sticky notes render maths inside standard delimiters: `$x^2$` and `\(x^2\)` inline, and `$$\int_0^1 x\,dx$$` or `\[ … \]` on their own centred line. Nothing else is parsed (no Markdown), and notes without maths draw identically to before.

- `$5 and $6` stays plain text: inline `$` formulas cannot start or end with a space or span a blank line.
- Use `\$` for a literal dollar sign inside a note that also contains maths.
- Stored text remains the untouched LaTeX source, so search, backups, and sync require no format change. Editing shows the source; finishing editing renders the formulas.

## Rendering pipeline

- `math/TextMath.kt` splits delimiters cleanly (`node tools/text-math-smoke.cjs`).
- `InkRenderer.textLayout` inserts a `FormulaSpan` (`FormulaSpan.kt`, a `ReplacementSpan`) into the box's `StaticLayout` so wrapping, alignment, hit-testing, and sticky-note padding share one layout path. Display formulas occupy their own centred line and scale down if wider than the box.
- `math/TextMathBitmaps.kt` renders bitmaps via Folio's pooled offline KaTeX WebView (`KaTeXWebView.renderSnippet`, which also measures the text baseline for inline alignment). Requests scale for the WebView's device pixel ratio so output lands at exact `PIXELS_PER_UNIT`. Because canvas draws cannot block on WebView, layouts show the source until the bitmap arrives and then trigger an `InkView` repaint. Layouts cache by `(box, ready formulas)`.
- Bitmaps live in a 24 MB memory-only LRU cleared on trim-memory, so cold starts re-render visible formulas. Exports and thumbnails reuse cached bitmaps and fall back to source text otherwise, so open a page before exporting it. Rendering requires an attached editor window to host the WebView.

## Device checks

- Type `$\frac{a}{b}+\sqrt{x}$` in both a text box and a sticky note, finish editing, and verify baseline alignment, inline wrapping, and resizing.
- Check `$$\sum_{i=1}^n i$$` centres on its own line and very wide formulas shrink to fit without clipping.
- Change colour, size, and opacity, and zoom in (bitmaps render at 3 px per page unit, so extreme zoom softens slightly).
- Verify inline formulas match surrounding text size with no trailing gap, and that ChatGPT review sticky notes wait for formulas before sizing so text is never clipped.
- Confirm malformed `$\frac{$` displays KaTeX's error text rather than a blank, and `$5 and $6` stays plain text.
- Undo/redo, move, lasso a formula box, duplicate a note, reopen the notebook, and export to PDF after viewing.
