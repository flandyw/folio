# PDF import

Picking or sharing PDFs opens one review before notebooks are created. The destination is shared
by the batch and shows full folder paths. On a tablet the file list sits beside the selected
document; on a phone it scrolls horizontally. Review names and detected details, expand **Edit
details** for corrections, or choose **Plain PDF** to leave exam metadata off. Switching back
restores corrections. Unreadable files can be removed without losing the rest of the selection.
The import button waits for inspection to finish and for names, years and total marks to be valid.

The review uses Material 3 Expressive connected toggle buttons, shape-changing action buttons
and icon buttons, and contained loading indicators. Fields live in `FolioViewModel`, so finishing
another file's inspection or recreating the Activity does not reset edits. Cancel stops the
inspection queue. Files are read serially, including when more files join the review.

## Detection

`ExamDetection.kt` remains independent of Android. It combines the filename, PDF title/subject/
author and at most three pages of embedded text (24,000 characters each). Short codes such as
`2024mm1`, `2023sm2`, `gm` and `fm`, Roman-numbered exams, generic written examinations, source
names and whole-paper marks contribute evidence. A generic examination heading does not
override a numbered filename that agrees with the cover's subject. Strong cover headings take
priority over weaker filename matches; ambiguous fields stay blank. Repeated mentions do not
increase confidence. Copyrights, study-design ranges, section totals and question totals are
excluded. Tentative or conflicting fields are called out, and **Why these suggestions?** shows
their sources. The strengths describe evidence, not calibrated probabilities.

`NoteRepository.inspectPdf` makes a temporary seekable copy, checks it with Android's PDF
renderer, and reads its front matter with PDFBox. When either of the first two pages has fewer
than 160 letters of embedded text, bundled offline ML Kit OCR reads that page at a maximum
1800-pixel edge. Recognizer and bitmap cleanup wait for inference to finish even on cancellation.
OCR and embedded text from the same document do not count as independent corroboration.
Text-extraction/OCR failures still allow a valid PDF to import with filename evidence. Temporary
PDFs are deleted after inspection, and no OCR text is stored in notebooks.

Suggested names use year, source, subject and assessment type when both subject and type are
known. Numbered papers keep distinct names. Ordinary documents, partial matches and solution/
answer/report filenames retain their original names. Changing exam details updates a suggested
name; a manually typed name stays as typed until **Use suggested name** is chosen.
Music imports continue to use their separate review and store. Notebook storage is unchanged.

## Verification

Run `node tools/import-smoke.cjs` after the canonical build has populated the compiler cache.
The runner compiles the pure detector from source and exercises compact names, evidence
conflicts, publisher references, copyright order, marks totals, scanned evidence, names,
text-extraction failures and cancellation.

On a device:

1. Import a mix of an exam, a scanned paper, an ordinary PDF and a damaged/protected file. Check
   tentative details, OCR suggestions, page counts, plain-PDF mode and removal of unreadable files.
2. Edit a ready file while another is still reading. Rotate, switch files, add another selection,
   and confirm all edits remain. Toggle Plain PDF and back; confirm corrected fields return.
3. Correct subject/year/type, type a custom name, then correct more fields. Verify custom names
   stay unchanged, while suggested names follow the fields. Check an invalid year/mark total.
4. Check narrow and wide layouts, keyboard visibility, large text, TalkBack labels, nested folder
   paths, cancellation and immediately selecting the same file again.
5. Import the batch, reopen its notebooks, and confirm their names, fields and PDF pages match
   the review. Check that originals and the Music collection are untouched.
