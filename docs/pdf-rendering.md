# PDF rendering

`NotebookPdfs` (owned by `NoteRepository`, accessed as `repository.pdfs`) keeps PDFBox metadata scans (text, links, labels, printed mark allocations, and outlines) on `pdfMetadataLock`. Native page rendering runs on its own queue in `PdfRenderCache`, so scanning a long PDF's navigation data never blocks editor backgrounds.

## Caching and bitmap ownership

- **Parsed document LRU:** Retains up to three parsed PDFs so scrolling an editor alongside a split or reference PDF does not thrash open/close cycles. Native renders stay serialised, and closing or evicting a document waits for any active render to finish. Exports and mark OCR still open their own scoped `PdfBackgrounds`.
- **Shared background budget:** Sized to 1/64 of device RAM (up to 192 MiB) for native bitmap memory, or 1/8 of the process heap (8–64 MiB) on low-RAM devices. `onTrimMemory` drops cached backgrounds without recycling them while keeping parsed documents open.
- **Resolution reuse:** Each source page stores one resolution, upgraded when a sharper render is needed. Editor backgrounds serve smaller page-browser and library thumbnails without re-rendering, while small previews never satisfy larger requests. Cache hits bypass the render queue, concurrent misses re-check after acquiring the queue, cancelled requests do not render, and oversized bitmaps return without flushing the cache.
- **Immutability:** `cachedPdfBackground` returns shared immutable bitmaps that must never be recycled on eviction or thumbnail completion because views or display lists may still draw them. `pdfBackground` and scoped `PdfBackgrounds.render` return fresh caller-owned bitmaps for paths that recycle working buffers. `closePdf` releases both the parsed document and its cached backgrounds.

## Preserved PDF export fallback

Preserved PDF export removes any partial page import before falling back to raster rendering. The fallback preserves the original PDF background and aspect ratio so a failed annotation overlay never duplicates content or emits a blank page. Cancellation stops export rather than falling back, and export-owned bitmaps recycle even on failure.

## Verification

```sh
./gradlew :app:testDebugUnitTest --tests com.folio.notes.PdfRenderCacheTest
./build.sh -p
```

- `PdfRenderCacheTest` tests fake native resources across multi-document alternation, editor-to-thumbnail reuse, serialisation, coalescing, cancellation, LRU/byte-budget eviction, invalidation, owned vs shared isolation, missing files, and failed-render retries.
- `PreservedPdfFallbackTest` exercises real PDFBox page imports, rollback, cancellation, fallback failure, and round-trips.
- **On device** (record cold-open/page-turn times and peak memory against the previous build):
  1. Cold-open a long paper with links and printed marks enabled; confirm page 1 renders while metadata scans and links/marks arrive cleanly.
  2. Scroll two different PDFs in split view, turn pages in each, and swap editor focus.
  3. Open/close the page browser, annotate a PDF page, and reopen its preview; confirm sharp previews and intact editor backgrounds.
  4. Jump rapidly through a long scanned PDF, close, trash, and restore; confirm cancelled renders clear promptly and memory stays bounded.
  5. Run PDF/PNG export, scanned mark detection, and the music reader; confirm ordinary editor backgrounds stay intact after private buffers dispose.
