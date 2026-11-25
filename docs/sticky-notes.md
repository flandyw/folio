# Sticky-note fixes and verification

Sticky notes retain their existing `TextBox` storage, note-local ink, destination-first transfers, and independent per-page undo. These fixes change only rendering and gesture handling; no storage fields or format versions are added.

## Defects addressed

| Trigger | Fault | Fix |
| --- | --- | --- |
| Scroll into a prefetched page | An attached native surface without a published placement could flash notes at the window origin. | Draw only attached, laid-out surfaces with a published workspace frame. |
| Scroll or relocate the page column | The overlay's retained display list could keep a stale page position. | Compare placements before drawing, invalidate on position/attachment changes, and draw/route input from the same published frames. |
| Open peek or block input during zoom | Notes switched between the workspace overlay and clipped page surface. | Tie rendering ownership to layout, independent of input blocking. |
| Return to the column with an existing native surface | Filtering subscriptions by a non-observable ownership flag could drop the surface after a layout change. | Subscribe all main handles, check current ownership at draw time, and invalidate on ownership changes. |
| Overlap notes from different pages | Registry attachment order decided the top note, disagreeing with document ink routing. | Sort overlay drawing and hit-testing by paper position. |
| Reorder layers with overlapping notes | Note controls hit storage order instead of visible layer order. | Hit-test the visible layer projection. |
| Place a locked note over an editable note | Hit-testing skipped the locked note and selected the note underneath. | Only the topmost visible note accepts hits; locked paper blocks notes beneath it. |
| Cover a focused note's corner with another note | The covered resize grip still intercepted presses. | Grips cannot intercept through another note's paper. |
| Hide or lock a focused note before an action or drop | Stale actions could edit, delete, duplicate, or resize it. | Re-check editability for focus, actions, and gesture completion; hidden seam notes stop pinning pages. |
| Add a second finger during a note drag | The note handler swallowed the pinch stream. | Cancel the preview, release interception, and hand off to navigation with the scale detector seeded by the initial down. |
| Cancel a move or resize | The context menu stayed at the cancelled preview position. | Re-report the committed note bounds on cancellation. |
| Distant `UP` without `MOVE`, or a coalesced excursion returning to start | A drag was misclassified as a focus tap. | Check final displacement and historical samples alongside move events. |
| Undo, edit, or delete a note mid-drag | Releasing the preview could overwrite newer text, ink, or colour. | Compare the picked-up note against current content; cancel stale drags and never resurrect deleted notes. |
| Draw a note taller than 10,000 page units | Reopening truncated it to the decoder's height limit. | Enforce the same dimension bounds during creation and resize. |
| Detach the source while carrying a note | Router cancellation dropped the lift; same-page fallback lost held position. | Drop before router reset, map the retained window position into the current page frame, then clear the gesture. |
| Release a transfer before Compose binds source removal | The source note briefly flashed at its old position. | Suppress the accepted drop until removal commits; restore normally on Undo. |
| Drop onto a loading/locked destination, stale note, or restored duplicate ID | Transfers could lose edits, touch protected layers, or duplicate IDs. | Validate both loaded pages synchronously before writing; refused transfers leave the note unchanged. |
| Transfer between pages at different scales | Paper, text, and ink changed screen size on drop. | Scale dimensions, text size, and local ink by the source/destination scale ratio. |
| Leave an active typing field via page/layout change | Uncommitted text could be lost or overwrite an external Undo. | Commit on deactivation/disposal using the draft's base wording and reject stale overwrites. |
| Type at 8–9 pt or use scaled/aligned/underlined/faded note text | Rendering clamped to text-box minimums and dropped stored formatting. | Honour sticky sizes in layouts/maths and match stored alignment, underline, and opacity. |
| Render differently coloured notes concurrently across editor/exports/thumbnails | Shared mutable paints could leak colours across threads. | Keep sticky-paper paints thread-local. |

## Automated checks

```sh
./gradlew :app:bundleReleaseClassesToCompileJar :app:prepareBackupSmoke
node tools/sticky-notes-smoke.cjs
node tools/backup-smoke.cjs
node tools/page-navigation-smoke.cjs
node tools/palm-rejection-smoke.cjs
./gradlew :app:testDebugUnitTest
./build.sh -p
```

`sticky-notes-smoke.cjs` compiles production `StickyNoteInput`, `StickyNotes`, layer rules, and the native overlay against lightweight Android stand-ins to exercise layer occlusion, protected commands, cancel/pinch handoff, tap travel, stale notes, bounded creation, grip resize, detach drops, scaled transfers, and prefetched overlays. `backup-smoke.cjs` verifies sticky ink, journal/snapshot history, exports, and portable round-trips.

## Device acceptance

Verify on a tablet:

1. Scroll and fling repeatedly through a long notebook with notes on every page (including across seams and outside paper edges), zoomed, during pull-stretch, in split view, and while toggling peek and zoom writing. No note should flash at the origin, lag, or clip.
2. Overlap notes across seams and reordered layers; confirm hidden notes vanish, locked notes shield notes beneath, and drawing/input agree on the top note.
3. Cancel a move/resize and start a pinch from a note on an infinite canvas, fitted page, and continuous column; confirm a cancelled palm never finishes a note or interrupts a pen.
4. Carry notes through auto-scroll onto differently sized pages and outside margins, testing detach, accepted/refused drops, per-page Undo, and reopening.
5. Type, switch pages/layouts, close, duplicate, change formatting, Undo/Redo while typing, and check 8 pt, scaled, aligned, underlined, faded, and formula notes.
6. Export and generate thumbnails for multi-coloured notes while scrolling to confirm stable colours and clean rendering.
