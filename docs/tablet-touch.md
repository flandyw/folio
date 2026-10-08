# Tablet controls and a compact editor

The editor prioritises space for handwriting. Drawing buttons and width presets are
40 dp; the main floating surfaces are 46 dp high. On panes at least 600 dp wide,
navigation, drawing tools and document actions share a single row. Smaller panes put
tools on a separate row so history and overflow stay accessible. Most tablets keep
secondary navigation in its popover; individual actions appear from 1100 dp.
The writing-follow/peek dock uses 40 dp controls in a 44 dp surface.

Quick colours and widths start collapsed and can be expanded with one tap. Opening
that row does not move the document. Its settings control stays reachable while the
colours scroll. Below 360 dp of tool-tray width, Redo moves into the tool menu.
Saved toolbar order and pinned presets still apply.

Notebook, folder, sort, creation and mistake-filter actions use anchored, scrollable
popovers. Tool palettes stay in popovers in narrow windows too, with width clamped to
the Android window. Tapping outside or pressing Back dismisses them; the surrounding
page stays visible. Existing pen/palm protection remains on their content.

The Library keeps its navigation rail from 840 dp and folder sidebar from 1200 dp.
Smaller windows show places/tags as chips and folders as shelf tiles. Tablet covers
have a 176 dp minimum width. Library navigation and the split drag area retain larger
finger targets. Notebook deletion still requires its existing confirmation.

## Device walkthrough

The release build checks compilation and APK metadata/signature. Layout and input
still need a device check; no Android device was connected during this change.

- In portrait and landscape, including 600–839, 840–1199 and 1200+ dp, browse nested
  folders, tags and Tablet files. Verify folders remain reachable without the sidebar.
- Open creation, notebook, folder, sort and mistake-filter popovers. Confirm they
  stay beside their triggers, scroll to every action and dismiss with outside taps
  and Back. Check Move and the Delete confirmation.
- In editor panes around 600 dp, check the single-row/two-row transition. Expand and
  collapse quick controls, open active-tool settings, and check Undo/Redo and saved
  toolbar layouts. Verify the canvas does not shift when quick controls expand.
- Check the music reader, narrow split panes, large system text and the software
  keyboard. Resize workspace/mistake panes with a finger; use stylus controls while
  resting a palm near the dock and confirm no accidental actions.
