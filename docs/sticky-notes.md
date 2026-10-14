# Sticky-note fixes and verification

Sticky notes retain the existing `TextBox` storage, local ink, destination-first transfers, and independent per-page undo. These fixes change rendering and gesture handling; they add no storage fields or format versions.

## Defects addressed

| Trigger | Fault | Fix |
| --- | --- | --- |
| Scroll into a prefetched page | An attached native surface without a published placement could flash its notes at the window origin. | Draw only attached, laid-out surfaces with a published workspace frame. |
| Scroll or relocate the page column | The overlay's retained display list could keep an earlier page position. | Compare placements before drawing and invalidate on position/attachment changes; draw and forward input using the same published frames. |
| Open a peek or block input during a zoom gesture | Notes switched between the workspace and clipped page surface. | Rendering ownership follows the layout independently of input blocking. |
| Return to the column with an existing native surface | Filtering subscriptions by a non-observable ownership flag could omit the surface after its layout changed. | Subscribe all main handles, read current ownership at draw time, and invalidate when ownership changes. |
| Overlap notes belonging to different pages | Registry attachment order decided the top note, disagreeing with document ink routing. | Sort overlay drawing and hit-testing by paper position. |
| Reorder layers with overlapping notes | Note controls hit storage order instead of visible layer order. | Hit-test the visible layer projection. |
| Put a locked note over an editable note | Hit-testing skipped the locked paper and selected the covered note. | Only the top visible note can accept a hit; locked paper blocks the notes beneath it. |
| Cover a focused note's corner with another note | The covered resize grip still stole the press. | A grip cannot intercept through another note's paper. |
| Hide/lock a focused note before a menu action or drop | Stale actions could still edit, delete, duplicate or resize it. | Resolve current editability for focus, actions and gesture completion. Hidden seam notes no longer pin their pages. |
| Put a second finger down during a note drag | The note handler swallowed the pinch stream. | Abandon the preview, release interception and hand navigation back to the normal handler, with the scale detector seeded by the initial down. |
| Cancel a move or resize | The context menu retained the canceled preview's position. | Re-report the committed note after cancellation. |
| Receive a distant up without a move, or a coalesced excursion returning to the start | A drag was misclassified as a note-focus tap. | Check final displacement and historical samples as well as move events. |
| Undo, edit or delete a note during a drag | Finishing the preview could replace newer words, ink or colour. | Compare the picked-up note against current content; cancel stale gestures and never resurrect a deleted note. |
| Draw a note taller than 10,000 page units | Saving and reopening shortened it because creation exceeded the decoder's height limit. | Apply the same dimension limits during creation and resize. |
| Detach the source while carrying a note | Router cancellation discarded the lift before the drop; a same-page fallback also lost its held position. | Drop before router reset, convert the retained window position back into the current page frame, then clear the gesture. |
| Release a transfer before Compose binds the source removal | The source note briefly reappeared at its old position. | Suppress the accepted drop until removal is acknowledged; restore it normally on Undo. |
| Drop onto a loading/locked destination, a stale note or a duplicate ID restored by independent Undo | The transfer could lose changes, write on a protected layer or duplicate a note ID. | Validate both loaded pages synchronously before either write; a refused transfer leaves the note unchanged and cannot fall through to a local preview commit. |
| Transfer between papers displayed at different scales | Paper, text and handwriting changed their screen size at release. | Convert dimensions, text size and local ink using the source/destination scale ratio. |
| Stop displaying a typing field by changing the active page/layout | Uncommitted typing could be lost or leave blank note text; external Undo could later be overwritten by the old draft. | Finish typing on deactivation/disposal, retain the draft's source wording and reject commits over changed wording. |
| Type at 8–9 pt, or use scaled/aligned/underlined/faded note text | Rendered text used the ordinary text-box minimum and the field omitted stored formatting. | Honor sticky sizes in layouts/math assets and match the field's stored alignment, underline and opacity. |
| Render differently coloured notes concurrently for the editor and exports/thumbnails | Shared mutable paper paints could exchange colours between threads. | Keep sticky-paper paints per rendering thread. |

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

The sticky runner compiles the production `StickyNoteInput`, `StickyNotes`, layer rules and native workspace overlay from source. Small Android stand-ins supply motion events, view lifecycle, placement and drawing records, so it checks actual gesture/overlay decisions without an emulator or another test dependency. It covers layer ordering and occlusion, protected commands, cancel/pinch handoff, final/history tap travel, stale/deleted notes, bounded creation round trips, grip resize, scroll-relative detach drops, accepted/refused drops, scaled transfers and placed/prefetched overlay input. Stand-ins cannot establish Android frame timing, real stylus routing, Compose/IME lifecycle or pixel appearance.

Backup smoke additionally verifies sticky ink, journal/snapshot history, export placement and portable round trips with real codecs. The canonical signed experimental build publishes through the existing Folio release workflow.

## Device acceptance

No Android device was connected for this change. Check these on a tablet before treating the scrolling-flash fix as visually confirmed:

1. Scroll and fling repeatedly through a long notebook with notes on every page, including notes crossing seams and extending past paper edges. Repeat while zoomed, during pull/stretch, in a split pane, and while opening/closing peek and zoom writing. No note should flash at the window origin, lag a page or move into a paper clip.
2. Overlap notes across seams and across reordered layers. Tap, write, erase, resize and move them. A hidden note must be absent; a locked visible note must shield content below; drawing/input must agree about the top note.
3. Move/resize a note, cancel the gesture, and begin a pinch from it on an infinite canvas, fitted page and continuous column. The original paper and menu should return, and pinch should work. A canceled palm must neither finish a note nor interrupt an unrelated pen.
4. Carry notes through auto-scroll, onto papers of different widths, and into outside margins. Test forced detach, accepted/refused destinations, and Undo on each page separately. The words, ink, pressure, colour and displayed size should survive release and reopening.
5. Type, switch pages or layouts, close the notebook, duplicate, change formatting and reopen. Test Undo/Redo while the field is open. Check 8 pt and scaled notes, alignment, underline, faded text, formulas, wrapping and keyboard focus.
6. Export and generate thumbnails containing different paper colours while scrolling through the same notes. Verify stable colours and no stale paper/control fragments.
