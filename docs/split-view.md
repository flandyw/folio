# Split view

Two documents side by side (stacked vertically below 600 dp), designed around one button, an in-pane chooser, and zero mode switching.

## User experience

- **Opening a split:** Tap the split button in the toolbar, choose **Split view…** in the notebook menu, press `Ctrl + \`, use the command palette, or drag a tab down out of the tab strip. Dragging lifts a chip under your finger and highlights drop halves (`SplitDropZone`); releasing opens the document on that side, while dragging back up returns to tab reordering. Opening via button splits immediately and shows the in-pane chooser: search, **This notebook** (opens at the active page), **Open tabs**, and **Recent** notebooks.
- **Tabs:** Tap to switch; press and drag sideways to reorder (open tabs and order persist in `workspaceTabs`). Neighbours spring smoothly into place, and new/closed tabs animate in and out.
- **Shared toolbar:** One toolbar spans both panes via `LocalToolbarPortal`. Neither pane dims or wears an "inactive" border, and the active tool travels with the toolbar whenever focus switches (`swapCompanion`).
- **Grouped split tabs:** Both split documents appear side by side inside one tinted capsule led by the split icon (`SplitTabGroup`). Closing the capsule ends the split while keeping both tabs open; individual background tabs only show a close button once selected, preventing accidental closes.
- **Drop side:** Dropping a dragged tab opens it on that half (left/right, or top/bottom when stacked; `SplitPanes.dropsAtStart`), with accessibility actions *Open on the left* / *Open on the right*.
- **Writing in both panes:** With pen, highlighter, eraser, shape, laser, or hand selected, the stylus writes directly in the companion pane (`LocalSharedInk`). Toolbar Undo/Redo targets whichever pane has the newest edit.
- **Seamless focus swap:** The companion pane runs on the editor's own `ContinuousDocumentHost` with its own `EditorSession` (identical zoom, pan, fling, fast-scroll, eraser ring, and sticky notes). Fingers navigate and follow links in either pane without moving focus. Only a stylus holding an editor-only tool (text, lasso, sticky notes, mark area) moves toolbar focus to the companion pane; both documents stay anchored at their exact page-relative position and zoom.
- **Divider:** A 6 dp bar with a centred pill (28 dp grab zone) that snaps to 30/50/70%. Dragging past an edge shows *Release to close*; releasing closes that pane (closing the editor leaves the companion as the editor). Double-tap for an even 50/50 split.
- **Parked splits:** Each split belongs to its host notebook (`companionHostId`). Opening a third notebook parks the split and restores it when you return.

## Code

- `WorkspaceScreen.kt`: `SplitLayout` keeps the editor as its first child so opening, closing, or swapping never remounts it. `CompanionPane`, `focusOnTouch`, and `LocalSplitView` wire focus and split actions.
- `SplitChooser.kt` / `WorkspacePicker.kt`: In-pane chooser over pure `SplitChooser.choices`.
- `EditorWorkspace.kt`: Pure `SplitPanes.dragged` / `release` (drag bounds, snap points, close thresholds).
- `WorkspaceManager.kt`: `showCompanion`, `swapCompanion` (flips side and share so the divider stays put), `closeEditorPane`, and parked splits.
- `SplitViewRulesTest`: Covers release rules and chooser ordering. Legacy `CompanionMode` and `PickerPurpose.COMPANION` are removed; saved workspaces ignore obsolete `mode` fields.

## Device checks

1. Open a split with a PDF; write in both panes with the pen and verify toolbar stays put and Undo targets the latest stroke. Switch to lasso and touch the PDF with the pen: focus moves without drawing. Tap the PDF with a finger: focus stays put.
2. Pick **This notebook**: write on different pages in each pane and confirm thumbnails update and undo stays per page.
3. Drag the divider to each edge (*Release to close*) and confirm the remaining document keeps its page and zoom.
4. Rest a palm on the companion pane while writing: panes must not swap.
5. Follow a PDF link with a finger: the page turns without moving focus.
6. Switch to a third notebook and return: the split restores. Rotate and restart with a split open.
7. Reorder tabs, restart, and confirm order. Drag a tab down onto either half (including split tabs), cancel a drag back into the strip, rapidly open/close tabs, and test scrolled strips and RTL.
8. Scroll and pinch-zoom both panes mid-page, swap focus back and forth, and confirm exact position and zoom hold.
9. In the companion pane, test pinch zoom, sideways pan, fling, fast-scroll thumb, and sticky notes on and off the paper.
