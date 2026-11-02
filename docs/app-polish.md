# App polish change list

111 UI refinements. Each numbered item is a changed control, section or piece of UI copy; imports and formatting are excluded.

Verification: signed experimental build via `./build.sh -p`. Device and TalkBack checks remain manual.

1. `settings/SettingsPages.kt` — Clarify copy: Scroll past the last page, then release to add a blank page. Turn off to add pages only with Add page.
2. `settings/SettingsPages.kt` — Clarify copy: Write faster for thinner ink and slower for fuller strokes. Works with or without pressure.
3. `settings/SettingsPages.kt` — Clarify copy: Taper the start and end of pen strokes. Dots and short ticks keep their original shape.
4. `settings/SettingsPages.kt` — Clarify copy: Tilt the stylus for a broader highlight. The stroke width is set when the tip touches down.
5. `settings/SettingsPages.kt` — Clarify copy: Reduce stored points after each pen stroke while preserving its shape, so notebooks use less space.
6. `settings/SettingsPages.kt` — Clarify copy: Starting tool
7. `settings/SettingsPages.kt` — Clarify copy: Choose the tool used when opening a notebook. Set each tool’s colour, width and opacity in the editor.
8. `settings/SettingsPages.kt` — Clarify copy: Choose the size and alignment for new text boxes. Existing text boxes keep their settings.
9. `settings/SettingsPages.kt` — Clarify copy: Choose the text size for new sticky notes. To resize text on an existing note, use its menu.
10. `settings/SettingsPages.kt` — Clarify copy: Move further with finger drags, two-finger drags and flings.
11. `settings/SettingsPages.kt` — Clarify copy: Choose how far a drag moves the page. 1× follows your finger.
12. `settings/SettingsPages.kt` — Clarify copy: Tap the canvas with two fingers to undo or three to redo. Pen and palm touches do not trigger these gestures.
13. `settings/SettingsPages.kt` — Clarify copy: Adjust the eraser size with pen pressure (about ±12%).
14. `settings/SettingsPages.kt` — Clarify copy: Return after erasing
15. `settings/SettingsPages.kt` — Clarify copy: Erase whole strokes
16. `settings/SettingsPages.kt` — Clarify copy: Touch any part of a stroke to erase the entire stroke.
17. `settings/SettingsPages.kt` — Clarify copy: Sorting, filters and layout also update when you change them in the Library.
18. `settings/SettingsPages.kt` — Clarify copy: Look for a newer signed version from the selected update source when Folio opens.
19. `settings/SettingsPages.kt` — Clarify copy: Show widths and colours below the toolbar when a notebook opens.
20. `settings/SettingsPages.kt` — Clarify copy: Show open documents above the toolbar. Turn off to show a back button in the toolbar instead.
21. `settings/SettingsPages.kt` — Clarify copy: Preview the current ink colour on the pen and highlighter buttons.
22. `settings/SettingsPages.kt` — Clarify copy: Show pinned presets beside the tools. Hiding them keeps your pins saved.
23. `settings/SettingsPages.kt` — Clarify copy: Show exam and Focal timers beside the tools when space allows.
24. `settings/SettingsPages.kt` — Clarify copy: Snap lines to 15° angles and the grid on Maths, Grid and Graph paper. You can also change this in the editor.
25. `settings/SettingsPages.kt` — Clarify copy: Smooth small hand movements while keeping up with fast writing. Applies to new pen strokes.
26. `settings/SettingsPages.kt` — Clarify copy: Set the closest zoom for pages, infinite canvases and reference views.
27. `settings/UxSettings.kt` — Clarify copy: Make editor buttons and selection actions easier to tap.
28. `settings/UxSettings.kt` — Clarify copy: Label the active tool and pinned presets in the editor.
29. `settings/UxSettings.kt` — Clarify copy: Show shortcut tips in the editor and Library. Dismiss a tip when you no longer need it.
30. `settings/UxSettings.kt` — Clarify copy: Use letter keys to switch tools while the canvas has focus. Customise the keys above.
31. `settings/UxSettings.kt` — Clarify copy: Align moved selections to 12-unit spacing and page margins. Hold Shift to move freely.
32. `settings/UxSettings.kt` — Clarify copy: Reopen each notebook with its last tool and preset. Turn off to use the app defaults.
33. `settings/UxSettings.kt` — Clarify copy: Double-tap your pencil, or double-tap this practice area, to try the shortcut.
34. `settings/UxSettings.kt` — Clarify copy: Practice: open the colour palette
35. `settings/UxSettings.kt` — Clarify copy: Practice: undo the last action
36. `settings/UxSettings.kt` — Clarify copy: No pencil shortcut selected
37. `editor/selection/RestyleSelectionPanel.kt` — Clarify copy: Turn off to keep the original opacity of each stroke.
38. `library/LibraryMovePanel.kt` — Clarify copy: No folders match your search. Try a different name.
39. `progress/ProgressLists.kt` — Clarify copy: No papers match your search. Try a subject, paper or year.
40. `study/FocalStudyPanel.kt` — Clarify copy: No plans match. Clear the filters to see more plans.
41. `study/FocalStudyPanel.kt` — Clarify copy: Complete a study session or log past study to see it here.
42. `music/MusicPanels.kt` — Clarify copy: Import a sheet music PDF in Music, then add it to this set list.
43. `editor/dialogs/EditorPaperDialog.kt` — Clarify copy: Fine grid with a bold line every five squares
44. `editor/dialogs/EditorPaperDialog.kt` — Clarify copy: Fine grid with centred axes
45. `editor/dialogs/EditorPaperDialog.kt` — Clarify copy: 25 multiple-choice questions with options A–E
46. `editor/toolbar/ToolOptions.kt` — Name every shared tool-option switch
47. `editor/toolbar/ToolOptions.kt` — Name graph control: Square diagram
48. `editor/toolbar/ToolOptions.kt` — Name graph control: Square grid cells
49. `editor/toolbar/ToolOptions.kt` — Name graph control: Use pi units on the x axis
50. `editor/toolbar/ToolOptions.kt` — Name graph control: Use different units on the y axis
51. `editor/toolbar/ToolOptions.kt` — Name graph control: Grid lines
52. `editor/toolbar/ToolOptions.kt` — Name graph control: Tick marks
53. `editor/toolbar/ToolOptions.kt` — Name graph control: Numbers on the axes
54. `editor/toolbar/ToolOptions.kt` — Name graph control: x and y labels
55. `editor/toolbar/ToolOptions.kt` — Name graph control: Label the origin O
56. `editor/toolbar/ToolOptions.kt` — Name graph control: Arrowheads
57. `editor/dialogs/EditorPaperDialog.kt` — Name paper-default switch
58. `editor/selection/RestyleSelectionPanel.kt` — Name selection-opacity switch
59. `editor/selection/RestyleSelectionPanel.kt` — Name selection thickness slider
60. `editor/selection/RestyleSelectionPanel.kt` — Name selection opacity slider
61. `editor/selection/EditorDocumentSelection.kt` — Name document selection size slider
62. `editor/EditorSearchPanels.kt` — Name handwriting search checkbox
63. `study/StudyTimeline.kt` — Name study completion checkbox
64. `music/MusicReader.kt` — Name score dimming slider
65. `music/MusicReader.kt` — Name tempo slider
66. `music/MusicReader.kt` — Name score page slider
67. `editor/dialogs/BatchPagesPanel.kt` — Name each batch page checkbox
68. `planner/PlannerScreen.kt` — Mark Upcoming as an accessibility heading
69. `planner/PlannerScreen.kt` — Mark Subjects as an accessibility heading
70. `planner/PlannerScreen.kt` — Mark All SACs as an accessibility heading
71. `planner/PlannerScreen.kt` — Mark Archived as an accessibility heading
72. `planner/PlannerScreen.kt` — Mark Study score as an accessibility heading
73. `planner/PlannerScreen.kt` — Mark ATAR estimate as an accessibility heading
74. `planner/PlannerScreen.kt` — Mark Saved estimates as an accessibility heading
75. `progress/ProgressLists.kt` — Mark Import Focal progression as an accessibility heading
76. `progress/ProgressScreen.kt` — Mark Your progress starts here as an accessibility heading
77. `study/StudyStopwatch.kt` — Mark Planned study as an accessibility heading
78. `study/FocalStudyPanel.kt` — Mark Log past study as an accessibility heading
79. `study/FocalStudyPanel.kt` — Mark Wrap up as an accessibility heading
80. `study/FocalStudyPanel.kt` — Mark Active across your apps as an accessibility heading
81. `editor/selection/RestyleSelectionPanel.kt` — Mark Colour as an accessibility heading
82. `editor/selection/RestyleSelectionPanel.kt` — Mark Line style as an accessibility heading
83. `editor/selection/RestyleSelectionPanel.kt` — Mark Brush as an accessibility heading
84. `music/MusicImportPanel.kt` — Name imported PDF preview slider
85. `music/MusicImportPanel.kt` — Name instrument inclusion checkbox
86. `library/files/ExplorerParts.kt` — Keep tablet file search labelled after typing
87. `music/MusicPanels.kt` — Keep set-list search labelled after typing
88. `music/MusicReader.kt` — Keep rehearsal mark field labelled after typing
89. `music/MusicPanels.kt` — Sentence capitalisation for rehearsal notes
90. `editor/EditorUxPanels.kt` — Sentence capitalisation for selection names
91. `editor/dialogs/BatchPagesPanel.kt` — Numeric keyboard for batch page position
92. `planner/PlannerScreen.kt` — Disable prose autocorrection for checklist JSON
93. `planner/CalendarTab.kt` — Disable prose autocorrection for timetable JSON
94. `settings/UxSettings.kt` — Disable autocorrection for shortcut letters
95. `settings/SettingsWritingFollow.kt` — Clarify copy: Automatically move the view as you write on notebook and PDF pages.
96. `settings/SettingsWritingFollow.kt` — Clarify copy: Leave extra room on the side where your writing hand rests.
97. `settings/SettingsWritingFollow.kt` — Clarify copy: Custom · adjust the controls below to change the feel.
98. `settings/SettingsWritingFollow.kt` — Clarify copy: Set the glide speed for sideways movement and line returns. Android’s reduced-motion setting skips the glide.
99. `settings/SettingsWritingFollow.kt` — Clarify copy: Set the line’s height after the view moves. 55% places it just below the centre.
100. `settings/SettingsWritingFollow.kt` — Clarify copy: Position the end of your writing after a sideways move. Next line returns to the answer margin.
101. `settings/SettingsWritingFollow.kt` — Clarify copy: Make the movement zone easier to see with a stronger tint. Hidden zones still trigger movement; ink stays visible through the tint.
102. `settings/SettingsWritingFollow.kt` — Clarify copy: Choose Text mode for automatic returns. In Maths mode, tap Next line.
103. `settings/SettingsWritingFollow.kt` — Clarify copy: Preview the next line with a dot and dashed guide before the view returns.
104. `settings/SettingsWritingFollow.kt` — Clarify copy: Match your handwriting’s line spacing after two natural line breaks.
105. `settings/SettingsWritingFollow.kt` — Clarify copy: Outline the active writing area so you can see its boundaries.
106. `settings/SettingsWritingFollow.kt` — Clarify copy: Show a whole-page overview in peek without pinning a view. Available on finite pages.
107. `editor/dialogs/BatchPagesPanel.kt` — Clarify copy: Clear selection
108. `editor/dialogs/BatchPagesPanel.kt` — Clarify copy: Destination page position
109. `settings/SettingsScreen.kt` — Match Starting tool search results to the renamed section; keep the old words searchable.
110. `settings/SettingsScreen.kt` — Match Return after erasing search results to the renamed control; keep single stroke searchable.
111. `settings/SettingsScreen.kt` — Match Erase whole strokes search results to the renamed control; retain eraser keywords.
