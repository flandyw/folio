# Split view

Two documents side by side (stacked below 600 dp), modelled on Goodnotes and Notability: one button to open
it, a chooser that appears in the new pane, and no modes to pick.

## How it works for the user

- **Open:** the split button in the editor toolbar, *Split view…* in the notebook
  menu, Ctrl + \ or the command palette, or in the tab strip hold a tab and pull it down out of the
  strip: it lifts off as a chip (the document's current page and name) under your finger, a dashed
  *Drop to open beside* outline marks the pane it will fill, and letting go there opens it
  (`TabDrag`/`LocalTabDrag`, drawn by `TabDragChip` and `SplitDropZone`). Dragging back up into the
  strip cancels and returns to reordering. The screen splits at once
  and the new pane shows a chooser: search, **This notebook** (opens on the page being edited, so two pages
  of one notebook are one tap apart), **Open tabs**, then **Recent** notebooks.
- **Tabs:** tap to switch; press, hold and drag sideways to reorder (like a browser). The order and the
  open tabs are kept across restarts (`workspaceTabs` in preferences as well as the saved state).
- **One toolbar, two editors:** the bar (tabs and tools) spans the whole screen above both panes. The
  editor hands it up through `LocalToolbarPortal` (with its composition locals, so its menus behave as
  if drawn in the editor), and the panes sit below it. The pane the toolbar works on has a faint
  accent outline; its tab carries the accent line.
- **Tabs show the split:** both documents get a tab, side by side in screen order, filled and marked
  with the split icon (`splitTabs`). Tapping the other half's tab works there; closing it closes the pane.
- **Write in both at once:** with a pen, highlighter, eraser, shape or laser selected, the pen writes
  straight into the other pane (`LocalSharedInk`). The toolbar's Undo/Redo takes back whichever pane's
  edit is newest (`DocumentEngine.undo` compares the other page's stack sequence).
- **The other pane has no bar of its own:** a scrolling column of its pages, like the editor's (an
  infinite canvas stays one canvas), with finger scroll and fling (`DocumentMotion`) and the editor's
  floating page navigator. The page mostly on screen is the pane's page, so page links, Undo and the
  saved position follow it; writing on a page makes it the pane's page.
  A finger tap, or a pen holding a tool only the editor can use (text, lasso, sticky notes, mark
  area…), makes it the editor; the documents stay put and only the toolbar's contents change. Page
  link, swap sides and saved workspaces are in the notebook menu and the command palette.
- **Divider:** drag to resize (it settles on 30/50/70%). Drag past the limit towards an edge and the pane
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
   lasso and touch the PDF with the pen: the toolbar moves over and no stroke is drawn.
7. Drag tabs to reorder them, restart the app, and check the order. Drag a tab down onto the page: it
   opens beside the editor.
2. Pick **This notebook**: both panes show the same notebook. Write on each page in turn and check that
   both thumbnails update and undo stays per page.
3. Drag the divider to each edge: *Release to close* appears, and the remaining document keeps its page
   and zoom.
4. Rest a palm on the other pane while writing: the panes don't switch.
5. Follow a link in a PDF pane with a finger: it turns the page and the pane doesn't take focus.
6. Open a third notebook from the tab strip and come back: the split returns. Rotate the device and
   restart the app with a split open.
