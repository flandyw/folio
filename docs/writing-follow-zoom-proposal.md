# Writing follow and zoom pane proposal

Status: the core bottom-docked pane, shared follow decisions, separate return cadence, paragraph spacing and explicit indents are implemented. See [writing-follow.md](writing-follow.md) for current behavior and device checks. Calibration and the later layout/transfer ideas remain proposed extensions. No storage format changes were needed.

## Recommendation

Add **Zoom pane** as a second view of the existing writing-follow session. Keep **Full-page follow** for people who prefer writing directly on a zoomed page. Both views should share answer-area boundaries, handwriting tracking, line destinations, pause/resume and movement history.

The central rule is: **the edge of the visible writing window is not the end of the answer line**. Reaching the window edge reveals more of the same line. Reaching the selected answer area's edge can arm a next-line return.

The supplied StarNote screenshot shows a cyan source frame on a PDF, an enlarged writing strip below, a pale trailing zone, a resize handle and navigation/return/close controls. The proposal recreates those visible interactions in Folio's Material 3 design. A still image cannot establish StarNote's exact trigger behavior, timing or arrow semantics; those details below are proposed Folio behavior.

## 1. Bring the useful parts into full-page writing follow

| Addition | Behavior |
| --- | --- |
| Visible writing bounds | Show subtle start/end margin markers for the selected answer area or canvas response column. Separate these from the visible viewport edge. |
| Soft advance zone | Tint a narrow area near the trailing viewport edge when sideways follow is available. It is a hint to reveal more of the current line, never an instruction to wrap. Mirror for RTL. |
| Next-line preview | Show the exact destination baseline and start point while a return is armed. Expand the existing landing guide with a short countdown indicator. |
| Direct margin adjustment | Provide deliberate drag handles in an “Adjust writing area” state. Ordinary navigation must never move the response column. |
| Clear movement feedback | Use brief statuses such as “More room on this line”, “Next line ready”, “Held for correction” and “Answer area finished”. Keep persistent controls quiet. |
| Same controls in both views | Next line, Back view, Pause and options retain the same meaning. Back view reverses camera movement; ordinary Undo reverses ink. |

Several foundations already exist in the working tree: learned spacing and line ends, list continuation indents, progress/correction tracking, adjustable timing, landing guides, movement history and end-of-area stops. These are reuse opportunities, not wholly new features.

## 2. Functional recreation: Zoom pane

### Entry and layout

Use the existing Writing & peek menu to offer **Open zoom pane**. On a PDF, choose a detected answer area or tap/drag a writing area. On blank paper, choose margins and line spacing. On an infinite canvas, opening the pane explicitly starts or continues a response session; it never grants global follow permission.

The page stays visible above a docked writing pane. A cyan/accent outline shows the exact source rectangle displayed below. That rectangle is a moving window *inside* the writing area, not the writing area's boundaries. At a high magnification, the window will show only part of one answer line.

Start with a bottom dock occupying roughly one third of a landscape tablet's available editor height. A divider resizes it, subject to a usable question view and writing surface. Keep one active dock and one source page. A side dock on wide tablets and an expanded pane on narrow screens are later layout additions.

### Positioning and scale

- Drag the source frame to relocate the writing window. Stop pending movement and rebuild local handwriting evidence at the new location.
- Pinch within the pane to change magnification around the gesture focus. Page-space answer margins and ink remain fixed.
- Resizing the pane changes the amount of visible content while preserving magnification and the current writing baseline where possible.
- A deliberate frame-size handle adjusts magnification; it does not edit answer margins. Margin handles belong to the separate writing-area adjustment state.
- The overview camera stays stable during writing. Recenter it only when the source frame would leave view, after pen lift, or through a deliberate reveal action.

### Controls

Keep **Back view**, **Reveal ahead**, **Next line**, **Pause** and **Close** reachable without covering ink; place advanced options in overflow. Reveal ahead moves horizontally along the same baseline with a little context overlap. It stops at the answer margin. Offer Previous line in overflow. Mirror layout/directions for RTL and place controls for the selected pen hand without conflating handedness with reading direction.

Closing the pane restores the overview camera and pauses follow. If the user already had an explicit canvas response session, keep its column until Finish response; reopening must not replace that column.

### Ink and navigation

Writing and erasing in the pane edit the actual page immediately. Pressure, stroke style, active layer, locked/hidden layers, scribble erasing and ordinary Undo work through the existing editor path. Render the same PDF, ink, images and text as the main page. All input samples map through a stable pane-to-page transform; magnification never changes saved page coordinates or pen width in page units.

Only one surface owns a gesture and follow movement at a time. The overview remains a navigation/selection surface while writing in the pane. Selection, text editing and cross-surface gestures can initially hand back to the full editor; do not offer controls that cannot work in the pane. A live stroke must be visible in both views without committing it twice.

## 3. Smarter automatic line return

Treat horizontal reveal and line return as separate decisions. Return is deliberately more conservative than horizontal reveal.

