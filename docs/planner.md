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
- Checked by `PlannerTest`, `StudyScoreTest`, `CalendarTest` (`./gradlew :app:testDebugUnitTest --tests 'com.folio.notes.planner.*'`).
  Expected values in `StudyScoreTest` / `CalendarTest` were produced by running Focal's own TypeScript (`bun`) on the same input.

## Not ported

SAC timer, chatbot "revise" and "link mistakes" (Stoplight), calendar study-session blocks and repeating events, exam
timetable markers, timetable holiday/period editing (holidays set in Focal are preserved), grade-boundary chart, friends
and chat. Mistake reviews do not move Stoplight ratings (Focal does that on its side from `itemIds`).

## Device check

Sign in, add a SAC / event / task / goal and a stoplight import, sync, and confirm each appears in Focal (web or desktop)
and that an edit made in Focal arrives here. A SAC list Focal cannot validate disappears there entirely, so check this
first after changing `Planner.validateSac`.
