# PDF rendering

`NotebookPdfs` (owned by `NoteRepository`, reached as `repository.pdfs`) keeps PDFBox metadata scans
(text, links, labels, printed mark allocations and outlines) on `pdfMetadataLock`. Native page rendering has its own queue in `PdfRenderCache`, so
opening a long PDF's navigation data does not block the editor's backgrounds.

The render cache retains up to three parsed documents in least-recently-used order. Scrolling an
editor and a split/reference document therefore does not close and reparse the other document on
each uncached page. Native renders remain serialized, and closing/evicting a document waits for its
current render to finish. Exports and mark OCR can still open their own scoped `PdfBackgrounds`.

Shared page backgrounds have the existing byte budget: one eighth of the process heap, clamped
to 8–64 MiB. Each source page keeps one resolution, upgraded when a sharper render is requested.
Editor backgrounds can satisfy smaller page-browser/library previews and subsequent previews of
edited ink without rendering the PDF again. A small preview never satisfies a larger request.
Cache hits bypass the native render queue, concurrent misses recheck the cache after acquiring it,
and cancelled queued requests do not open or render a document. An image exceeding the budget is
returned without flushing the other cached pages.

`cachedPdfBackground` returns shared immutable bitmaps: eviction and thumbnail completion must
never recycle them because a native view or display list may still draw them. `pdfBackground`
and scoped `PdfBackgrounds.render` return fresh, caller-owned images for paths that recycle their
working bitmaps. `closePdf` drops both the named parsed document and its cached backgrounds.
The original render dimensions, white background and PDF display mode are preserved.

## Verification

```sh
./gradlew :app:testDebugUnitTest --tests com.folio.notes.PdfRenderCacheTest
./build.sh -p
```

The JVM checks exercise the real cache with fake native resources. Sixteen alternating page
requests across two documents open two documents; an editor render followed by thirty smaller
preview requests draws once. They also cover native serialization, cache hits during another
render, request coalescing, cancellation, byte-budget/LRU eviction, resource closing, invalidation,
owned/shared bitmap isolation, missing sources and failed-render retries.

These counts establish work avoided, not device timing. On the same tablet and PDFs, compare:

1. Cold open a long paper with links/printed marks enabled. Confirm the first page appears while
   metadata is being inspected and that links and marks arrive correctly.
2. Scroll two different PDFs in split view, change pages in each, then swap the editor pane.
3. Open/close the page browser, write on a PDF page, and reopen its preview. Confirm the editor's
   background remains visible, previews update their ink and small previews stay sharp at their size.
4. Jump rapidly through a long scanned paper; close, trash and restore it. Confirm cancelled work
   does not cause a long queue, restored backgrounds are correct and memory remains bounded.
5. Export PDF/PNG, run scanned mark detection, and use the music reader. Check output and ordinary
   editor backgrounds after these paths have disposed of their private working bitmaps.

Record cold-open/page-turn timings and peak memory against the previous build. Native timing and
visual results require a device; JVM checks and compilation do not establish them.
