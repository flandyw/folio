# Mistake responses

## Response modes and layout

Mistake review places **Write**, **Type**, and **Paper** in the top-right app bar just before the overflow menu, giving the response area full vertical height. On narrow screens or with large system fonts, the switcher collapses to icons with accessibility labels, and **Previous attempts** and **Skip** (once revealed) move into the menu. **Write** is the default; switching remembers your choice on this device, and an unfinished typed answer always reopens in **Type**. Switching modes preserves both handwritten and typed working.

The review screen uses one uniform surface across the top bar, question pane, divider, response toolbar, viewport, and footer. Corrections and explanations sit as plain text on the same surface. The embedded editor inherits a local colour scheme with its surface tiers matched to the review background so the toolbar never creates a contrasting band, while notebook paper keeps its own colour.

## Typed responses

**Type** presents a borderless multiline paper sheet (outlined in the accent colour while focused) with standard selection, paste, and editing. An empty sheet hints at `$…$` maths.

- Edits save through `FolioViewModel.commitText` and the serialised document writer; the footer shows *Saves as you type*, *Saving…*, *Saved · N words*, or *Not saved* with **Retry save**.
- **Compare** dismisses the keyboard, reveals the correction, and switches to a typeset **Preview** beside it; **Edit** returns to the text field. Rating waits for pending writes, and a failed save blocks rating until resolved.
- While focused, `Space`, `S`, and number keys type normally instead of triggering review shortcuts; `Ctrl/⌘+Enter` compares and `Esc` finishes typing.
- A **Typing bar** (`TypingBar` in `MistakeTypedResponse.kt`) sits above the keyboard: **Maths** wraps the caret or selection in `$…$` (or becomes *Leave maths* inside delimiters), the list key toggles `- `, and symbol keys insert Unicode in prose or LaTeX inside maths (e.g. a power key after `x` inserts `$x^{}$`). `Enter` continues `- ` and `1.` lists and ends a list on an empty item. The Write/Type switch shows a dot on the inactive mode when it holds working.

On narrow windows, the question folds down while the software keyboard is open and confidence chips make room for **Done typing** and **Compare answer**. Wide windows keep the question beside the response; very short windows temporarily hide the response toolbar and question pane to preserve editing space. Opening a card in Type does not open the keyboard automatically; tapping Type or the field does.

**Preview** renders formatted text and offline KaTeX maths through the same rich-text renderer as the question. Sharing a typed-only answer sends plain text; mixed working uses the image/clipboard path. Previous attempts show typed answers alongside handwriting, and device storage lists both under **Responses**.

## Storage and compatibility

No notebook or sync format changes are required. Each typed answer is an ordinary `TextBox` on the card's existing practice page with ID `mistake-response-<reviewId>`, placed to the right of existing working on an editable layer. Drafts and completed working stay local and travel in notebook backups; only ratings sync with Focal.

The review screen observes its practice page via `DocumentReader.observePage` and loads it independently of the Write editor. Shell page metadata always has `loaded = false` with no content and must never drive Type's loading state, restoration, working indicators, or sharing.

## Exam paper mode

**Paper** (`MistakeExamPaper.kt`) renders the question on a white exam sheet: subject/paper header, bold *Question (N marks)* title, serif question text, embedded images, and ruled answer lines (~3 per mark, 4–30; *−* / *+* adjusts lines per question on this device). It replaces the split question/response view. **Settings → Mistakes → Answering** sets the default mode for new questions, and switching in-review updates the same preference.

