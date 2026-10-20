# Study session recovery

The editor stopwatch counts only while the pages are visible. Leaving the editor,
backgrounding Folio or turning the screen off parks it; returning to the pages does
not resume it automatically. Sessions started from Study keep counting while the
app process stays alive in the background. Both recover paused after process death,
at the last successful local checkpoint, so time while Folio was closed is excluded.
Rotation preserves the running session.

`FocalStudyManager` keeps the live timer in the application scope. Each successful
write to `focal-study.json`, including sync receipts, takes a recoverable copy of the
focus and its session entry at the same instant. That copy closes the open interval
and retains its active time and an unsynced pause intent; it does not pause or dirty
the live timer. Running focus sessions checkpoint every five seconds, independently
of the thirty-second sync loop, and immediately when the Activity stops. Android
can defer background work, so recovery always uses the last successful write rather
than promising a fixed loss limit during sleep.

Pause, resume, finish, discard and account detachment update the focus and entry in
one state change before the atomic file write. Old running saves without a heartbeat
close their open interval at its start in both models. Resuming appends a fresh
interval; offline replay retains each pause/resume boundary. A pending local pause
beats an old running server echo. Remote running checkpoints contribute only closed
intervals to the timer's accumulated base, preventing the open interval from being
counted twice.

Exam timers synchronously commit their notebook preferences on each state change
and heartbeat, including clearing a stopped sitting. Disk restoration always parks
a running timer at its saved last-visible heartbeat before advancing the countdown,
even for an immediate restart. An in-memory sitting retains the existing grace
period. A manually paused sitting preserves its pause and elapsed time.
Restoration uses the same Focal-adjusted clock as recording, including in-memory
notebook switches.

These changes keep the existing session JSON fields and notebook storage formats.

## Planned study

Study and the study sessions panel manage unfinished focus sessions shared with
Focal: create, edit, duplicate, delete and reschedule plans with a subject, notes,
topics and up to 100 non-overlapping blocks (each up to 24 hours). The editor timer
dialog starts the same plans and links to the full sessions panel. Plans are
searchable by title/subject/topics and filterable by subject and all/today/overdue/
upcoming, sorted by scheduled start. Connect the same Focal account and refresh to
receive website changes. Exam/SAC plans are excluded from regular study.

Starting a plan reuses its ID and revision, title, subject and metadata, optionally
attaching the selected/open notebook. Scheduled blocks are never worked intervals:
the stopwatch starts at zero with a fresh interval. The original schedule remains
in metadata as the duration goal, shown alongside elapsed study; reaching it never
automatically finishes the session. The durable command queue publishes `start`
against the planned row, followed by any offline pause/resume/complete boundaries.
Study and sessions-panel starts count in the background; editor timer starts pause
when leaving the pages. A current focus, another running session or local exam
blocks starting a plan. Focal's calendar now starts this same row too.

Local plan edits live in optional `planDraft` in `focal-study.json`, separate from
the last canonical `remotePayload`. Create/save-progress send the canonical blocks
and metadata, with no timer boundary fields. Accepted receipts clear the draft;
refused edits retain it for review rather than silently retrying over another
device's reschedule. A conflicted draft is synced-but-retained and cannot start
until reviewed and explicitly saved. Atomic local plan mutations check the current
account, lifecycle and revision. No notebook/journal/backup format changes.

The timeline moves and resizes individual blocks of both plans and completed study,
including completed sessions started from plans. Other blocks and break gaps stay
intact; overlapping edits and completed time in the future are refused. Blocks
clipped across midnight cannot be dragged. Unsent timer boundaries must sync first,
and conflicted plan drafts must be reviewed in Edit before timeline changes.

Device checks:

- Drag the body and both resize handles of a multi-block plan, then complete it
  and repeat. Verify the other blocks, break gaps and details survive refresh
  and restart. Check blocks starting/ending at midnight and rejected overlaps.
- Create a multi-block plan in each client; verify its subject, notes, topics and
  break gaps on the other. Edit, duplicate, move to today/now, and delete it.
- Start a website plan from each Folio entry point and Focal's calendar/timer;
  verify the same row completes with only measured active time and its original
  title/subject/details/schedule. Pause and confirm the duration goal stays fixed.
- Repeat offline, with several pauses and a process restart before reconnecting;
  verify no duplicate row, billed break or automatic resume. Focal must retain
  cached account plans after an offline browser reload.
- Reschedule a plan elsewhere while editing it, and verify the stale save is
  refused. In Folio reopen the retained draft, review and explicitly save it.
  Starting/completing/deleting a plan elsewhere must stop local plan editing.
- Reject zero-length/overlapping blocks; preserve drafts and block dates through
  rotation. Check portrait, landscape and phone layouts, midnight and time zones.

## Study page behaviour

Pure rules for the screens live in `StudyTimerRules.kt` (no Android imports) and are
checked by `StudyTimerRulesTest` (`./gradlew :app:testDebugUnitTest`): duration and
clock labels, history grouped by local day, this week's total (from Monday), the
day streak, plan scope/search filters, plan "when" labels and per-block plan problems.

