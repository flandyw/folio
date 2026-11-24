# Writing follow and zoom pane proposal

> **Status:** The core bottom-docked zoom pane, shared follow engine, separate return cadence, paragraph spacing, and explicit indents are shipped. See [Writing follow](writing-follow.md) for current behaviour and device checks. Two-line PDF calibration, side dock, pinned question context, and overlap-transfer zones remain unimplemented proposals. No storage format changes were required.

## Recommendation

Offer **Zoom pane** as a second view of the existing writing-follow session alongside **Full-page follow**, sharing answer-area bounds, handwriting tracking, line targets, pause/resume, and movement history.

Core principle: **the edge of the visible writing window is not the end of the answer line.** Approaching the window edge reveals more of the same line; reaching the answer area's margin arms a return to the next line.

The design adapts the visible ergonomics of StarNote (source frame on PDF, magnified lower writing strip, pale trailing zone, resize handle, navigation/return/close controls) into Folio's Material 3 layout. Trigger rules, timing, and arrow semantics below are Folio-specific proposals.

## 1. Improvements to full-page writing follow

| Addition | Behaviour |
| --- | --- |
| Visible writing bounds | Subtle start/end margin markers for the selected answer area or canvas response column, distinct from the viewport edge. |
| Soft advance zone | Pale tint along the trailing edge when sideways follow is available; signals room ahead on the same line, never a wrap instruction (mirrored for RTL). |
| Next-line preview | Shows the destination baseline, start point, and countdown while a return is armed. |
| Direct margin adjustment | Explicit drag handles in an "Adjust writing area" state; ordinary navigation never shifts the column. |
| Clear status feedback | Brief statuses (*More room on this line*, *Next line ready*, *Held for correction*, *Answer area finished*) with quiet persistent controls. |
| Unified controls | **Next line**, **Back view**, **Pause**, and **Options** share identical semantics across both views (**Back view** reverses camera motion; Undo reverses ink). |

## 2. Zoom pane design

- **Entry and layout:** Open via **Writing & peek → Open zoom pane**. Displays the full page above a bottom-docked writing pane (~1/3 landscape tablet height, resizable via divider) with an accent source frame tracking the exact zoomed rectangle. Side dock (wide tablets) and expanded pane (narrow screens) are future extensions.
- **Positioning and scale:** Drag the source frame to relocate the window, pinch inside the pane or drag the corner handle to magnify, and resize the pane while keeping page-space magnification and baseline stable. The overview camera moves only when the source frame nears its edge.
- **Controls:** Keep **Back view**, **Reveal ahead** (horizontal advance with overlap, stopping at the answer margin), **Next line**, **Pause**, and **Close** reachable away from the pen hand (RTL-aware). Closing restores the overview camera and pauses follow without discarding an active canvas response column.
- **Ink and navigation:** Edits commit directly to the real page with full pressure, brush, layer, scribble, and Undo support; live strokes appear in both views without double-committing. Only one surface owns a gesture at a time; selection, text, and cross-surface tools hand back to the full editor.

## 3. Automatic line return rules

Horizontal reveal and line return are distinct decisions, with return deliberately more conservative:

| Situation | Decision |
| --- | --- |
| Approaching visible edge with answer space left | Reveal ahead on the same line after pen lift. |
| Sustained forward writing reaches answer margin | Arm return to the next valid line with preview and countdown. |
| Dot, crossbar, accent, or correction behind frontier | Cancel countdown and hold; reassess after a fresh quiet pause. |
| Single mark at margin | Hold: insufficient evidence of a finished line. |
| Writer starts the next line manually | Accept natural break and cancel pending return. |
| Fraction, superscript, underline, or diagram | Hold automatic wrap; keep **Next line** manual in Maths mode. |
| Last valid line of answer block | Stop and show *Answer area finished*. |
| Erase/Undo removes final word | Retract frontier and cancel pending return. |
| Pan, pinch, tool change, pause, cancelled input, or frame drag | Cancel pending movement; navigation never silently authorises a return. |

- **Destination priority:** User-confirmed printed guide → manual guide/spacing → learned spacing from natural breaks → configured fallback. Preserve column, explicit indent, and reading direction (with **Indent / Reset indent** overriding bullet inference).
- **Timing & interruption:** Learn return cadence separately from horizontal reveals, starting from the user's configured delay. Pen-down cancels countdowns or freezes animation immediately before mapping the first sample; interrupted returns re-evaluate instead of blindly resuming.
- **Defaults:** Automatic return is opt-in on pages, starts off on new infinite-canvas responses, and stays manual in Maths mode.

## 4. Phased roadmap and architecture

- **First release (shipped):** Source/question overview, resizable pane, horizontal reveal overlap, destination preview, Back view, pen-hand control placement, and shared palm rejection.
- **Next:** Two-line calibration for poorly scanned PDFs, pinned question context while scrolling, and keyboard/stylus shortcuts.
- **Later:** Side dock, recent writing areas, and an optional same-baseline overlap transfer zone (strictly verified against duplicate strokes or shifting ink under the pen). Respect reduced-motion settings, readable statuses, accessible labels, and non-colour cues.

**Implementation shape:** Shared session/controller above the views (`CanvasWritingSession` gate preserved), pure decision helpers (`WritingFollow`, `WritingGuides`, `FollowRhythm`, `FollowGlide`), Compose pane/overview layout in `EditorScreen` with a camera-enabled `InkView`, edits routed through `FolioViewModel` and the serialised writer, and durable defaults in `AppPrefs` (no storage format change). Primary risk: coordinate stability across two live views of one page.

## 5. Acceptance checks

Verify via `writing-follow-smoke.cjs` (pane-edge vs answer-edge, natural breaks, finishing marks, interrupted glides, RTL, learned spacing, last-line stops) and on a OnePlus Pad 3 (pressure/history, palms across divider, pen-down mid-glide, rotation/resize, scanned PDFs, dense pages, finite pages, and explicit canvas sessions). Both views must produce identical page-space ink, one save per edit, and one Undo step per stroke.
