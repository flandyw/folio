# Study and exams

[Wiki home](README.md) · [Notebooks](notebooks.md) · [Accounts and sync](data-and-settings.md)

## VCAA papers and marking

The VCAA destination provides a searchable catalog of exam resources. Select a paper to download and import it as a PDF-backed notebook; Folio fills available subject, year, paper and marks metadata. Downloads are restricted to the VCAA host. Imported papers use the same editor and notebook storage as other PDFs.

Marking tools provide mark areas, mark stamps, comments, numbered flags and a feedback sheet. A mark area can span PDF pages: it is one linked award, even though it has a region on each page. Marking decorations and comments are ordinary page content, so edits participate in undo. See [document transactions](../document-transactions.md) for cross-page rules.

Long-response workflows support linked drafts and marked copies, pinned questions, plans, comparison and feedback. Notebook sittings can record exam timing and writing activity.

## Mistakes and review

Mistakes is Folio's ExamTrack review experience, connected to Focal for account-owned review data. Review a question using **Write** or **Type**; switching preserves both kinds of working. Compare the answer with the correction, then rate the review. Typed responses support lists and maths, save through the document writer and can be previewed before comparison. A save failure must be resolved before rating.

Practice working stays in device-local practice notebooks and travels in notebook backups; review ratings use Focal sync. Review pacing and scheduling have their own rules. Optional **Record in Study timer** records a review queue as a study session with intervals for question visits. See [mistake responses](../mistake-responses.md), [review pacing](../mistake-review-pacing.md) and [Focal sync](../focal-sync.md).

## Study timer and timeline

Study supports standalone and notebook-linked sessions, plans, reflections and history. The timeline displays a day and allows logging or planning blocks, moving and resizing them, splitting and selecting sessions to merge. Compatible sessions can be merged; synced merges require connectivity and reject unsynced sessions. A completed synced timed session from today may be continued when eligible. A timer running on another device can be shown and controlled when this device is not timing anything.

Offline work is queued through the Study manager and reconciled with Focal when available. Some locally recorded, never-synced timer intervals cannot be reshaped. See [study sessions](../study-sessions.md) for recovery, merge limits and device checks.

## Progress and planner

Progress presents recorded exam results, question marks, confidence, topic insights and available VCAA distribution comparisons. Estimates and correlations depend on the recorded data; they are planning aids, not guarantees.

Planner has Calendar, SACs, Stoplight, Goals, Score and Grades tabs. Calendar combines events, tasks, timetable information, study and review markers. Score includes study-score and ATAR estimation; Grades uses bundled VCAA distribution data. Planner works with Focal rows and does not include every Focal feature. The [planner guide](../planner.md) lists the supported data, unported features and sync checks.

## For maintainers

`vcaa/` provides the catalog; `marking/` and `exam/` cover feedback and sittings. Review and its cache/sync live in `mistakes/`; Study in `study/`; Progress and Planner in `progress/` and `planner/`. `math/` provides shared offline formula rendering. Focal's account and synchronization boundaries are covered in [Focal sync](../focal-sync.md).