| Situation | Decision |
| --- | --- |
| Writing approaches the visible pane edge, with answer space remaining | Reveal ahead on the same line after a suitable pen lift. |
| Sustained forward text reaches the actual answer margin | Arm a return to the next valid line; show its start and countdown. |
| Dot, crossbar, accent or correction behind the frontier | Cancel the countdown and hold the view. Preserve the line frontier; reassess after the finishing mark and a fresh quiet interval. |
| A single mark at the margin | Hold. It is insufficient evidence of a completed line. |
| Writer starts the next line themselves | Accept the natural break and cancel the old return. Do not add another line advance. |
| Fraction, superscript, long underline or diagram | Hold automatic wrapping. In Maths mode reveal vertical room conservatively and keep Next line explicit. |
| Last valid line in the selected answer block | Stop and show “Answer area finished”. Switching questions, columns or pages requires a deliberate action. |
| Erase/Undo removes the final word | Retract the frontier and invalidate any pending return. |
| Pan, pinch, tool change, pause, canceled input or source-frame drag | Cancel pending movement; navigation cannot silently authorize a return. |

### Destination and timing

Choose the next line in this order: a user-confirmed printed guide in the same answer block; a manually set guide/spacing; reliable spacing learned from natural line breaks; the configured fallback. Distinguish confirmed guides from uncertain PDF detection. In an uncertain region, let the user set two baselines and margins; do not guess a different question's lines.

Preserve the selected column, explicit indent and reading direction. Wrapped list items can use the existing hanging-indent tracking; an explicit Next line can start a new item at the marker margin. An **Indent / Reset indent** override prevents a short first word being mistaken for a bullet.

The existing pen-lift rhythm is a good starting point for short horizontal reveals. Learn a separate return pause from actual line endings; do not speed up returns merely because the user repeatedly canceled horizontal movement. Start with the user's configured delay and keep adaptive timing bounded. If evidence is ambiguous, show Next line ready and wait for the button.

During return countdown, show a destination preview and a small progress indicator. Pen-down cancels the pending movement. During animation, pen-down freezes the transform before the first sample is mapped, and the return is not considered complete unless it reached the destination. Avoid blind resumption of interrupted returns; reevaluate the current baseline and next destination first. Back view restores the earlier view and tracking state without removing ink.

A brief pause cannot prove that a sentence, equation or word is finished. Use local geometry and explicit mode/area selection; semantic “AI completion” is not needed for the first version.

### Defaults

Automatic return remains opt-in for pages and starts **off for every new infinite-canvas response**, including one started through Zoom pane. Maths uses manual returns. A user's explicit column survives pan, zoom, resize, natural breaks and switching views. Only Start a new column here replaces it. See all working pauses both views; Return to working restores the prior camera/session.

## 4. Further ideas worth adding

**First release:** persistent source/question context, resizable pane, a small overlap when revealing ahead, adjustable margins, destination preview, Back view, and a generous writing area with controls placed away from the pen hand. Preserve pressure and touch-history handling through the existing palm-rejection router.

**Next:** tap two printed lines to calibrate a poorly scanned PDF; Indent / Reset indent; a Paragraph action that returns with deliberate extra spacing; an option to keep the question or diagram pinned while the main overview reveals the moving source frame; and keyboard/stylus shortcuts using the existing input mechanisms.

**Later:** a side dock, recent writing areas for quick revisits, and an optional same-baseline overlap transfer zone inspired by the pale zone in the screenshot. Test a transfer zone carefully: it must map every sample to one page location, avoid duplicated strokes and avoid moving content under the pen. Begin with a simpler pen-up horizontal reveal.

Respect system reduced-motion settings and provide readable statuses, labeled controls and adequate touch targets. Color can reinforce the source-frame relationship, but must not be the only way to identify it.

## 5. Implementation shape

This is a proposed decomposition, not a commitment to new class names:

- A shared session/controller owned above the views: page id, writing area, source rectangle, presentation mode, follow tracker, pending destination and movement history. Keep the existing CanvasWritingSession authorization gate.
- Pure decision helpers near WritingFollow, WritingGuides, FollowRhythm and FollowGlide: distinguish viewport reveal from region return, validate destinations and derive movement/status commands. Share them between views.
- A Compose pane/overview layout in EditorScreen, with a camera-enabled InkView for pane input. Existing pageCamera support is a useful starting point, but editable dual-view behavior requires inspection; it is not a drop-in solution.
- Route edits through the existing FolioViewModel callbacks and serialized page-save path. Share rendering assets carefully. Audit local view copies, caches and live-stroke ownership so one view cannot overwrite another's recent ink or allocate a second full-size raster needlessly.
- Settings in AppPrefs for actual durable defaults. Source frames, cameras, countdowns and visit sessions are presentation state. No notebook format, journal or ink migration is required by this design.

The highest implementation risk is maintaining stable input coordinates while two views observe one page. Return heuristics should come after a correct manual pane and bounded navigation.

## 6. Delivery order and acceptance checks

1. **Manual pane:** overview/source-frame link, magnification, resizing, native ink/eraser, manual horizontal reveal, Next line, Back view and Close. Verify ink alignment, layer rules, save/reopen and Undo on a device.
2. **Shared follow:** use existing correction tracking, guides, timing and movement rules in both views. Add bounds and destination feedback to full-page follow.
3. **Return refinement:** a separate return cadence, finishing-mark grace, calibration fallback and explicit indent overrides. Then evaluate advanced transfer/side-dock ideas.

Extend the pure writing-follow smoke scenarios for pane-edge versus answer-edge decisions, natural breaks, finishing marks, interrupted animation, RTL, learned spacing and last-line stops. On a OnePlus Pad 3, verify pressure/history, palm contacts across the divider, pen-down during movement, orientation/resize, scanned PDF guides, dense pages, finite pages and explicit infinite-canvas sessions. Both views must produce identical page-space ink, one save per edit and one ordinary undo step per stroke.

Do not run a publishing build for this proposal. During implementation use the repository's prescribed checks and device validation.
