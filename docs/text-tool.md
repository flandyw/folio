# Inline text tool

Select Text and tap the page to type at that location. Tap an existing box to edit it.
Typing uses a transparent Android `EditText` above `InkView`, with native caret,
selection, clipboard and IME composition. The selected box's stored text is omitted
from the ink layer while the field draws it. Its frame follows the same page scale
and camera origin as the renderer, including infinite canvases and music sheets.

The editor toolbar offers Done, bold/italic/underline, size, duplicate and delete.
More formatting expands inline for alignment, colour and opacity. The grip above
the box moves it; the lower right grip changes wrap width without scaling the font.
Both grips also offer accessibility actions. Formatting applies to the whole box,
as in the existing text model.

Done, tapping outside the field, Back, Escape, Ctrl+Enter, changing tools/pages, closing the editor and app stop
finish typing. The draft is saved across recreation. Completion addresses its
original page ID and records one ordinary text transaction, so typing, formatting,
moving and resizing during that visit form one Undo step. An empty new box is
discarded; clearing an existing box removes it. Duplicate finishes the source
and starts a new draft offset from it. The storage format is unchanged.

The field retains its native Editable when formatting changes, preserving IME
composition and cursor/selection. A page scroll or a typing-session camera pan
keeps the caret clear of the keyboard and editor toolbar.

## Device checks

Check phone/tablet, portrait/landscape, narrow split panes and a hardware keyboard:

1. Tap a blank page, type and paste multiline text. Select words, cut/copy/paste,
   and use a composing IME. Confirm there is no text dialog or full screen IME.
2. Toggle styles, size, alignment, colour and opacity while selecting or composing
   text. Confirm the caret/selection remains and the saved rendering matches.
3. Move and resize using grips and accessibility actions. Check finite page edges,
   document zoom, infinite pan/zoom, PDFs and music sheets. After shrinking a box,
   tap its former bounds: the completed edit must not reopen the older wording or width.
4. Type near the bottom with the software keyboard open and keep adding lines.
   Confirm the caret stays visible without unexpected jumps or input loss.
5. Finish using Done, tapping outside, Back, Escape, Ctrl+Enter, a different tool or another page.
   Undo/Redo should restore exactly one visit's changes on its original page.
6. Rotate, background/resume, close/reopen and switch notebooks while editing.
   Confirm wording and formatting survive, with no duplicate boxes.
7. Duplicate an edited box, delete one, and clear all its text. Undo each action.
   Confirm hidden/locked layers and read-only reference panes cannot be edited.