- **Canvas alignment:** The sheet lays out at fixed width (`ExamSheet.WIDTH`) beneath a transparent `InkView` (`InkView.paperless`) bound to the same practice page, undo stack, and journal as Write. Because the sheet follows the infinite-canvas camera, fingers scroll and pinch paper and ink together. The first answer line sits at `y = 0` (question highlights sit at negative `y`).
- **Image resize and reflow:** Image widths and aspect ratios persist locally (`mistake.exam.image*`) so space is reserved before images load. Finishing a resize runs `ExamSheet.reflow` as one undoable stroke edit so ink above shifts with text and ink over the image scales with it (undo reverts the ink, not the image size). Ink written in Write appears at the same position in Paper and vice versa.
- **Toolbar:** `ExamTopBar` shares `EditorTopBar`'s layout and hosts `FloatingInkToolbar` with pen, shapes, highlighter, and eraser (`ExamSheet.SLOTS`) plus shared quick colours, widths, brushes, and presets. The left side carries the line stepper (*− N lines +*), resize pictures, and fit; the right carries a pace ring (1.5 min/mark, stops at Compare) and share (hold for question text). Compare appends a *Marking guide* below the lines and scrolls to it.
- **Density and image loading:** The sheet lays out at its own density, so `KaTeXWebView.render` and `renderDocument` scale requests to the WebView's real pixel ratio (disk cache `katex-…-document-v5`). Question images share one loader released on disposal, and resize bounds capture during placement in sheet coordinates so panning and zooming avoid global-position callbacks.
- **Physics and input:** Finger flings decay exponentially and clamp via `ExamSheet.clamp`. `rememberEditorPreferences` wires scribble-to-erase, hold-to-erase, eraser modes, shape assist, snapping, two-/three-finger undo/redo, palm grace, fast pan, max zoom, stylus shortcuts (pen/eraser/highlighter/undo/colour), and pen haptics.
- **Dark theme (Android 12+):** Only the printed sheet passes through `ExamSheet.nightMatrix` (inverted lightness, preserved hue, `#242424` paper on a black desk). Ink, highlights (including the low-latency overlay), and the paper shadow bypass the filter, and sharing always exports white paper with original ink colours.

## Recording practice in Study

**Record in Study timer** is on by default (toggle in **Mistakes → Today** or beside Write/Type). Starting any due card follows the same subject filter, order, and session length as **Start questions**, queuing remaining cards automatically.

- One practice queue owns one Study session, with each question visit recorded as a timer interval. Skipping, rating, backgrounding, or toggling recording off closes the active interval; resuming opens a new interval without counting the gap. Switching Write/Type keeps the same interval. Starting recording pauses any other running Study timer.
- Sessions record a *Mistake practice* title, exam/paper/question details, and subject ID (mixed queues use the latest subject while per-interval metadata tracks each question's subject). Focal's short-session merge and overlap pruning rules still apply. Five-second checkpoints close interrupted visits cleanly after a crash or restart.

## Verification

- **Automated:** `MistakeTypedResponseTest` checks reader loading, typing bar keys, list continuation, mixed ink/text snapshot round-trips and undo, independent review IDs, canvas edits, layer choice, and retention of typed-only attempts.
- **On device (Write/Type):** Test narrow portrait, landscape with software keyboard, wide tablet, and split screen. Type paragraphs, paste, compose accented text, scroll to the bottom, and confirm `Space`, `S`, and `1–4` stay in the field. Switch Write → Type → Write, skip/resume, rotate, restart, rate immediately after editing, test math preview, typed/mixed sharing, **Retry save** after a write failure, large fonts, and TalkBack.
- **On device (Paper):** Check a question with multiple large images (scroll, pinch, resize, switch questions, return, share). Rotate, leave and return (ink stays aligned), add lines, switch Paper → Write → Paper, undo/redo, test finger drawing on/off, dark theme (including low-latency overlay and sharing), stylus double-tap, scribble-to-erase, single-stroke eraser, and two-finger undo. Include Paper in [Low-latency ink device checks](low-latency-ink-device-checks.md).
- **On device (Study recording):** Start a middle Today card with a short session limit and rate through in Write and Type. Verify one Study session with expected intervals, duration, subject, and details (including custom and mixed subjects). Toggle recording in both places, skip/return, leave a partial queue, background/resume, rotate, and restart offline before syncing with Focal. Confirm gaps are excluded and an existing Study timer pauses on start.
