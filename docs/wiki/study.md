# Study and exams

[Wiki home](README.md) · [Notebooks](notebooks.md) · [Accounts and sync](data-and-settings.md)

## VCAA papers and marking

The **VCAA** destination provides a searchable catalogue of exam papers. Pick a paper to download and import it as a PDF-backed notebook with subject, year, paper, and marks metadata pre-filled. Downloads are restricted to the VCAA host, and imported papers use the same editor and storage as any other PDF notebook.

Open **Saved filters** to name and save a subject, year, or paper type on this device. Applying a saved filter replaces your current search and filters without downloading or importing papers; you can remove saved filters from the same panel.

**Marking tools** add mark areas, mark stamps, comments, numbered flags, and a feedback sheet. A mark area can span PDF pages as a single linked award across multiple page regions. Marking annotations are ordinary page items and participate in undo. See [Document transactions](../document-transactions.md) for cross-page rules.

Long-response workflows support linked drafts and marked copies, pinned questions, plans, side-by-side comparison, and feedback. Notebook sittings can also record exam timing and writing activity.

## Mistakes and review

**Mistakes** is Folio's spaced-repetition review flow, backed by Focal for account-owned review data. Work through a question using **Write** or **Type** (switching preserves both forms of working), then compare with the correction and rate your review.

- Typed responses support lists and KaTeX maths, save through the document writer, and can be previewed before comparison. A save error must be resolved before rating.
- Practice working lives in device-local practice notebooks (included in notebook backups), while review ratings sync with Focal.
- Enable **Record in Study timer** to log a review queue as a study session with per-question intervals.

See [Mistake responses](../mistake-responses.md), [Review pacing](../mistake-review-pacing.md), and [Focal sync](../focal-sync.md).

## Study timer and timeline

**Study** supports standalone and notebook-linked sessions, plans, reflections, and history. The day timeline lets you log or plan blocks, move and resize them, split them, and merge compatible sessions.

- Synced merges require a network connection and reject unsynced sessions.
- Eligible synced timed sessions completed today can be continued.
- If a timer is running on another device and this device is idle, Folio shows and controls it.
- Offline work queues in the Study manager and reconciles with Focal once connected. Some locally recorded, never-synced timer intervals cannot be reshaped.

See [Study sessions](../study-sessions.md) for recovery, merge limits, and device checks.

## Progress and planner

- **Progress** surfaces recorded exam results, question marks, confidence ratings, topic insights, and VCAA grade distributions. Estimates and correlations reflect your recorded data and are planning aids, not guarantees.
- **Planner** includes **Calendar**, **SACs**, **Stoplight**, **Goals**, **Score**, and **Grades** tabs. Calendar merges events, tasks, timetable entries, study sessions, and review markers; Score estimates study scores and ATAR; Grades uses bundled VCAA distributions. Planner syncs with Focal rows and covers a focused subset of Focal features. See [Planner](../planner.md) for supported data, unported features, and sync checks.

## For maintainers

- `vcaa/` owns the paper catalogue; `marking/` and `exam/` cover feedback and sittings.
- `mistakes/` owns review, cache, and sync; `study/` owns sessions and the timeline; `progress/` and `planner/` own analytics and planning.
- `math/` provides shared offline KaTeX rendering.
- Account and sync boundaries are documented in [Focal sync](../focal-sync.md).
