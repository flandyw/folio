# Laser pointer and lasso ink-to-text

Two parity features that need no storage-format change.

## Laser pointer

- **Turn on:** page options menu → **Present → Laser pointer**. The setting is per open notebook and session-only; it resets when the notebook is reopened, so a presentation never starts with it on.
- **Behaviour:** with a drawing tool active (pen, highlighter, shapes), a stylus leaves a fading red trail instead of ink. Fingers still pan and zoom. Nothing is stored: the trail is not in the page, the journal, undo, exports or backups.
- **Lifetime:** the trail fades over 900 ms, is capped at 240 samples, and is cleared when the laser is turned off or the active page changes. The eraser, lasso, text and hand tools keep their normal stylus behaviour, so the laser never blocks erasing.
- **Ownership:** `InkView.laserPointer` gates `beginStroke`'s ink branch (after the eraser branch). Once a laser stroke starts, `handleLaser` owns the pen until lift, cancel, or a second finger, so a stale `laserDown` flag cannot swallow a new press. Cancel paths clear it in `cancelGesture`.

Check on device: draw with the stylus with the laser on and confirm no ink appears, the trail fades, a finger still pans and pinches, the eraser still erases while the laser is on, and turning the laser off leaves no trail behind.

## Convert handwriting to text (lasso)

- **Use:** select handwriting with the lasso, then ⋯ on the selection bar → **Convert handwriting to text**.
- **What happens:** the selected ink is drawn black on white at about 2.5 px per page unit (capped at 2048 px) and read by the same on-device ML Kit recogniser the PDF search and marks use (`recognizePdfText`). The picture is recycled and not kept. The recognised lines become one text box at the selection's top-left, width at least the ink's width and at least 160 units, sized from the recogniser's median line height. The text takes the ink colour when the selection used one colour (highlighter ink is ignored for this).
- **Commit:** `FolioViewModel.replaceInkWithText` removes the selected strokes and adds the box through `updateContent`, so it is one undoable step. It refuses if the strokes are no longer on the page. A second conversion is ignored while one is running.
- **Limits:** the recogniser is a printed-text model. Neat handwriting converts well; cursive or messy writing may not. "No readable text" is reported through the editor's error message, not a dialog. Digital Ink Recognition (stroke-based, better for handwriting) would need a new dependency and model download; it is the upgrade path if accuracy is not good enough.

Check on device: convert neat print and cursive samples, check the box position and size against the original, undo once and confirm the ink returns, convert on a PDF page and on a page with several colours, and check that conversion works offline once ML Kit's model is installed.
