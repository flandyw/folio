# Study sessions and recovery

## Timer durability and checkpoints

- **Editor stopwatch:** Counts only while pages are visible. Leaving the editor, backgrounding Folio, or turning off the screen parks it without auto-resuming on return.
- **Study page timer:** Keeps counting while the process stays alive in the background. Both timers recover paused after process death at the last local checkpoint (time while closed is excluded). Screen rotation preserves running timers.
- **Checkpointing:** `FocalStudyManager` keeps live timers in application scope. Every atomic write to `focal-study.json` (and every 5-second heartbeat plus Activity stop) writes a recoverable copy that closes the open interval with an unsynced pause intent without pausing the live timer.
- **Reconciliation:** Pause, resume, finish, discard, and account detachment update focus and entry atomically. Pending local pauses beat stale remote running echoes, and remote running checkpoints only contribute closed intervals so open intervals are never double-counted.
- **Exam timers:** Commit preferences synchronously on every state change and heartbeat; disk restoration parks running timers at their last-visible heartbeat using the Focal-adjusted clock.

No session JSON fields or notebook storage formats change.

## Planned study

Study and the sessions panel manage unfinished focus sessions shared with Focal (subject, notes, topics, and up to 100 non-overlapping blocks up to 24 h each; exam/SAC plans stay separate). Plans are searchable and filterable by subject and scope (all / today / overdue / upcoming).

- **Starting a plan:** Reuses the plan's ID, revision, title, subject, and metadata, optionally linking a notebook. Scheduled blocks become the duration goal (never worked time); the stopwatch starts at zero and never auto-finishes at the goal.
- **Drafts and conflicts:** Local plan edits live in `planDraft` inside `focal-study.json`, separate from `remotePayload`. Accepted receipts clear the draft; refused edits keep it for manual review and block starting until resolved.
- **Timeline editing:** Moves and resizes individual blocks of plans and completed study (leaving breaks intact, refusing overlaps, future completed time, or midnight-clipped drags). Unsent timer boundaries must sync first.

## Study page behaviour

Pure rules live in `StudyTimerRules.kt` (`StudyTimerRulesTest`): duration/clock labels, local-day grouping, Monday-based weekly total, day streak, plan filters, "when" labels, and per-block validation.

- **Starting & switching:** Start defaults to the notebook's subject (or most recently studied) and explains blocks (active exam or session). `changeFocusSubject` updates a running/paused session on the same row without touching elapsed time. The Study page keeps the screen awake while running; the editor stopwatch does not.
- **History & confirmation:** History groups by day with daily totals (exams listed, not counted); tapping jumps to that day on the timeline; deletion confirms time removed and writes a tombstone (no undo).
- **Plan editor:** Flags invalid blocks inline (backwards, >24 h, overlapping), defaults blank titles to `<subject> study`, confirms before discarding unsaved edits, and blocks overwriting plans changed elsewhere.
- **Timeline:** Minute-accurate everywhere (drawing, dragging, resizing, razor, *Move to now*, ±1 min steppers, system time picker, 10-minute zoom with minute ticks). Includes a Monday–Sunday week strip, undo for moves/resizes, midnight rollover, and split/repeat preserving notes and topics.
- **Past study & accessibility:** Manual minutes (`studyManualMinutes`) accept only whole integers `1..1440` with inline validation. Section/day titles are accessibility headings; delete and date/time buttons name their target.

## Short session merging and duplicate interval recovery

- **Auto-merge (`0022_merge_short_study_sessions.sql`):** On sync, Focal merges completed focus fragments under 2 minutes of the same subject separated by ≤5 minutes (up to 100 intervals), preserving breaks as gaps and publishing absorbed rows as tombstones. Overlapping sessions, exams, and intervening subjects stay separate.
- **Frozen batches:** Focus command batches freeze in durable prefs before RPC so retries keep identical mutation IDs, revisions, and timestamps instead of minting duplicate intervals.
- **Cleanup (`0023` & `0024`):** Server migrations strip sub-second/exact duplicate intervals on completed sessions (`0023`) and chatter bursts (≥3 closed intervals <30 s in one minute) on paused/running exams (`0024`). Folio adopts canonical receipts immediately; background checkpoints wait for remote pauses and finishing a paused exam appends no zero-time interval.

## Verification

- **Automated:** `node tools/focal-study-smoke.cjs`, `StudyTimerRulesTest`, and `./build.sh -p`.
- **On device:**
  - Test editor stopwatch pause/close/reopen, backgrounding, screen-off, and rotation; test Study timer backgrounding and force-stop recovery (online and offline).
  - Test rapid pause/resume/finish during sync (no revived sessions or doubled time) and exam timer reading/writing restarts.
  - Drag/resize multi-block plans and completed sessions on the timeline; test offline plan starts, stale-edit conflict review, subject switching, history deletion, midnight rollover, and narrow-pane/TalkBack validation (`"1.5"`, `"-5"`, search clear with date range).
