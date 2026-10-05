# Shelf, dialog and keyboard polish

Sixty-two small changes that follow [the writing and navigation polish](ux-writing-polish.md). They touch the shelf, its dialogs, Settings search and text-entry fields. The editor chrome, storage format and journal are unchanged.

## The 50 changes

Shelf
1. Entering selection with a long press gives a long-press haptic.
2. Ticking or unticking a notebook gives a light tick.
3. The Favorites chip shows how many notebooks are starred.
4. Folder chips show how many notebooks each folder holds.
5. "Continue writing" shows when the notebook was last edited.
6. Clearing the search box clears the debounced query too, so the shelf resets at once.
7. An empty search names the query that matched nothing.
8. The sort button shows the current sort order as visible text.
9. The result-count badge is announced as "N notebooks".
10. The exam countdown is a chip with an icon on the error container.
11. "Retry save" is styled as an error and carries a refresh icon.
12. Select / Done carries an icon.
13. The selection bar reads "N of M selected" and announces changes.
14-18. Move, Exam details, Cover, Favorite and Delete in the selection bar carry icons.
19. Bulk delete states the total page count.
20. Single delete states the page count.
21. Removing a folder states how many notebooks stay in All notebooks.
22. The delete confirmation reads "Delete notebook".
23. The bulk delete confirmation reads "Delete N".
24. Tapping Library while already on it scrolls the shelf to the top.
25. Scrolling the shelf dismisses the search keyboard.
26. The first-run empty state offers "Import a PDF".
27-28. Bottom-bar and rail destinations no longer announce their name twice.

Move and naming dialogs
29. A whole folder row in Move is tappable, not just its button.
30. The current folder is marked with a check.
31. The new-folder field capitalises words and creates and moves from the keyboard's Done.
32. The folder search uses a Search action.
33. Rename and New folder enable Save only once the name has changed.
34. Names capitalise the first letter.
35. Names have a Clear button.
36. The 120-character counter appears only near the limit.
37. New notebook names capitalise the first letter.
38. The create button says "Create canvas" or "Create · N pages" where that applies.
39. PDF import destinations are real radio rows, tappable across the row.
40. PDF import names capitalise.
41. The PDF import button says "Import N" for several files.

Export and Settings
42. The export page range applies from the keyboard's Done.
43. Save and Share PDF show how many pages they will include.
44. A Settings search with no match offers "Clear search".

Text entry
45. Exam review fields (comments, question, topic, criterion, examiner note, correction, explanation) capitalise sentences.
46. Notebook result titles and papers capitalise words.
47. Progress list, provider and paper names capitalise words.
48. The add-provider field capitalises words.
49. Long-response notebook name and subject capitalise words.
50. Long-response topic and prompt capitalise sentences.

## Second pass

51. Starring a notebook gives a light haptic tick.
52. Grid cards show two lines of a long title.
53. New notebooks have a page stepper (1–40) for blank starts.
54. The custom exam timer starts from the keyboard's Done.
55. The custom timer field states its range and the reading time that precedes it.
56. Settings switches give toggle-on / toggle-off haptics.
57. The Progress list dismisses the search keyboard when it scrolls.
58. Exam, mistake and notebook-result number fields use a Next action.
59. Marks awarded above the total are flagged inline.
60. Marks lost above the total are flagged inline.
61. Export offers Odd pages and Even pages for double-sided printing.
62. Move destinations are sorted alphabetically.

## Device checks

The build verifies compilation only. On a phone and a tablet, check: long-press selection haptics, folder/favorite counts with long names, the selection bar at large text size, Move panel row taps and the Done key, rename Save staying disabled until a change, and export range entry from the keyboard.

## Pull past the end to add a page

Dragging the document past its last page stretches it (`DocumentMotion`). Lifting the finger from a stretch of 72 dp or more adds a blank page, with the same paper and reveal as the Add page button. While pulling, an M3E `LoadingIndicator` fills with the pull and a pill reads "Pull to add page" then "Release to add page"; a haptic marks the threshold, and the indicator spins ("Adding page…") while the page is created. A fling that merely hits the end never commits. Not offered when the last page is an infinite canvas or a peek is open. Settings → Writing & tools → "Pull past the end to add a page" turns it off (`EditorQuickPrefs.PULL_TO_ADD_PAGE`, default on). Device check: pull with a finger and with the hand tool on a short and a long notebook, release early (should snap back) and late (should add one page only).
