# Planner (Focal parity)

A single sidebar destination (`SidebarItem.PLANNER`, `LibrarySection.PLANNER`, `planner/PlannerScreen.kt`) brings Focal's planning views into Folio. Everything reads and writes existing Focal-synced rows through `ExamProgressManager` (`save` / `saveValue`), with no Folio-only store or storage-format changes.

| Tab | Focal source | Synced row |
| --- | --- | --- |
| Calendar | `calendar-page`, `day-plan.ts`, `event-dialog` | `events:<id>` (`CalendarEvent`), `user_state:learning` (`tasks`), `timetable_config:timetable_config` |
| SACs | `sac.ts`, `sac-page` | `user_state:sacRecords` (whole list) |
| Stoplight | `stoplight.ts`, `stoplight-page` | `user_state:learning` (`curriculumAreas`) |
| Goals | `learning-workspace.ts`, `goals-page` | `user_state:learning` (`goals`) |
| Score | `study-score.ts`, `scaling.ts`, `atar.ts` | `user_state:atarEstimates` (newest first) |
| Grades | `vcaa-explorer` | Read-only (`assets/progress/vcaa-grade-distributions.json`) |

## Implementation notes

- **Raw JSON preservation:** `PlannerRules.kt` (SACs, learning workspace, stoplight, goals) and `CalendarRules.kt` (events, class timetable) edit raw `JSONObject`s so fields added by newer Focal builds survive Folio saves. Because **Focal drops an entire list if any single record fails validation** (`migrateSacRecords`, `isLearningWorkspace`), every write validates first.
- **Study score and scaling:** `StudyScore.kt` mirrors Focal's study-score, scaling, and ATAR logic line for line. `assets/planner/vtac-scaling.json` is generated from Focal's `public/vtac-scaling-reports.json` (references only) plus `src/lib/atar-data.ts`; regenerate it whenever Focal adds a scaling year.
- **Calendar:** Mirrors Focal's `calendar-page.tsx` via pure `Calendar.dayPlan` / `Calendar.month` over tasks, SACs, events, Focal study sessions (`FocalStudyManager`), logged exams, and due mistakes, using Focal's totals rules. Finished events fold into "done" (Focal leaves them open). The shared `StudyTimeline` sits beneath and tracks the selected day.
- **Null-safe parsing:** Always read event/timetable fields with `JSONObject.text`, never `optString`: Focal sends `"deleted_at": null`, which Android's `optString` turns into `"null"` (unlike JVM `org.json`'s `""`). Bump `ExamProgressManager.FEED_VERSION` whenever `SYNCED_ENTITIES` grows so older caches re-read the feed once.
- **Timetable import & midnight spans:** Timetable import rejects malformed day/period arrays and non-integer cycle/day numbers before replacing the timetable; repeated day labels merge periods in time order, preserving holidays and view settings. Calendar spans exclude exact midnight ends from the following day, while the edit form preserves the stored end date for overnight and multi-day events.

## Not ported

SAC timer, chatbot "revise" and "link mistakes" (Stoplight), calendar chatbot import, event subject picker, repeating events, VCE exam timetable markers, marking a study session done from the day pane (use Study), timetable holiday/period editing (holidays from Focal are preserved), grade-boundary chart, friends, and chat. Mistake reviews do not update Stoplight ratings (Focal updates them from `itemIds`).

## Verification

- **Automated:** `./gradlew :app:testDebugUnitTest --tests 'com.folio.notes.planner.*'` (`PlannerTest`, `StudyScoreTest`, `CalendarTest`; expected outputs generated with Focal's TypeScript via `bun`).
- **Sync check on device:** Sign in, add a SAC, event, task, goal, and stoplight import, sync, and confirm each appears in Focal (web/desktop) and remote edits arrive in Folio. Always test SAC sync first after modifying `Planner.validateSac`.
- **Layout and accessibility (manual):** At 320 dp / narrow split pane and maximum font scale, verify Stoplight actions, legend, and rating chips wrap cleanly, empty filters show *Show all*, and amber chips keep dark readable labels in both themes. With TalkBack, confirm calendar days announce "Today" and selected state, the checklist subject reads as selected, the ATAR *Use predicted score* switch names its study, and invalid SAC marks (e.g. `abc`) announce an error. Editing a SAC titled `SAC 2.0` must retain `.0`.
