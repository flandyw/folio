# Split view

Two documents side by side (stacked below 600 dp), modelled on Goodnotes and Notability: one button to open
it, a chooser that appears in the new pane, and no modes to pick.

## How it works for the user

- **Open:** the split button in the editor toolbar (both toolbar styles), *Split view…* in the notebook
  menu, Ctrl + \ or the command palette, or long-press a tab in the tab strip. The screen splits at once
  and the new pane shows a chooser: search, **This notebook** (opens on the page being edited, so two pages
  of one notebook are one tap apart), **Open tabs**, then **Recent** notebooks.
- **Work in either pane:** the editor (with its toolbar) is one pane. A pen touch on the other pane, or a
  deliberate finger tap there, or its **Edit** button makes it the editor. The documents stay where they
  are and only the toolbar moves. The touch that switches panes is consumed, so a stroke never lands in
  the wrong document. A finger still scrolls, zooms and follows PDF links in the other pane. Finger taps
  are ignored while a pen is active (`StylusActivity`), so a resting palm can't switch panes.
- **The other pane's bar:** tap the title to show another document there, link pages (the pane follows
  the editor's page turns, with an adjustable offset), and a menu to swap sides, split evenly, open saved
  workspaces or close the split. The bottom bar keeps page navigation, zoom, contents, search and previews.
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

1. Open a notebook, tap the split button, and pick a PDF. Write in the editor, then put the pen on the PDF:
   the toolbar moves over, no stroke lands on the PDF, and the next stroke writes there.
2. Pick **This notebook**: both panes show the same notebook. Write on each page in turn and check that
   both thumbnails update and undo stays per page.
3. Drag the divider to each edge: *Release to close* appears, and the remaining document keeps its page
   and zoom.
4. Rest a palm on the other pane while writing: the panes don't switch.
5. Follow a link in a PDF pane with a finger: it turns the page and the pane doesn't take focus.
6. Open a third notebook from the tab strip and come back: the split returns. Rotate the device and
   restart the app with a split open.
