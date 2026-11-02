# UI path polish — second pass

107 user-facing refinements. Imports, helper extraction and tests are not counted as separate changes.

Focus: direct routes to settings, safe form dismissal, page and export actions, recoverable pickers and narrow-pane layouts.

Verification: 10 focused JVM tests passed (`SettingsSearchTest`, `EditorCommandSearchTest`, `ExportPageRangeTest`). `git diff --check` passed. The signed experimental build (`./build.sh -p`) passed and published version 5.2.1-exp.1, code 5210001. Device and TalkBack checks remain manual.

1. `settings/SettingsSearch.kt` — Make Toolbar style search reveal its actual control
2. `settings/SettingsSearch.kt` — Make Live shape measurements search reveal its actual control
3. `settings/SettingsSearch.kt` — Make Pressure-sensitive eraser search reveal its actual control
4. `settings/SettingsSearch.kt` — Make Resume timer on pen down search reveal its actual control
5. `settings/SettingsSearch.kt` — Make Stop timer after inactivity search reveal its actual control
6. `settings/SettingsSearch.kt` — Make Quick note folder search reveal its actual control
7. `settings/SettingsScreen.kt` — Add direct settings path: Pen smoothing
8. `settings/SettingsScreen.kt` — Add direct settings path: Pull past the end to add a page
9. `settings/SettingsScreen.kt` — Add direct settings path: Thinner when fast
10. `settings/SettingsScreen.kt` — Add direct settings path: Soft stroke ends
11. `settings/SettingsScreen.kt` — Add direct settings path: Tilt widens the highlighter
12. `settings/SettingsScreen.kt` — Add direct settings path: Low-latency ink
13. `settings/SettingsScreen.kt` — Add direct settings path: Keep notebooks small
14. `settings/SettingsScreen.kt` — Add direct settings path: Sticky notes
15. `settings/SettingsScreen.kt` — Add direct settings path: Colour dots
16. `settings/SettingsScreen.kt` — Add direct settings path: Pinned presets
17. `settings/SettingsScreen.kt` — Add direct settings path: Timer on the toolbar
18. `settings/SettingsScreen.kt` — Add direct settings path: Start with ink options open
19. `settings/SettingsScreen.kt` — Add direct settings path: Document tabs
20. `settings/SettingsScreen.kt` — Add direct settings path: Fast pan
21. `settings/SettingsScreen.kt` — Add direct settings path: Pan speed
22. `settings/SettingsScreen.kt` — Add direct settings path: Pause before moving
23. `settings/SettingsScreen.kt` — Add direct settings path: Movement speed
24. `settings/SettingsScreen.kt` — Add direct settings path: Writing height
25. `settings/SettingsScreen.kt` — Add direct settings path: Position after a sideways move
26. `settings/SettingsScreen.kt` — Add direct settings path: Line-end zone
27. `settings/SettingsScreen.kt` — Add direct settings path: Show where the next line lands
28. `settings/SettingsScreen.kt` — Add direct settings path: Learn my line spacing
29. `settings/SettingsScreen.kt` — Add direct settings path: Start following at this zoom
30. `settings/SettingsScreen.kt` — Add direct settings path: Detect answer areas automatically
31. `settings/SettingsScreen.kt` — Add direct settings path: Show answer area outlines
32. `settings/SettingsScreen.kt` — Add direct settings path: Switch to the area I write in
33. `settings/SettingsScreen.kt` — Add direct settings path: Save library backup
34. `settings/SettingsScreen.kt` — Add direct settings path: Restore library backup
35. `settings/SettingsScreen.kt` — Add direct settings path: Back up now
36. `settings/SettingsScreen.kt` — Add direct settings path: Custom timer, writing minutes
37. `settings/SettingsScreen.kt` — Add direct settings path: Custom timer, reading time
38. `settings/SettingsScreen.kt` — Add direct settings path: Respond by
39. `settings/SettingsScreen.kt` — Add direct settings path: Starting zoom
40. `settings/SettingsScreen.kt` — Add direct settings path: Check on launch
41. `settings/SettingsScreen.kt` — Start each settings category at its own top position
42. `settings/SettingsScreen.kt` — Dismiss search keyboard when opening a settings result
43. `settings/SettingsSearch.kt` — Reveal the parent group for controls conditional on device or preference
44. `progress/ExamDialogs.kt` — Keep exam form visible when discard is cancelled or save is busy
45. `progress/ExamDialogs.kt` — Keep mistake form visible when discard is cancelled or save is busy
46. `progress/NotebookResultsDialog.kt` — Keep notebook results visible after choosing to keep editing
47. `progress/ProgressLists.kt` — Keep exam progression visible after refusing discard
48. `planner/PlannerScreen.kt` — Prevent planner form closing animation while saving
49. `progress/ProgressInsights.kt` — Keep provider form visible while its save is busy
50. `editor/EditorUxPanels.kt` — Keep alignment panel open until its transaction finishes
51. `editor/EditorUxPanels.kt` — Keep snippet panel open during save and paste
52. `ui/FolioPanel.kt` — Announce the active dialog title when opening a panel
53. `ui/FolioPopover.kt` — Announce contextual popovers as named panes
54. `ui/FolioPopover.kt` — Make contextual popover titles navigable headings
55. `library/LibraryMovePanel.kt` — Keep long move-destination names readable without crowding actions
56. `library/LibraryMovePanel.kt` — Use one tap target per folder instead of duplicate move actions
57. `library/LibraryMovePanel.kt` — Give folder-only moves a valid empty-state destination
58. `library/LibraryMovePanel.kt` — Cancel only folder creation and clear its abandoned draft
59. `library/LibraryMovePanel.kt` — Explain where Create & move creates its folder
60. `editor/selection/MoveSelectionPanel.kt` — Replace the single-page move dead end with an actionable explanation
61. `workspace/SplitChooser.kt` — Cancel split chooser with Back instead of leaving the notebook
62. `workspace/SplitChooser.kt` — Keep split document search labelled after text entry
63. `workspace/SplitChooser.kt` — Expose split chooser groups as navigable headings
64. `workspace/SplitChooser.kt` — Provide visible recovery and cancel actions in an empty split chooser
65. `export/ExportPagesDialog.kt` — Clear stale export selection when the typed range is empty or invalid
66. `export/ExportPagesDialog.kt` — Remove redundant export Apply step and show range errors immediately
67. `export/ExportPagesDialog.kt` — Clear the typed range when individually toggling export pages
68. `export/ExportPagesDialog.kt` — Clear the typed range after long-pressing a single export page
69. `editor/page/EditorPageBrowser.kt` — Replace the page browser with batch selection instead of stacking panels
70. `editor/page/EditorPageBrowser.kt` — Open inserted or duplicated grid pages directly
71. `editor/page/EditorPageBrowser.kt` — Open inserted or duplicated list pages directly
72. `editor/page/EditorPageBrowser.kt` — Dismiss the page-jump keyboard before revealing the destination
73. `editor/page/EditorPageBrowser.kt` — Explain a failed page jump beside the field
74. `editor/page/EditorPageBrowser.kt` — Use the theme accent for the page reorder marker
75. `editor/page/EditorPageBrowser.kt` — Name grid bookmark actions by their actual effect
76. `editor/dialogs/BatchPagesPanel.kt` — Use a single full-width checkbox row for each batch page
77. `editor/dialogs/BatchPagesPanel.kt` — Disable Select all when all batch pages are already selected
78. `editor/dialogs/BatchPagesPanel.kt` — Disable Clear selection when no batch pages are selected
79. `editor/dialogs/BatchPagesPanel.kt` — Show selected and total page counts in batch selection
80. `editor/dialogs/BatchPagesPanel.kt` — Make the batch deletion action explicit
81. `editor/dialogs/BatchPagesPanel.kt` — Explain valid batch positions and why deleting every page is unavailable
82. `editor/UxShortcuts.kt` — Find editor commands by shortcut or availability explanation as well as title
83. `editor/UxShortcuts.kt` — Add a one-tap reset to command search
84. `editor/UxShortcuts.kt` — Expose the keyboard-highlighted command as selected
85. `editor/UxShortcuts.kt` — Explain shortcut search when commands have no matches
86. `editor/UxShortcuts.kt` — Add clear and keyboard Search actions to the gesture guide
87. `editor/UxShortcuts.kt` — Add a recovery path when gesture search has no results
88. `editor/UxShortcuts.kt` — Make gesture help topics navigable headings
89. `workspace/SavedWorkspaces.kt` — Capitalise saved workspace names like other document names
90. `workspace/SavedWorkspaces.kt` — Show the actual route for creating a workspace pair
91. `workspace/SavedWorkspaces.kt` — Add an informative empty state to saved workspaces
92. `workspace/SavedWorkspaces.kt` — Keep long workspace names from pushing removal offscreen
93. `workspace/SavedWorkspaces.kt` — Distinguish removing a workspace shortcut from deleting its notebooks
94. `workspace/SavedWorkspaces.kt` — Name each workspace removal target for accessibility
95. `progress/ProgressInsights.kt` — Name the provider-alignment switch
96. `progress/ProgressInsights.kt` — Disable adding empty or duplicate providers instead of silently doing nothing
97. `progress/ProgressInsights.kt` — Prevent reset while provider settings are saving
98. `editor/selection/RestyleSelectionPanel.kt` — Wrap primary selection line styles in narrow panes
99. `editor/selection/RestyleSelectionPanel.kt` — Wrap extended selection line styles in narrow panes
100. `editor/selection/RestyleSelectionPanel.kt` — Wrap selection restyle confirmation actions with large text
101. `music/MusicPanels.kt` — Wrap music details confirmation actions with large text
102. `library/LibraryMovePanel.kt` — Wrap Create & move actions in narrow folder pickers
103. `planner/PlannerScreen.kt` — Wrap shared planner form confirmation actions
104. `export/ExportPagesDialog.kt` — Distinguish clearing selected export pages from clearing a text field
105. `editor/selection/EditorSelectionController.kt` — Show move confirmation only after validating and applying the move
106. `editor/EditorCommands.kt` — Accept compact Ctrl+Z and spaced Ctrl + Z in action search
107. `settings/SettingsSearch.kt` — Reveal timer and backup groups when their search result has no standalone scroll target.

## Device follow-up

No Android device or emulator was attached during this pass. Check these paths on a device:

- Search Settings for a descriptive control name; open the result and check the right row is revealed with the keyboard dismissed.
- Edit an exam, mistake, notebook result or progression; press Back or Close, choose Keep editing, and confirm the form remains visible.
- Enter an invalid export range after a valid one; confirm Save and Share become unavailable. Pick a page tile and confirm the old range clears.
- Open Notebook pages, choose Select pages, then export or move the selection; confirm only the intended panel remains open.
- Open split view, search for a missing document, clear the search, then use Back to cancel the chooser.
- Use large text and a narrow split pane to check line-style choices and form actions. Check panel titles and page checkbox rows with TalkBack.
