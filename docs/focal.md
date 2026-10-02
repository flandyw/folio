# Focal study sessions

Folio records study sessions locally and syncs them with Focal's shared Supabase project. Session lifecycle writes use the canonical `study_session_mutate` RPC and cursor reads use `sync_read_changes`; session rows never go through generic `sync_apply_changes` or the `sync_changes` compatibility view. Realtime is a wakeup only. Folio mistake reviews use the shared cursor and versioned generic change RPC.

## Configure

Use Focal's `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` as Folio's values below. Supply them through environment variables, Gradle properties, or ignored `local.properties`, in that order. Only HTTPS URLs and publishable or anon client keys are accepted. Credentials are placeholders in a local build when these values are absent, and the app keeps recording sessions locally.

```properties
FOCAL_SUPABASE_URL=https://your-project.supabase.co
FOCAL_SUPABASE_PUBLISHABLE_KEY=your-focal-publishable-or-anon-key
```

Release builds require GitHub Actions secrets with these two names. The same project also holds ExamTrack's mistake data and mistake attachments, since ExamTrack was merged into Focal, so one account and one sign-in cover study sessions and mistake review.

## Use

Open a notebook and tap the **Focal** chip beside the exam and stopwatch timers. It shows the current reading, writing, or paused state and whether the record is saved locally, waiting to sync, syncing, synced, or needs attention. You can also connect Focal in **Settings → Account & updates → Open study sessions**. Choose a subject and start ordinary study; pause or resume, then save with optional notes and a confidence score. **Log study without a timer** records past work by duration. The subject is suggested from the notebook's exam metadata or title. Focal built-in IDs match exactly, and signed-in users can choose their Focal custom subjects. The panel shows recent sessions, today's completed study minutes, a pending count, and a manual retry.

Shared active sessions from other apps remain individually controllable, but they do not prevent starting a new Folio study timer. The panel can expand beyond the first six shared sessions. When several shared sessions are paused, **Finish paused** or **Discard paused** resolves them together after confirmation.

Starting an exam timer immediately creates an in-progress exam practice session. Folio checkpoints that same record locally every 30 seconds, publishes phase and pause changes, then completes it when the exam ends or is stopped. Its active writing seconds exclude reading time and timer pauses. Stopping during reading with no writing removes the provisional Focal record. Folio's existing mark and attempt recording remains separate.

Sessions and the ordered mutation outbox live in the atomic `files/focal-study.json` cache, so recording does not require a network connection or account. Lifecycle commands keep their mutation IDs and ordering across retries. Folio captures `elapsed_since_previous_ms` with `SystemClock.elapsedRealtime()` and persists its per-session boundary and boot count; when available, a server-clock estimate supplies `occurred_at`. If the process restarts, ordinary focus recovery pauses at the last durable checkpoint. If the device reboots, elapsed-realtime continuity is discarded rather than guessed from wall time. The Supabase Auth session is shared with mistake review and stored encrypted in Android Keystore-backed `noBackupFilesDir`. Unsigned records are assigned to the account used when they first sync; signing out hides the prior account's records without deleting them locally.

Starting or changing an exam/focus session queues canonical lifecycle commands; progress checkpoints remain local rather than creating duplicate feed rows. Canonical segments and accumulated active milliseconds are authoritative on every client. The mutation RPC provides idempotent receipts, stale commands rebase only when the requested transition remains valid, and terminal remote states clear obsolete queued commands. Realtime wakes Folio to pull the cursor feed; it also pulls after reconnect and every 30 seconds. Folio retains notebook identity while merging remote sessions. Planned calendar rows do not block a new focus session, and sync status reflects the actual queue and connection. Failed uploads remain queued locally and retry while the app is open.

Run `./gradlew :app:assembleDebug :app:lintDebug` to validate. A live end-to-end sync check requires the Focal URL/key, a test account, and the Focal migration state above.
