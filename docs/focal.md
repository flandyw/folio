# Focal study sessions

Folio records study sessions locally and sends them to the existing Focal Supabase project. It reads and writes through Focal's `sync_changes` compatibility view and, while Folio is open, subscribes to `sync_log` changes from Focal's v3 sync migration for prompt updates. No Focal SQL changes are needed when that migration is installed.

## Configure

Use Focal's `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` as Folio's values below. Supply them through environment variables, Gradle properties, or ignored `local.properties`, in that order. Only HTTPS URLs and publishable or anon client keys are accepted. Credentials are placeholders in a local build when these values are absent, and the app keeps recording sessions locally.

```properties
FOCAL_SUPABASE_URL=https://your-project.supabase.co
FOCAL_SUPABASE_PUBLISHABLE_KEY=your-focal-publishable-or-anon-key
```

Release builds require GitHub Actions secrets with these two names. They may refer to a different Supabase project and account than ExamTrack.

## Use

Open a notebook and tap the **Focal** chip beside the exam and stopwatch timers. It shows the current reading, writing, or paused state and whether the record is saved locally, waiting to sync, syncing, synced, or needs attention. You can also connect Focal in **Settings → Account & updates → Open study sessions**. Choose a subject and start ordinary study; pause or resume, then save with optional notes and a confidence score. **Log study without a timer** records past work by duration. The subject is suggested from the notebook's exam metadata or title. Focal built-in IDs match exactly, and signed-in users can choose their Focal custom subjects. The panel shows recent sessions, today's completed study minutes, a pending count, and a manual retry.

Shared active sessions from other apps remain individually controllable, but they do not prevent starting a new Folio study timer. The panel can expand beyond the first six shared sessions. When several shared sessions are paused, **Finish paused** or **Discard paused** resolves them together after confirmation.

Starting an exam timer immediately creates an in-progress exam practice session. Folio checkpoints that same record locally every 30 seconds, publishes phase and pause changes, then completes it when the exam ends or is stopped. Its active writing seconds exclude reading time and timer pauses. Stopping during reading with no writing removes the provisional Focal record. Folio's existing mark and attempt recording remains separate.

Sessions live in `files/focal-study.json`, written atomically, and are available without a network connection or account. The active ordinary session is also stored there; after process death it reopens paused so an unseen gap does not count. The Focal Auth session uses a separate Android Keystore key and ciphertext in `noBackupFilesDir`, never in the session log or a notebook archive. Sign-out hides records belonging to the previous Focal account while retaining them locally. Unsigned records are assigned to the account used when they first sync.

Uploads insert one immutable terminal `put` change with a stable change UUID and one row UUID per sitting into `public.sync_changes`; an explicitly discarded, previously uploaded session sends a `delete` change. The first active checkpoint is uploaded once so the Focal macOS app can show that a session is currently in progress. While running, the macOS client can derive elapsed time from the open interval's `start` timestamp, so Folio does not send periodic timer checkpoints. Pause and resume boundaries are uploaded explicitly, and the final update uses the same `row_id`. This prevents a long exam or a frequently paused focus session from becoming dozens of apparent sessions in clients that consume the append-only log without collapsing by `row_id`. Focal's receipt trigger makes retries idempotent; Focal then pulls the same schema version 2 payload as its own study sessions. Supabase Auth and row-level security authorize access. Both apps can pause, resume, finish, or discard shared sessions. A `sync_log` notification wakes Folio to pull the authoritative row immediately; it also pulls after a Realtime reconnect and every 30 seconds as a fallback. Pending local changes survive older server echoes; remote termination stops active checkpoints from resurrecting a session. Folio retains local notebook identity when merging remote updates. Recovery publishes a paused checkpoint, and planned calendar rows do not block a new focus session. Sync status reflects the actual queue and connection, independently of whether a timer is active. Failed uploads remain queued locally and retry while the app is open.

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` to validate. A live end-to-end sync check requires the Focal URL/key, a test account, and the Focal migration state above.
