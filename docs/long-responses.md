# Long responses and feedback actions

Create a notebook → **Long response** (or **Essay practice**) starts a question with a subject,
text/topic, optional marks and time target, and Plan only / Practise a paragraph / Full response.
An existing notebook can use the notebook menu → **Set up long response**; its current pages become
Original work. The response shelf keeps the prompt visible and offers a collapsible typed plan,
attempt history, feedback actions and a ruled continuation page. Plans save after a short typing
pause and flush when the plan closes or the editor leaves.

## Attempts

Each attempt owns page IDs in the same notebook. Starting fresh appends a blank ruled page;
continuing, inserting or duplicating a page associates it with the attempt being edited.
Reordering pages does not break links. **Copy for marking** copies the attempt's current page
content into independent pages and keeps the original untouched. It does not copy undo stacks or
award another result. Rename attempts to distinguish drafts, teacher-marked work and timed retries.
Open one attempt and use **Compare** on another to show it in the existing read-only companion,
with linked page navigation turned off. The optional timer uses Folio's existing exam timer and
cannot replace an active sitting from this panel.

Marking's tally only counts pages belonging to the current response attempt. A recorded mark links
to that attempt and is shown in its history card; recording again updates its linked result. Whole
notebook marks recorded from the library remain notebook-level results. Prompt marks are optional;
no rubric or automatic marking is inferred.

## Feedback actions

**Marking & feedback → Feedback actions** accepts a new instruction or a typed comment already on
the current page. Choose Rewrite a paragraph, Retry the question or Practise a plan. Actions retain
the source page ID and, when chosen from a comment, its text-box ID and a copy of the comment text.
**Source page** opens the original page. **Practise** creates a fresh linked attempt and displays
its feedback focus while writing; subsequent taps resume that practice. Completion is explicit,
so starting practice does not silently resolve feedback. Removed actions leave comments and
practice pages intact. Deleted source pages are reported and feedback remains readable.

The library's **Feedback actions** opens a subject-filterable list across notebooks, including
ordinary worksheets. Completed actions can be included and reopened. Practising an action from a
notebook without response setup groups its existing pages as Original work and uses that action as
the initial question; question details can then be edited.

## Persistence and compatibility

`LongResponse.kt` holds Android-free models and codecs. `Notebook.longResponse` and
`Notebook.feedbackActions` are optional additive fields in the index and legacy portable codec.
Older notebooks default to no response and no actions. Index version 6, binary snapshots, journals,
and their undo/redo invariants are unchanged. Native v3 backups carry the index unchanged. Notebook
copies remap page links and clear result links because notebook duplication clears exam results.
Older app versions can read the notebook but do not retain these new fields when rewriting it.

New attempt snapshots are written before the index is published, through the normal serialized
writer and retry path. Existing page snapshots and their histories are not rewritten by creating an
attempt. Deleting a page retains missing references so the UI reports the loss instead of silently
linking feedback to another page. Questions and typed plans are metadata in `.folio` and library
backups; PDF/PNG export continues to export page content.

## Verification

Run `./gradlew :app:assembleDebug :app:lintDebug :app:prepareBackupSmoke`, then
`node tools/backup-smoke.cjs`. The smoke check covers absent fields, portable/index/native backup
round-trips, independent notebook copies, continuation ownership, reordering, missing sources,
result-link clearing, and prompt/topic search, alongside existing journal and undo/redo checks.

Before release on a stylus device:

1. Create each response mode, enter a long prompt and plan, rotate, leave/reopen and force-stop.
   Confirm saved text and ink remain and the prompt shelf fits compact/large-text layouts.
2. Write a draft, continue onto another page, make a marked copy, rewrite, compare, reorder and
   delete a source page. Check page ownership, original ink, undo/redo and missing-page messages.
3. Turn a typed comment into an action, practise it, resolve/reopen it and filter actions by subject
   from the library. Confirm the focus and source remain correct after restarting.
4. Mark two attempts differently. Each tally must exclude the other attempt; record and re-record
   marks and check history. Test timing, including an already-running timer.
5. Export/import `.folio`, restore a library backup, and duplicate a response notebook. Confirm
   prompts, plans, ink, feedback links and independent page IDs; open an older ordinary notebook.
6. Simulate save failure while creating a marked copy, retry, then reopen. All source pages and
   their undo histories must remain intact. Check split view and actual pen input on hardware.
