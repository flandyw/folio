# Progress and exam logging

Progress now brings Focal's exam workflows into Folio, with the same floating destination toolbar as Mistakes:

- **Overview:** result totals, difficulty-aligned averages, recent results, subject coverage, weekly activity, notebook results and upcoming notebook exam dates.
- **Exams:** log, edit and delete results; filter by subject, provider, paper and year; search and sort; record comments, question marks, confidence, context and timing; capture linked mistake cards.
- **Lists:** create or import a Focal exam progression, append papers or replace selected subjects, reorder papers, and follow each subject independently. Browse the bundled VCAA library, papers, reports and specifications; mark completion, log results, or start a timed notebook. Tagged local notebooks have their own list.
- **Insights:** raw/aligned score trends, VCAA percentile trends, cohort benchmarks, improvement outlooks, paper/topic mastery, lost marks, confidence calibration, performance context, timing, revision priorities and review forecasts.

Open a result to compare official distributions across years. The normal curve, mean, median and standard deviation use the same grade-band midpoint calculation as Focal; percentiles interpolate inside official bands. These and provider-adjusted scores are planning estimates. The catalogue is copied from Focal's `web/public/vcaa-grade-distributions.json` and `vcaa-exam-resources.json`, retaining their source URLs and generation dates. Replace both assets under `app/src/main/assets/progress/` when refreshing that source pack.

The export action shares a standalone HTML report with exam results and topic mastery. It opens in a browser and supports printing.

## Persistence and sync

`ExamProgressManager` is app-scoped and uses the existing process-wide `FocalSupabaseConnection`. It restores the shared Focal identity; it never creates a second client or session file. While Progress is visible, it refreshes the shared ordered feed every 30 seconds and on entry, and publishes after local edits.

Each identity has an atomic `files/progress/<user-id>/cache.json` (version 1). Signed-out work uses `files/progress/device/cache.json`. This is a separate cache; notebook indexes, page snapshots, journals and `.folio` storage formats are unchanged. The cache contains raw materialized rows, a durable outbox with stable mutation IDs and expected sequences, the feed cursor, and explicit sync conflicts. Writes reach disk before the UI closes a form or a mutation is sent. Unreadable files are retained and edits are blocked until loading succeeds.

Exam results use Focal's `attempts` entity and camel-case payload fields. Mistake capture uses `mistakes`; review remains in the existing Mistakes page. Progression, provider difficulty and completed official paper IDs use Focal's `user_state` rows (`examProgression`, `examDifficulty`, `completedExamIds`) with `{value, updated_at}` payloads. Legacy singleton user-state payloads are readable. Editing starts with the original JSON so timing, attachments and unknown fields survive.

Concurrent remote edits are displayed for resolution: use the Focal version or keep the saved local edit against the displayed remote sequence. A remote deletion only offers the Focal version, preventing accidental resurrection. Account changes hide the previous cache; open forms retain their original account identity and cannot save into the next account.

Notebook marks with a known total appear immediately. **Add notebook results to log** publishes copies using the existing attempt IDs and a `folioNotebookId` link, preventing duplicates. Editing such a log also updates the corresponding local notebook mark; these use whole-number marks to match notebook storage. Deleting a linked result removes its notebook mark but keeps the notebook and mistake cards. Marks later recorded directly in a notebook appear as additional results and can be added to the log. Each result is named `{company} {year} {subject}` (typed maths-methods subjects become Mathematical Methods). A result that is already logged, but only a likely match, can be dismissed from the review; dismissals are stored per device (`focal.dismissedResults`), never synced, and cannot be undone yet. **Record and save to Focal** on an exam notebook's mark dialog records the mark, then opens the same review for that one result so the details can be checked before it is saved. **Copy device logs to this account** copies signed-out exam results and mistake cards without overwriting existing account rows.

## Checks

Run `./gradlew :app:assembleDebug :app:lintDebug`. Before releasing, check logging and reopening a result, question totals, progression append/replace, distribution year selection, and offline save followed by Focal sync. For account sync, check sign-out/account switching, lost-response retries and concurrent web edits/deletions. No notebook codec or journal format migration is involved.
