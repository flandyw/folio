# Study timer

Study is a top-level destination in the phone navigation and tablet rail. It does not
require an open notebook or a Focal account. Choose a subject (including custom Focal
subjects), optionally link a notebook, then start a count-up timer. Pause, resume, save
with notes/confidence, or discard after confirmation. Manual entries accept 1–1,440 minutes.
Today's total includes active and completed regular study, clips intervals at local midnight,
and excludes pauses, exams and imported calendar placeholders.

Layout is adaptive (content width ≥ 840dp splits, e.g. landscape tablet or the landscape
panel): a timer column (clock, Pause/Resume, wrap-up form) and an independently scrolling
context column (today + sync, other apps' sessions, history). Narrower widths, such as portrait
tablets, stack the same blocks timer-first in a centred column.

The page and the editor's study panel share controls and the application-scoped
`FocalStudyManager`; leaving the page or rotating does not stop a timer. Display ticks are
foreground-lifecycle-bound, but elapsed time comes from the manager's monotonic clock.
Every lifecycle action is saved atomically before publication. While running, a local
checkpoint is saved every 30 seconds. After process death or reboot, the timer recovers
**paused at its last durable checkpoint**, rather than counting unattended time; up to
30 seconds of uncheckpointed work can be lost. No exact alarms or foreground-service
permissions are needed. This is an elapsed-study timer, not a Pomodoro countdown/alarm.

## Editor stopwatch

The editor's stopwatch is not a separate clock: **Timer & stopwatch → Study stopwatch** starts
the same Focal study session (`FocalStudyManager.startFocus`, `attended = true`) linked to the
open notebook, with the subject suggested from its metadata. Pause/resume, **Finish & save**
(straight to history; add notes from the study panel) and **Discard** act on that session, and
it appears in history and on other Focal apps. Holding the Focal chip pauses/resumes it.

An *attended* session (started from the editor) counts only while the pages are on screen:
leaving the editor for the library, backgrounding the app or turning the screen off parks it
(`parkFocus`, published as a pause boundary) and it only resumes by hand. Switching between
notebooks does not park it. A session started from the Study page is unattended and keeps
running in the background. Starting an exam timer parks a running session as well.

An exam's Focal session counts reading and writing time, excluding pauses. Its elapsed
timer starts during reading and continues across the phase change; finishing during reading
saves the time spent so far. The attempt's writing-time record still measures writing only.
The pen-inactivity stop applies only during writing, with its idle allowance beginning when
writing starts. Reading continues without pen strokes; manual pauses and leaving the editor
still park the exam clock.

Status is explicit, via `FocalFocus.parkReason` (persisted in `focal-study.json`, optional, so
older files load unchanged): *Paused* (by hand), *Stopped when you left the pages*, *Recovered
after Folio closed* (the last durable checkpoint was restored; up to 30 s of work lost) and
*Paused for the exam timer*. The chip, panel and stopwatch tab show it with the sync status.
The old per-notebook stopwatch (`stopwatch.*` preferences) is gone; a stopwatch that was
running at update time is not carried over.

Signed-out sessions remain local and are claimed by the account on sign-in. Account-owned
sessions remain isolated; signing out or switching accounts parks and detaches that account's
local timer. The old account can resolve its parked session after signing back in. Local
save failures are visible and retryable. Remote errors leave sessions pending for retry.

Focal lifecycle commands use the existing canonical mutation API. Standalone timers replay
all durable intervals just like notebook-linked timers, including offline pause/resume
boundaries. Manual logs use the canonical `log` action with an explicit finished block;
they never synthesize a live timer.

Canonical session rows (Focal migration `0020`) have no `state` column: they carry a
`completed` flag, `cancelled_at` marks a deleted session, and running, paused and scheduled
(Folio's "planned") are derived from the open interval, `paused_at` and the absence of a
start (`focalStateOf`). A scheduled session's segment is its planned slot, so Folio ignores it
as timer history and a start replaces it. A completed session is no longer final: Folio can
delete it with `cancel`; only a deleted row answers `session_terminal`. Rows cached by an older
build keep their `state` and are still read. See [sync.md](sync.md) for transport and receipt rules.
Cross-app active sessions can be paused, resumed, finished or discarded; Folio prevents
starting/resuming a second running session locally (concurrent remote starts remain possible).
History includes reflections, notebook context and per-entry sync state and expands in batches.

## Release validation

Run `./build.sh -p`, `node tools/focal-study-smoke.cjs` and `node tools/katex-smoke.cjs`.
Before release, verify on an Android device with a Focal test account:

1. Start without a notebook while signed out. Navigate away/back, rotate, and background
   the app: elapsed time continues. Pause for a minute, resume and save; only active time
   appears in history. Repeat with a linked notebook, notes and confidence.
   Then start the editor stopwatch: leave the editor and background the app; it must show
   *Stopped when you left the pages* and not count the time away.
2. Start, wait past a checkpoint, then terminate/restart the process; it opens paused at
   the saved duration, labelled *Recovered after Folio closed*. Repeat after reboot. Old notebook-linked timers still recover.
3. Turn off the network; start, pause, resume, pause and save a standalone session. Restart
   and reconnect: exactly one canonical session has the correct ordered intervals and active
   duration. Repeat with discard and with a manual log.
4. Verify the same session on Focal desktop/web. Pause/resume/finish there and confirm
   Folio updates without resurrecting a completed session. Reconnect after missed updates.
5. Sign out with a running session, then sign in as a different user. The old timer and
   history must not appear or upload under the new account; return to the old user to resolve it.
6. Start an exam timer: regular study is parked and cannot resume until the exam stops.
   Verify the Focal session timer includes reading and writing, excludes pauses, and preserves
   the duration across pause/resume in either phase. Finish once during reading and once after
   writing; confirm the saved Focal duration on another client. Exams remain separate from
   the regular-study daily total.
   With a short inactivity limit, let reading finish without pen strokes: it must keep running
   and allow the full inactivity interval after writing begins before parking.
7. Check midnight-spanning study, custom subjects, empty history, many sessions, large text,
   narrow phone/landscape/tablet layouts, TalkBack controls, and discard cancellation.
8. With insufficient device storage, confirm the save error remains visible and Retry saves
   successfully after freeing space. With no network, confirm pending sessions survive restart.
