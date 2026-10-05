# Writing and navigation polish

This pass builds on [the earlier 25 workflow changes](ux-simplification.md). It focuses on writing space, reliable search, safe drafts, useful selection actions, and compact layouts.

## The 39 changes

1. Focus mode keeps the drawing strip and local save status while folding document controls away.
2. Numeric page navigation is folded away until requested.
3. Page duplication is pinned beside add and insert.
4. Empty page searches offer Show all pages.
5. The page browser switches between a draggable list and a thumbnail grid.
6. Phone bulk selection uses the contextual action bar without the destination navigation bar.
7. Larger thumbnails preserve page proportions.
8. Selection duplication is a primary action when enough room is available.
9. Picture actions adapt to narrow panes; secondary actions remain accessible.
10. Paste is disabled when the system clipboard is empty.
11. Text draft fields and formatting use saved UI state.
12. Text duplication copies the latest draft, leaving the original unchanged.
13. Closing a changed text draft requires a discard decision.
14. The original text box and open editor restore with the draft after configuration changes.
15. Text formatting is optional disclosure below the writing field.
16. Text supports multiline typing and Ctrl+Enter to apply.
17. Notebook text search reads unopened pages and reports failed reads.
18. Search excerpts come from the strongest matching text box.
19. Notebook search queries survive reopening the search panel.
20. Typed-search result lists fit above the software keyboard.
21. Typed search receives focus immediately and submits from the IME.
22. Editor and reference PDF search share one UI with previous/next match navigation.
23. PDF query drafts survive configuration changes and old results are distinguished from new input.
24. PDF search results fit the actual panel viewport.
25. PDF and typed-text search offer direct switching in imported notebooks.
26. Selection moves have a searchable destination list with page previews.
27. Ctrl+F finds, Ctrl+G opens numeric page navigation, Ctrl+0 resets zoom; tapping the active text tool opens its options.
28. The document menu includes a keyboard shortcut reference.
29. PDF match navigation is available only for the submitted query.
30. Page deletion explains session Undo recovery.
31. Notebook moves use a shared searchable folder chooser with Create & move for new folders.
32. Hidden or locked active layers explain blocked drawing and expose Layers even in focus mode.
33. Back/Escape cancels a crop or clears picture/ink selection before notebook navigation.
34. Formatting chips wrap, and text sliders have spoken names and values.
35. Deeply nested PDF outline titles retain readable space in narrow panes.
36. Workspace searches match multiple terms across document fields; pending companion mode survives rotation.
37. A pinned tool preset is active only when tool, colour, width, opacity and line style all match.
38. Search does not retain decoded strokes or images from unopened pages.
39. PDF contents and keyboard help fit short panel viewports.

Notebook formats and binary/journal versions are unchanged. Search uses temporary text-only copies and never saves those copies. Creating a move destination uses the existing serialized library writer. Text duplication remains one ordinary page edit.

## Device checks still required

The build verifies compilation, packaging and signing; it does not verify rendered layouts or touch interactions. Check these scenarios on phone/tablet, portrait/landscape, narrow companion panes and large system text:

- Toggle focus mode while writing; verify writing follow, page scrolling and selection menus avoid the measured dock.
- Browse a long notebook in list/grid modes, filter to bookmarks/practice pages, clear an empty search and reopen at the current page.
- Make a lasso selection and duplicate/move it. Check picture controls at narrow widths; Back/Escape must cancel crop or clear selection, then allow notebook navigation.
- Create/edit a multiline text box, change formatting, rotate, cancel/discard/keep editing, duplicate the draft, and apply using Ctrl+Enter. Check Undo returns the page to its prior contents.
- Cold-open a notebook with typed text on distant pages, search without visiting those pages, and confirm the strongest matching box supplies the excerpt. A failed read must show incomplete-results feedback.
- Open PDF search from editor and reference panes, edit a submitted query, navigate matches, switch to typed notes and check deeply nested contents.
- Select notebooks, search for a move folder, create a new folder and move them into it; return to the shelf and restart to verify the folder and notebook locations.
- Activate a hidden or locked layer and verify the warning opens Layers in normal and focus modes.
- Apply a pinned preset, change only opacity or line style, and confirm its active indicator clears.
