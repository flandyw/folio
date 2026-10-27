# Writing and editing

[Wiki home](README.md) · [Notebooks](notebooks.md) · [Study and exams](study.md)

## Ink and input

The toolbar offers pen, highlighter, eraser, lines, shapes, graph, text, lasso, hand, sticky note, PDF mark area and laser. Tool options include colours, widths and presets. Customize the toolbar order and quick colours; tools that do not fit remain in the overflow menu. Some tools are omitted on surfaces where they do not apply.

Pen strokes support pressure and smoothing. Prediction is temporary drawing feedback, not saved ink. Folio rejects palms while allowing deliberate navigation gestures; Android-cancelled contacts are respected. Finger drawing can be controlled in settings. Hardware behaviour varies: see [palm rejection](../palm-rejection.md) and [pen feel](../pen-feel.md) for device checks.

The laser is a presentation tool: its coloured trail fades and is not saved or added to undo history. The ruler is a toolbar switch; slide it with one finger or rotate it with two. Pen or highlighter strokes started on or near its edge follow that edge.

## Erasing, shapes and graphs

Use the eraser or scribble over ink. Scribble recognition uses a geometry check and an on-device ML Kit gesture model; the model must be downloaded before classifier-based recognition works offline. Hold to erase is a separate, default-on gesture: hold a qualifying scribble until it is highlighted, then lift to erase. If recognition cannot safely approve an ordinary scribble, the ink is preserved. See [document transactions](../document-transactions.md) for the ordinary and held gesture history rules.

Shape assist can turn a held stroke into a fitted shape. Selected ink can also be previewed and cleaned up as a diagram, leaving unrelated handwriting alone. Settings include controls and a practice area. See [shape assist](../shape-assist.md).

The graph tool creates ordinary line strokes for axes, ticks, grid and labels. Presets cover different origins; steps, π-based x labels and square or independent cells can be adjusted. The result remains editable as ink.

## Selection and layers

Use lasso to select content for moving, resizing, rotating, copying, duplicating, deleting or restyling. Selected handwriting can be tidied or converted to text using on-device recognition. A selection can span hosted pages, including sticky notes and images; a cross-page edit is grouped for undo. Moving to a page and some transforms require the relevant pages to be available.

Layers are per page. Hidden or locked layers are protected from editing and selection. Visibility and ordering apply to ink and other page content; see [document transactions](../document-transactions.md) and the storage rules in [AGENTS.md](../../AGENTS.md) before changing them.

## Text, maths, pictures and sticky notes

Text boxes and sticky notes store their editable text. Inline `$…$` and `\(…\)` and display `$$…$$` and `\[…\]` maths render with the offline KaTeX bundle; the source remains stored as text. See [text maths](../text-math.md).

Sticky notes support typed text and their own ink. Tap with the sticky tool to add a note, drag to size one, and drag an existing note to move it. A focused note has a resize grip and a menu for text, drawing, colours, size and other actions. In the scrolling editor, notes can be carried between pages. Note ink belongs to the note and is clipped to it. New-note text size comes from Writing settings; changing it does not alter existing notes. See [sticky notes](../sticky-notes.md).

## Writing follow and zoom

Writing follow helps reveal space or return to a new line while writing on pages. It can use detected answer areas on PDFs and offers manual and automatic line actions. The zoom pane provides an enlarged working area. Settings control timing, movement, line spacing and other behaviour.

An infinite canvas starts as a free workspace: it does not follow just because page follow is enabled. **Write a response** starts a temporary canvas writing session; the first pen stroke places its writing column. Navigation pauses movement without changing that column. See [writing follow](../writing-follow.md) for detailed rules and checks.

## For maintainers

The native surface is `ink/InkView.kt`; shared rendering is `ink/InkRenderer.kt`. Input routing lives in `input/`; pen processing, recognition, shapes and graphs live under `ink/`. Page presentation and toolbar code live under `editor/`. Cross-page capture and durable grouped history are documented in [document transactions](../document-transactions.md).
