# Split view

Two documents side by side (stacked below 600 dp), modelled on Goodnotes and Notability: one button to open
it, a chooser that appears in the new pane, and no modes to pick.

## How it works for the user

- **Open:** the split button in the editor toolbar, *Split view…* in the notebook
  menu, Ctrl + \ or the command palette, or in the tab strip hold a tab and pull it down out of the
  strip: it lifts off as a chip (the document's current page and name) under your finger, the screen
  shows a left and a right half (see *Drop side*), and letting go opens it on that side
  (`TabDrag`/`LocalTabDrag`, drawn by `TabDragChip` and `SplitDropZone`). Dragging back up into the
  strip cancels and returns to reordering. The screen splits at once
  and the new pane shows a chooser: search, **This notebook** (opens on the page being edited, so two pages
  of one notebook are one tap apart), **Open tabs**, then **Recent** notebooks.
- **Tabs:** tap to switch; press, hold and drag sideways to reorder (like a browser). The order and the
  open tabs are kept across restarts (`workspaceTabs` in preferences as well as the saved state).
  Neighbours spring into their new slots while the held tab follows the pointer directly; on release
  or cancellation it settles back into place. New and closed tabs expand/collapse with a short fade.
- **One toolbar, two editors:** the bar (tabs and tools) spans the whole screen above both panes. The
  editor hands it up through `LocalToolbarPortal` (with its composition locals, so its menus behave as
  if drawn in the editor), and the panes sit below it. Neither pane is marked as the inactive one:
  no outline, both split tabs look alike, and the tool travels with the toolbar when the editor
  changes pane (`swapCompanion` carries it), so the pen never changes just because you moved over.
- **Tabs show the split:** both documents get a tab (opening a document beside adds its tab), side by
  side in screen order and wrapped in one tinted, outlined group led by the split mark (`splitTabs`,
  `SplitTabGroup`). The frame follows the animated slots as a separate decoration, so grouping never
  recreates a held tab. Tapping the other half's tab works there.
- **Closing:** a background tab has no close button (tap selects it, then its close shows), so a tap
  never closes by accident. The split pair's capsule has one close, which ends the split and keeps both
  tabs open; after that each tab closes on its own (`WorkspaceManager.closeTab`: closing the tab being
  worked on leaves the other document as the editor). A split parked behind a closed tab is forgotten.
- **Drop side:** a tab pulled onto the page opens on the half it is dropped on (left/right, or
  top/bottom when stacked; `SplitPanes.dropsAtStart`), and the editor takes the other side.
  `SplitDropZone` shows both halves, lighting the one under the finger with "Release to open on the …".
  The tab's accessibility actions offer *Open on the left* / *Open on the right*.
- **Write in both at once:** with a pen, highlighter, eraser, shape, laser or the hand selected, the pen
  works straight in the other pane (`LocalSharedInk`). The toolbar's Undo/Redo takes back whichever pane's
  edit is newest (`DocumentEngine.undo` compares the other page's stack sequence).
- **The other pane has no bar of its own:** it is the editor's own column on the editor's own
  `ContinuousDocumentHost` (same paper width, margins, gaps and top inset), so pinch zoom, sideways
  pan, flings, the fast-scroll thumb, the eraser ring and sticky notes hanging off the paper
  (`DocumentStickyWorkspace`, over the pane's own `EditorSession`) behave exactly as in the editor; an
  infinite canvas stays one canvas. The page mostly on screen is the pane's page, so page links, Undo
  and the saved position follow it; writing on a page makes it the pane's page.
  Fingers never move the editor: they scroll, zoom and follow links in either pane alike. Only a pen
  holding a tool the pane can't use (text, lasso, sticky notes, mark area…) makes it the editor; the
  documents stay put and only the toolbar's contents change. Page link, swap sides and saved
  workspaces are in the notebook menu and the command palette.
- **Trading places is seamless:** the pane keeps its exact spot as a page-relative anchor (the editor's
  `documentPageId`/`documentPageY` plus its zoom and pan, written just before it takes focus and
  debounced while scrolling), so when it becomes the editor, and the editor becomes the pane, neither
  document moves or changes zoom on screen.
- **Divider:** a slim 6 dp bar with a centred pill (28 dp grab area) that lights up and lengthens
  while held; both panes keep identical 10 dp margins so the pill sits midway between the papers. Drag to resize (it settles on 30/50/70%). Drag past the limit towards an edge and the pane
  being squeezed shows *Release to close*. Letting go there closes that pane, and closing the editor's pane
  leaves the other document as the editor. Double-tap for an even split.
- **Switching notebooks:** a split belongs to the notebook it was opened beside (`companionHostId`).
  Opening another notebook parks it, and opening the host again brings the second pane back.

## Code

- `WorkspaceScreen.kt`: `SplitLayout` keeps the editor as the first child wherever it sits, so opening,
  closing or flipping the split never remounts the editor. `CompanionPane` is the other document,
  `focusOnTouch` does the pen/tap focus, and `LocalSplitView` gives the toolbar and tab strip the
  open/close/open-beside actions.
- `SplitChooser.kt`: the in-pane chooser, over the pure `SplitChooser.choices` in `WorkspacePicker.kt`.
- `EditorWorkspace.kt`: `SplitPanes.dragged` / `release` (pure: live drag range, snap, close edges).
- `WorkspaceManager.kt`: `showCompanion(id, pageId?)`, `swapCompanion` (flips side *and* share so the
  divider stays put), `closeEditorPane`, and the parked split in `open`.
- `SplitViewRulesTest` covers the release rules and chooser ordering.

The old Split/Reference `CompanionMode` and the Library detour (`PickerPurpose.COMPANION`) are gone. A
saved workspace's `mode` field is ignored when it is read.

## Device checks

1. Open a notebook, tap the split button, and pick a PDF. Write in the editor, then write on the PDF with
   the pen: the stroke lands on the PDF and the toolbar stays put; the pane's Undo removes it. Switch to
   lasso and touch the PDF with the pen: the toolbar moves over, the lasso stays selected and no
   stroke is drawn. Tap the PDF with a finger: nothing moves over.
7. Drag tabs to reorder them, restart the app, and check the order. Drag a tab down onto the page: it
   opens beside the editor. With short and long tab titles, cross several neighbours, pause just past
   a swap, then reverse: neighbours slide aside and the held tab stays under the pointer without
   swapping back on its own. Release between slots and cancel a drag: it springs home without
   persisting a canceled order. Repeat with either split tab, pulling down and back into the strip.
   Open and close several tabs quickly: their space expands/collapses, the active tab remains visible,
   and a closing tab cannot be selected again. Also check a scrolled strip and right-to-left layout.
2. Pick **This notebook**: both panes show the same notebook. Write on each page in turn and check that
   both thumbnails update and undo stays per page.
3. Drag the divider to each edge: *Release to close* appears, and the remaining document keeps its page
   and zoom.
4. Rest a palm on the other pane while writing: the panes don't switch.
8. Scroll both panes to the middle of a page and pinch-zoom each, then switch panes (pen with lasso, or
   the tab) and back: each document stays on the same line at the same zoom.
9. In the other pane: pinch zoom, pan sideways when zoomed, fling, drag the fast-scroll thumb, and
   check sticky notes on and beside the paper are drawn and take the pen like in the editor.
5. Follow a link in a PDF pane with a finger: it turns the page and the pane doesn't take focus.
6. Open a third notebook from the tab strip and come back: the split returns. Rotate the device and
   restart the app with a split open.
