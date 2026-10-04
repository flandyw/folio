# Marking and feedback

Page menu → **Marking & feedback** (`MarkingPanel.kt`, logic in the pure `Marking.kt`).

Everything is ordinary page content — typed text boxes and ink — so it moves, restyles, erases, exports and
backs up like anything else. **No storage format change.** The one convention is the *feedback sheet*: a page
titled `Feedback`, found by title, whose numbered lines decide the next flag number.

- **Note at a point** — tap the spot a comment is about; a dot and leader line tie it to a box in the nearest
  clear space (`Marking.feedbackNote`). *Handwritten* is a white panel of thick `LINE` rows under a border and
  switches to the pen; *typed* opens the text editor on a new box. The panel is white so it covers any printed
  text the free-space guess missed. Cancelling a typed note leaves its dot and leader (undo removes them).
- **Printed marks** — on an imported PDF, `NoteRepository.pdfMarkZones` reads `[4 marks]`, `(2 marks)`, `1 mark`
  with positions from PDFBox's text layer, then offline OCR on pages without detected allocations. A stylus
  hovering over one, or a finger tapping it (`InkView.beginZone`; stylus *touches* still write), floats a chip
  (`MarkChip`): **tick** stamps `+N` in full, **cross** opens a − / + stepper for part marks. Nothing is stored
  beyond the `+N` text box, which the tally already counts; re-opening a stamped label edits that stamp. The
  chip never takes focus, vanishes after 3 s (10 s while adjusting) or when the pen touches the page, and the
  whole thing is switchable in the panel (`marking.assist`). The summed allocations feed "of N" in the tally
  when exam details have no total and the scan completes without errors. Unverified against real exam PDFs: positions assume PDFBox text coordinates
  are relative to the crop box.
- **Quick comments** — a saved bank (`marking.comments` pref, 24 max, editable). Tap one, then tap the page;
  the tool stays armed so several can be placed. With *Place in free space* on, it drops into the clearest
  spot instead (`Marking.freeSlot`: occupancy grid + summed-area table over the page's own ink/text/pictures;
  a PDF's printed content is *not* known, so it is a first guess to drag).
- **Marks** — `✓`, `½`, `+1`…`+4` stamped as bold text. `Marking.markValue` reads `+N`, ticks and `½` back, so
  the panel totals them per page and can file the sum as an `ExamAttempt`. Any text box that is exactly one of
  those counts, whatever its colour; half marks round when recorded. A page that cannot be read is reported,
  never silently counted as zero.
- **Flags** — a numbered ring on the page plus `N · p.X` on the feedback sheet (created at the end of the
  notebook on first use, growing as needed). Long feedback gets a whole ruled page instead of a margin.
- **Make room** — tap a line and everything that starts below it moves down; a straddling item stays put.
  A finite page grows by the same amount (index saved first, so a crash leaves spare paper, not clipped
  ink). Imported PDF pages cannot grow (their background is stretched to the page), nor can a page pass
  `Marking.MAX_PAGE_HEIGHT`; both say so. Undo restores the ink but leaves the page tall.

Armed taps reuse the text tool's tap path (`EditorScreen.placeTextBox`); changing tool disarms.

Manual check before release: mark a PDF with comments + flags, confirm the Feedback page appears and exports;
make room on a notebook page, force-stop, reopen and confirm ink and page height agree.

## Scanned papers

The fallback uses bundled ML Kit Latin text recognition (`com.google.mlkit:text-recognition:16.0.1`).
The model ships in the APK, so first use works offline without a model download. Page images are processed
on device. This adds native OCR/model assets to the APK; no camera permission or cloud service is needed.
Reference: https://developers.google.com/ml-kit/vision/text-recognition/v2/android

- PDF text extraction runs first; only pages with no matching allocations go through OCR. This supports
  mixed scanned/digital papers without duplicating text-layer labels. A page with both searchable labels
  and additional labels embedded in an image is not exhaustively scanned.
- A separate framework PDF renderer scans the original background (never the user's annotations), one
  page at a time, at up to 2200 × 2800 pixels. OCR does not hold the editor's PDF render lock. Element
  bounding boxes are unioned only over the matched label and scaled from rendered pixels into page units.
  PDF rotation metadata is handled by the renderer; sideways text baked into a scan may still be missed.
- Only literal numeric allocations from 1 to 40 are accepted; no guessing `I`/`l` as `1`, or automatic
  awarding. Faint scans and handwriting may need manual stamps. Recognition accuracy is not guaranteed.
- Progress and errors appear in the existing panel, without a modal or notification. Labels become usable
  as pages finish. Leaving the notebook or disabling assist cancels subsequent work; an in-flight inference
  finishes before its bitmap is recycled. Failed pages can be retried by toggling assist off and on.
- Successful per-page results, including empty results, are cached under `cacheDir/mark-ocr-v1`, bounded
  to 512 entries. Keys include source path, length, modification time, page index and dimensions. Only
  allocation boxes/values are retained; clearing app cache safely rebuilds them. Cache format/model or
  matching changes should bump this directory version. No notebook, journal or backup format changes.

Device check before release: import a scanned paper with `[4 marks]`, `(2 marks)` and `1 mark`, plus a
mixed digital/scanned PDF and a PDF with rotation metadata. In airplane mode, confirm labels line up at
multiple zooms, tick awards full marks, cross adjusts and revisiting edits the existing award. Check a
blank/faint page, reopen to exercise the cache, and turn assist off/navigate away during a long scan.
Build/lint and matcher checks do not substitute for verifying OCR accuracy on actual exam scans.
