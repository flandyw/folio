# 50 expected-behaviour improvements

Scope: this pass leaves every file touched by the latest ten commits unchanged
(`1357902` through `003a3d2`, inclusive, at the start of the task).
The work is in other exam, music, split-chooser, backup-exclusion and tablet-file
flows. It introduces no dependencies or storage-format changes.

## Recording marks — `exam/ScoreDialog.kt`

1. Turning off **Timed sitting** omits the hidden duration from the recorded mark.
2. An unchanged timer measurement keeps its exact seconds instead of truncating to whole minutes.
3. Supplied durations must be positive and within the entry range; unknown duration remains optional.
4. A zero or invalid total explains the problem beside **Out of**.
5. The total's keyboard action moves to duration when timed; it submits only when appropriate.
6. An invalid score above the total no longer shows a misleading percentage preview.
7. Record actions wrap on narrow panels instead of sharing one overflowing row.

## Exam details — `exam/ExamDetailsPanel.kt`

8. Incomplete years are explained and blocked instead of being saved as tiny year numbers.
9. Invalid units are explained and blocked instead of silently clamping them to 1 or 4.
10. Optional marks must be positive; Save and keyboard Done share validation.
11. **Record a mark** waits for an invalid marks total to be corrected.
12. The selected exam date is displayed as the DatePicker's UTC calendar date, avoiding a previous-day label west of UTC.
13. Recorded marks show newest first.
14. Deleting a recorded mark asks for confirmation and explains that pages are kept.
15. Back and Close protect unsaved details; **Keep editing** leaves the panel visible.

## Batch exam details — `exam/BatchExamTagsPanel.kt`

16. An opted-in invalid year cannot be applied across notebooks; blank still intentionally clears it.
17. Year suggestions use this year and the preceding two, replacing fixed 2022/2021 suggestions.
18. Tapping the selected year suggestion clears it, matching the single-notebook editor.
19. Tapping the selected company suggestion clears it, matching the single-notebook editor.
20. The whole section header toggles its checkbox, including its checkbox accessibility role.

## Exam timer — `exam/TimingPanel.kt`

21. Blank, zero and out-of-range custom times are explained and cannot start a substituted/clamped duration, via either button or keyboard.
22. Stopping a live timer asks for confirmation and explains that elapsed writing time is kept.
23. Timer/stopwatch tabs scroll horizontally when their status labels do not fit.

## Music import — `music/MusicImportPanel.kt`

24. Busy import disables filmstrip edits and part switching through **Show**, keeping the submitted selection stable.
25. A newly added custom part becomes the filmstrip's active part.
26. Checking a part makes it the filmstrip's active part.
27. Editing a part's range makes that part active, so subsequent filmstrip taps change the expected range.
28. Merging selected parts keeps unticked drafts instead of discarding them; the merged part becomes active and suggestion notices follow their actual part.
29. A selected part with an empty title explains why it cannot be added.
30. A selected part with no pages explains why it cannot be added; filmstrip taps cannot silently replace malformed typed ranges.

## Music pickers — `music/MusicPanels.kt`

31. Score-detail drafts are keyed by score identity, preventing one score's fields from carrying into another.
32. A blank score title explains the disabled Save action.
33. **Add to set** uses the shelf's search, including words across composer, title, part, notes and rehearsal marks.
34. Set-score search has a clear action.
35. Empty score lists explain whether to import scores or change the search.
36. Set-membership toggles use current stored membership, preventing rapid taps from replaying a stale checkbox state in either picker.
37. Choosing the current folder closes the picker without unnecessarily writing the music index, including **No folder**.

## Split chooser — `workspace/WorkspacePicker.kt`, `workspace/SplitChooser.kt`

38. Split search uses the Library's metadata and page-name search, retaining hidden-document exclusions.
39. Back clears a chooser search before leaving the flow.
40. Search on the keyboard dismisses the keyboard so the results can be browsed.

## Backup exclusions — `backup/BackupExclusionsPanel.kt`

41. Search uses the Library's all-word search across notebook metadata and page names.
42. **Exclude shown** / **Include shown** act on the displayed results only and disable when there is nothing to change.

## Tablet files — `library/files/DeviceExplorer.kt`

43. File search matches every word in any order, including whitespace pasted from other apps.
44. Every route back to Locations clears the now-hidden folder query.
45. Successful file operations use the short notice channel instead of reporting success as an error.
46. Tapping a ZIP offers the same import-copy review as its existing **Import to Folio** menu action, retaining **Open with**.
47. All import submissions refuse to run while a library/file operation or load failure prevents importing.
48. Select mode can import a single Folio/ZIP archive as well as batches of PDFs, with an appropriate action label.
49. Import-copy review offers an explicit Cancel and stays open on outside taps.
50. New-folder and rename dialogs use storage's name validation before closing, so dot names, null characters and slashes do not throw away the entered draft.

## Verification

Automated coverage is in `ExamEntryRulesTest`, `FileBrowseRulesTest`,
`SplitChooserSearchTest` and `MusicChooserRulesTest`. These exercise numeric
boundaries, exact measured seconds, untimed durations, file-name validation,
cross-field searches, hidden-document exclusion and merged-page union.

Results on 31 October 2026:

- `./gradlew :app:testDebugUnitTest --console=plain`: 184 tests passed, including 15 new tests; no failures or skips.
- `./build.sh -p`: signed release build, APK signature/package/version checks and publication passed. Published [5.1.9-exp.1](https://folio.flandolf.me/releases/v5.1.9-exp.1/), version code 5190001.
- `git diff --check`: passed.
- Compared changed paths against `git diff HEAD~10 HEAD --name-only`: no protected file changed.

## Tablet checks still required

- Enter timed and untimed marks with measured seconds, unknown duration, zero duration and invalid totals. Exercise keyboard Next/Done and narrow panels with both record actions.
- Edit exam details, press Back/Close, keep editing, then save. Delete/keep a mark. Verify the date label in a time zone west of UTC.
- Batch-toggle section titles, toggle year/company chips and attempt to apply a partial year.
- Start valid/invalid custom timers, cancel/confirm Stop, and inspect timer tabs in a narrow split.
- Select three music parts, untick one, merge the other two, then reselect the retained draft. Add a custom part and build its range from the filmstrip. Check import-busy guards and malformed range recovery.
- Rapidly toggle set membership. Switch score details. Choose the current music folder. Exercise empty and multiword searches.
- Search the split chooser by page name and subject, press keyboard Search and then Back. Search backup exclusions and bulk-toggle only the shown results.
- Test local and document-provider files: multiword search, Locations navigation, PDF/Folio/ZIP import, mixed selections, busy import, Cancel/outside tap and invalid folder/file names.