- Start offers the notebook's subject, else the subject studied most recently, and
  names it on the button. Blocked starts say why (exam timer, another session).
- A running or paused session can change subject (`changeFocusSubject`): one
  `save_progress` on the same row; the generated "<subject> Focus" title follows,
  a plan's own title does not. Recorded time is untouched.
- The Study page keeps the screen awake while its clock runs (a View flag, released
  on pause or leaving). The editor stopwatch does not.
- Saving, logging, discarding and deleting show a short confirmation. Discard and
  delete confirmations state the time that will be removed.
- History is grouped by day with daily study totals (exams listed, never counted).
  Tapping a study row shows its day on the timeline; it can be deleted after
  confirmation (`deleteSession`, a tombstone, so no undo).
- Plan filters appear once there are more than three plans. Search matches every
  word in any order. Plan cards show when they are due ("Starts in 20 min",
  "Overdue · Yesterday").
- The plan editor marks the block to fix (backwards, over 24 h, overlapping) and
  only enables Save when there is none; a blank title saves as "<subject> study";
  closing with unsaved edits asks first (`FolioPanel(canDismiss)`); a plan changed
  elsewhere shows a banner and cannot be saved over.
- The timeline follows today past midnight, opens a date picker from its title,
  resets tools when the day changes, and lets result messages fade. Split and
  "Repeat" keep a plan's notes and topics.

Device checks: start/pause/change subject while online and offline and confirm Focal
shows the new subject with the same time; leave the Study page running and confirm
the screen stays on, then pause and confirm it can sleep; delete a history session
and confirm it disappears on Focal; leave the Study page open over midnight.

## Short session merging

Focal migration `0022_merge_short_study_sessions.sql` combines completed focus
fragments under two minutes for the same subject when the gap is at most five
minutes. Groups can keep growing as more fragments arrive. Overlapping sessions,
unknown subjects, exam sittings and intervening work on another subject stay
separate. Breaks remain gaps between the original intervals, not active time.
Groups stop at 100 intervals or the command metadata size limit.

The server keeps the receiving session ID and publishes the absorbed sessions as
deleted tombstones. Original titles and metadata are retained in
`metadata.auto_merge_sources`, and the original rows remain in the database.
The migration also groups existing fragments. Folio adopts the complete merged
timeline from both mutation receipts and remote updates, including its earlier
start time. Merging happens when sessions sync; offline entries remain separate
until then. Both clients must use the migrated Supabase database.

## Duplicate interval recovery

Focus command batches are frozen in durable preferences under the account and
session ID before any RPC. Requests keep their mutation IDs, revisions and timing
across timeouts and process restarts. Each acknowledged prefix advances the saved
batch; newer local actions wait until that batch finishes. Previously, rebuilding
a partly accepted batch minted new IDs for later pause/resume commands and could
replay them as extra intervals. Timed commands are never rebased onto a conflicting
server timeline.

Focal migration `0023` cleans completed focus history and subsequent completions:
it removes sub-second intervals and exact copies within or across sessions of the
same subject, then recalculates active time. Sessions left empty are deleted via
the normal tombstone feed. Folio adopts the cleaned canonical receipt immediately.
Minute labels alone are not duplicate evidence; distinct real intervals remain.

Migration `0024` applies cleanup to paused/running exams and SACs too. It preserves
the open interval and pause/timing timestamps. Bursts of three or more closed
intervals under 30 seconds in one minute from the same phase/device are removed
as chatter; isolated short intervals remain. Existing paused exams are repaired
without completing or deleting the sitting. Folio adopts the pruned intervals
from settled exam receipts. A background checkpoint must wait for an acknowledged
remote pause to reach the countdown, rather than publishing an automatic resume.
Finishing a paused exam does not append an interval with no new active time.

## Verification

`node tools/focal-study-smoke.cjs` exercises the actual pure models and helpers:
checkpoint consistency and repeated recovery, pause/resume cycles, offline command
replay, remote reconciliation, and immediate/long exam restarts. The canonical
signed build is `./build.sh -p`.

On a device:

- Start the editor stopwatch, pause, close Folio, reopen and resume. Verify the
  elapsed time stays fixed during the pause and increases only after resuming.
- Leave the editor, switch apps and turn the screen off with an editor stopwatch
  running. Verify it returns parked and time away is excluded. Rotate while running
  and verify rotation does not pause it.
- Start from Study, switch apps and return while Folio remains alive. Verify it kept
  counting. Swipe Folio away or force-stop it, reopen and verify recovery is paused
  at its last saved time; repeat after several pause/resume cycles.
- Repeat offline, reconnect and verify Focal receives the same active time and
  pause/resume boundaries, with no duplicate session or automatic recovery resume.
- While syncing, pause, resume and finish quickly. Verify a late receipt neither
  revives a finished session nor doubles elapsed time.
- Repeat background, force-stop and immediate-restart checks with an exam timer in
  reading and writing phases, including a manually paused sitting and a stopped one.
