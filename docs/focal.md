# Focal study sessions

Folio records study sessions locally and sends them to the existing Focal Supabase project. This targets Focal's current `sync_changes` log from migration `0004_rebuild_sync_as_change_log.sql`, not the removed `study_sessions` table. No Focal SQL changes are needed.

## Configure

Use Focal's `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY` as Folio's values below. Supply them through environment variables, Gradle properties, or ignored `local.properties`, in that order. Only HTTPS URLs and publishable or anon client keys are accepted. Credentials are placeholders in a local build when these values are absent, and the app keeps recording sessions locally.

```properties
FOCAL_SUPABASE_URL=https://your-project.supabase.co
FOCAL_SUPABASE_PUBLISHABLE_KEY=your-focal-publishable-or-anon-key
```

Release builds require GitHub Actions secrets with these two names. They may refer to a different Supabase project and account than ExamTrack.

## Use

Open a notebook and tap the **Focal** chip beside the exam and stopwatch timers. It shows the current reading, writing, or paused state and whether the record is saved locally, waiting to sync, syncing, synced, or needs attention. You can also connect Focal in **Settings → Account & updates → Open study sessions**. Choose a subject and start ordinary study; pause or resume, then save with optional notes and a confidence score. **Log study without a timer** records past work by duration. The subject is suggested from the notebook's exam metadata or title. Focal built-in IDs match exactly, and signed-in users can choose their Focal custom subjects. The panel shows recent sessions, today's completed study minutes, a pending count, and a manual retry.

Starting an exam timer immediately creates an in-progress exam practice session. Folio updates that same Focal record every 30 seconds while the timer runs and when its phase or pause state changes, then completes it when the exam ends or is stopped. Its active writing seconds exclude reading time and timer pauses. Stopping during reading with no writing removes the provisional Focal record. Folio's existing mark and attempt recording remains separate.

Sessions live in `files/focal-study.json`, written atomically, and are available without a network connection or account. The active ordinary session is also stored there; after process death it reopens paused so an unseen gap does not count. The Focal Auth session uses a separate Android Keystore key and ciphertext in `noBackupFilesDir`, never in the session log or a notebook archive. Sign-out hides records belonging to the previous Focal account while retaining them locally. Unsigned records are assigned to the account used when they first sync.

Uploads insert immutable `put` changes with stable change UUIDs and one row UUID per sitting into `public.sync_changes`; a provisional session stopped before writing sends a `delete` change. Focal's receipt trigger makes retries idempotent; Focal then pulls the same schema version 2 payload as its own study sessions. Supabase Auth and the table's `auth.uid() = user_id` RLS policy authorize access. Folio only uploads its own sessions and reads custom subject changes. Failed uploads remain queued locally and retry while the app is open.

Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` to validate. A live end-to-end sync check requires the Focal URL/key, a test account, and the Focal migration state above.
