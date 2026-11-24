# Writing and editing

[Wiki home](README.md) · [Notebooks](notebooks.md) · [Study and exams](study.md)

## Ink and input

The toolbar includes pen, highlighter, eraser, lines, shapes, graph, text, lasso, hand, sticky note, PDF mark area, and laser tools. Each drawing tool exposes colour, width, and saved presets. You can customise the toolbar order and quick colours; tools that do not fit move into the overflow menu, and tools that do not apply to the current surface are hidden.

Pen strokes support pressure sensitivity and corner-preserving smoothing; tip prediction is temporary visual feedback and is never saved. On Android 10 (API 29) and later, opaque ballpoint stylus strokes also draw their leading edge on a front-buffer overlay (`input.lowLatencyInk`, on by default). Finger ink, other tools and brushes, translucent ink, ruler-locked strokes, and strokes starting on a sticky note use the standard page path. The front-buffer path has not yet been device-verified.

Folio rejects palms while allowing deliberate pinch navigation and respects Android-cancelled contacts. Finger drawing can be toggled in Settings. Because digitiser behaviour varies across devices, see [Palm rejection](../palm-rejection.md), [Pen feel](../pen-feel.md), and [Low-latency ink device checks](../low-latency-ink-device-checks.md).

The **laser** is a presentation pointer whose coloured trail fades out without saving or entering undo history. The **ruler** is toggled from the toolbar: slide it with one finger, rotate it with two, and start pen or highlighter strokes near its edge to draw along it.

## Erasing, shapes, and graphs

- **Eraser and scribble to erase:** Use the eraser tool or scribble over ink. An unmistakable pen scrub (several tight back-and-forth legs over ink) erases on geometry alone, even offline. Shorter or looser scrubs and highlighter scrubs are verified by an on-device ML Kit gesture model (which must be downloaded first).
- **Hold to erase:** On by default: hold a qualifying scribble until it highlights for deletion, then lift to erase in a single undo step without committing the scrub. If neither path approves an ordinary scribble, your ink stays intact. See [Document transactions](../document-transactions.md) for undo rules.
- **Shape assist:** Hold the pen still at the end of a stroke to snap it into a fitted shape. You can also preview and clean up lasso-selected diagrams without touching nearby handwriting. Settings includes controls and a practice canvas. See [Shape assist](../shape-assist.md).
- **Graph tool:** Draws axes, ticks, grid lines, and labels as ordinary editable line strokes. Presets cover common axis origins, with configurable steps, π-based x-axis labels, and square or independent cells.

## Selection and layers

Use the **lasso** to move, resize, rotate, copy, duplicate, delete, or restyle content. Selected handwriting can also be tidied or converted to text using on-device recognition. Selections can span hosted pages (including sticky notes and images), and cross-page edits undo together as one step.

**Layers** are per page. Hidden or locked layers cannot be edited or selected. Visibility and stacking order apply to ink and all other page items; read [Document transactions](../document-transactions.md) and [AGENTS.md](../../AGENTS.md) before changing layer behaviour.

## Text, maths, pictures, and sticky notes

Text boxes and sticky notes store plain editable source text. Inline `$…$` / `\(…\)` and display `$$…$$` / `\[…\]` expressions render via the offline KaTeX bundle while keeping the source intact. See [Text maths](../text-math.md).

**Sticky notes** hold both typed text and their own clipped ink:
- Tap with the sticky tool to place a note, or drag to size it.
- Drag an existing note to move it, or carry it across pages in the continuous editor.
- Tap a note to reveal its resize grip and action bar (text, drawing, colours, size, and more).
- Default text size for new notes lives in **Settings → Writing** and does not alter existing notes. See [Sticky notes](../sticky-notes.md).

## Writing follow and zoom

**Writing follow** pans to reveal space and returns to the next line as you write, using detected PDF answer areas where available and offering manual or automatic line return. The **zoom pane** opens a magnified writing frame without duplicating the page. Settings controls timing, movement, and line spacing.

An infinite canvas starts as a free workspace and does not auto-follow simply because page follow is enabled. Choosing **Write a response** starts a temporary writing session whose first pen stroke anchors the column; manual navigation pauses follow without moving that column. See [Writing follow](../writing-follow.md) for full rules and device checks.

## For maintainers

- `ink/InkView.kt` is the native surface; `ink/InkRenderer.kt` handles shared rendering.
- `input/` owns palm rejection and stylus routing; `ink/` owns pen feel, recognition, shapes, and graphs.
- `editor/` owns page presentation, toolbars, selection, follow, and sticky notes.
- Cross-page capture and durable grouped history are documented in [Document transactions](../document-transactions.md).
