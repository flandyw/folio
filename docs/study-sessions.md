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
