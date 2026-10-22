# Planner (Focal parity)

One sidebar destination (`SidebarItem.PLANNER`, `LibrarySection.PLANNER`, `planner/PlannerScreen.kt`) holds the Focal
features Folio did not have. Everything lives in rows Focal already syncs, edited through `ExamProgressManager`
(`save` / `saveValue`), so there is no Folio-only store and no storage-format change.

| Tab | Focal source | Synced row |
|-----|--------------|------------|
| Calendar | `calendar-page`, `day-plan.ts`, `event-dialog` | `events:<id>` (CalendarEvent), `user_state:learning` (`tasks`), `timetable_config:timetable_config` |
| SACs | `sac.ts`, `sac-page` | `user_state:sacRecords` (whole list) |
| Stoplight | `stoplight.ts`, `stoplight-page` | `user_state:learning` (`curriculumAreas`) |
| Goals | `learning-workspace.ts`, `goals-page` | `user_state:learning` (`goals`) |
| Score | `study-score.ts`, `scaling.ts`, `atar.ts` | `user_state:atarEstimates` (newest first) |
| Grades | `vcaa-explorer` | read-only (`assets/progress/vcaa-grade-distributions.json`) |

- `PlannerRules.kt` (SACs, learning workspace, stoplight, goals) and `CalendarRules.kt` (events, class timetable) edit
  raw `JSONObject`s, so fields a newer Focal adds survive a Folio save. **Focal drops a whole list when one record
  fails its validation** (`migrateSacRecords`, `isLearningWorkspace`), so every write is validated first.
- `StudyScore.kt` is a line-for-line port of Focal's study score, scaling and ATAR code. `assets/planner/vtac-scaling.json`
  is generated from Focal's `public/vtac-scaling-reports.json` (references only) plus `src/lib/atar-data.ts`; regenerate it
  when Focal adds a scaling year.
- The Calendar tab follows Focal's `calendar-page.tsx`: a pure day plan (`Calendar.dayPlan` / `Calendar.month`, a port of `day-plan.ts`)
  over tasks, SACs, events, Focal study sessions (`FocalStudyManager`), logged exams and due mistakes, with Focal's totals rules
  (merged session minutes, a task covered by a same-titled session, events and the review queue as markers). Finished events
  fold into "done" (Focal keeps them open). The shared `StudyTimeline` sits under it and follows the selected day.
- Read event/timetable fields with `JSONObject.text`, never `optString`: Focal rows carry `"deleted_at": null`, which Android's
  `optString` returns as `"null"` (the JVM tests' org.json returns ""), and that once hid every event. `ExamProgressManager`'s
  `FEED_VERSION` makes an older cache re-read the feed once; bump it whenever `SYNCED_ENTITIES` grows.
- Checked by `PlannerTest`, `StudyScoreTest`, `CalendarTest` (`./gradlew :app:testDebugUnitTest --tests 'com.folio.notes.planner.*'`).
  Expected values in `StudyScoreTest` / `CalendarTest` were produced by running Focal's own TypeScript (`bun`) on the same input.

## Not ported

SAC timer, chatbot "revise" and "link mistakes" (Stoplight), calendar chatbot import, event subject picker, repeating events,
VCE exam timetable markers, marking a study session done from the day pane (open Study instead), timetable holiday/period editing (holidays set in Focal are preserved), grade-boundary chart, friends
and chat. Mistake reviews do not move Stoplight ratings (Focal does that on its side from `itemIds`).

## Device check

Sign in, add a SAC / event / task / goal and a stoplight import, sync, and confirm each appears in Focal (web or desktop)
and that an edit made in Focal arrives here. A SAC list Focal cannot validate disappears there entirely, so check this
first after changing `Planner.validateSac`.
