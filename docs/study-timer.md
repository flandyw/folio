# Study timer

Study is a top-level destination in the phone navigation and tablet rail. It does not
require an open notebook or a Focal account. Choose a subject (including custom Focal
subjects), optionally link a notebook, then start a count-up timer. Pause, resume, save
with notes/confidence, or discard after confirmation. Manual entries accept 1–1,440 minutes.
Today's total includes active and completed regular study, clips intervals at local midnight,
and excludes pauses, exams and imported calendar placeholders.

The page and the editor's study panel share controls and the application-scoped
`FocalStudyManager`; leaving the page or rotating does not stop a timer. Display ticks are
foreground-lifecycle-bound, but elapsed time comes from the manager's monotonic clock.
Every lifecycle action is saved atomically before publication. While running, a local
checkpoint is saved every 30 seconds. After process death or reboot, the timer recovers
**paused at its last durable checkpoint**, rather than counting unattended time; up to
30 seconds of uncheckpointed work can be lost. No exact alarms or foreground-service
permissions are needed. This is an elapsed-study timer, not a Pomodoro countdown/alarm.

Signed-out sessions remain local and are claimed by the account on sign-in. Account-owned
sessions remain isolated; signing out or switching accounts parks and detaches that account's
local timer. The old account can resolve its parked session after signing back in. Local
save failures are visible and retryable. Remote errors leave sessions pending for retry.

Focal lifecycle commands use the existing canonical mutation API. Standalone timers replay
all durable intervals just like notebook-linked timers, including offline pause/resume
boundaries. Manual logs use the canonical `log` action with an explicit finished block;
they never synthesize a live timer. See [sync.md](sync.md) for transport and receipt rules.
Cross-app active sessions can be paused, resumed, finished or discarded; Folio prevents
starting/resuming a second running session locally (concurrent remote starts remain possible).
History includes reflections, notebook context and per-entry sync state and expands in batches.

## Release validation

Run `./gradlew :app:assembleDebug :app:lintDebug` and `node tools/katex-smoke.cjs`.
Before release, verify on an Android device with a Focal test account:

1. Start without a notebook while signed out. Navigate away/back, rotate, and background
   the app: elapsed time continues. Pause for a minute, resume and save; only active time
   appears in history. Repeat with a linked notebook, notes and confidence.
2. Start, wait past a checkpoint, then terminate/restart the process; it opens paused at
   the saved duration. Repeat after reboot. Old notebook-linked timers still recover.
3. Turn off the network; start, pause, resume, pause and save a standalone session. Restart
   and reconnect: exactly one canonical session has the correct ordered intervals and active
   duration. Repeat with discard and with a manual log.
4. Verify the same session on Focal desktop/web. Pause/resume/finish there and confirm
   Folio updates without resurrecting a completed session. Reconnect after missed updates.
5. Sign out with a running session, then sign in as a different user. The old timer and
   history must not appear or upload under the new account; return to the old user to resolve it.
6. Start an exam timer: regular study is parked and cannot resume until the exam stops.
   Verify reading time and pauses are not counted as ordinary study.
7. Check midnight-spanning study, custom subjects, empty history, many sessions, large text,
   narrow phone/landscape/tablet layouts, TalkBack controls, and discard cancellation.
8. With insufficient device storage, confirm the save error remains visible and Retry saves
   successfully after freeing space. With no network, confirm pending sessions survive restart.
