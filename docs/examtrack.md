# ExamTrack mistake review

Folio uses ExamTrack's existing Supabase project. Verified against ExamTrack upstream `289e39ef4d8d002117d7f35424bf035df686edfb` (16 Sep 2026). There are **no SQL changes**, new tables, service-role credentials, or additional cloud databases.

## Setup

Configure these values using environment variables, Gradle properties, or untracked `local.properties`, in that order. CI builds without credentials use safe placeholders; release builds inject GitHub Actions secrets. Never commit real keys.

```properties
EXAMTRACK_SUPABASE_URL=https://your-project.supabase.co
EXAMTRACK_SUPABASE_PUBLISHABLE_KEY=your-publishable-or-anon-key
```

```sh
# Store production credentials (actions secrets, used by CI/release only):
gh secret set EXAMTRACK_SUPABASE_URL --app actions
gh secret set EXAMTRACK_SUPABASE_PUBLISHABLE_KEY --app actions
```

Only HTTPS and publishable/anon keys are accepted. The build rejects secret keys and JWT keys without the `anon` role. Public keys identify the project; Supabase Auth and RLS authorize access.

The native library is [supabase-kt 3.0.3](https://github.com/supabase-community/supabase-kt/tree/3.0.3), using Auth, Postgrest and Storage with Ktor OkHttp 3.0.3. These versions compile with Folio's Kotlin 2.0.21 toolchain. JSON uses the existing `org.json` persistence conventions, with SDK `JsonObject` values at the network boundary.

## Use

1. Open **Mistakes** from the library, or **Settings → Account → ExamTrack**.
2. Sign in with the same email/password as ExamTrack's sync account. Account creation and password recovery remain in ExamTrack.
3. Open **Review due**, read the question and saved images, and write in the normal Folio editor.
4. Tap **Reveal answer**, compare the correction, and choose Again, Hard, Good or Easy — each shows its next interval before you commit.
5. The rating and page reference are saved locally, the rating upload is queued, and the next due mistake opens.

The overview searches questions, subjects and categories and filters by subject, category, due/upcoming and suspended state, with counts on every chip. A mistake's detail renders its full question, attachments, marks/category metadata, an expandable correction, and a timeline of handwritten attempts. Unfinished attempts can be resumed. **Saved handwriting on this device** exposes practice notebooks for viewing and `.folio`/PDF export, including after sign-out or a cloud deletion. This is deliberately device-owned work, separate from the signed-in account's cloud list.

## Text and maths rendering

Question, correction and explanation render offline as Markdown-lite with LaTeX math (`$…$`, `$$…$$`, `\(…\)`, `\[…\]`). Supported Markdown: **bold**, *italic*, `code`, headings, bullets, numbered lists, quotes and dividers. Supported LaTeX: `\frac`, `\sqrt`, `^`/`_` super/subscripts, `\text`, Greek letters and common operators/functions (`\times`, `\le`, `\sin`…). Unknown commands fall back to readable text so content is never dropped; malformed input renders as plain text. See `MistakeRichText.kt` (pure parser) and `MistakeRichTextUi.kt` (Compose renderer), covered by `RichTextParserTests`.

## Authentication and isolation

Email/password matches `src/lib/sync.ts` in ExamTrack. Supabase owns token exchange and refresh. Its session manager writes AES-GCM ciphertext using an Android Keystore key, under `noBackupFilesDir`. Passwords are held only for the login request; tokens are neither logged nor exposed to Compose. SDK logging is disabled. No authentication material enters notebook files, cache JSON, Android backups or `.folio` archives.

Startup restores the saved account and cache without requiring network access. The SDK refreshes expiring tokens and retries temporary refresh failures. Folio explicitly owns the startup refresh coroutine so an expired-session startup retry in SDK 3.0.3 is cancelled by sign-out. An invalid session hides the account cache and requires sign-in again. Sign-out clears the local SDK session, stops refresh and cancels sync; it works offline and does not log the user out of ExamTrack on other devices. It does not delete handwriting or the account-partitioned offline cache.

Every table request includes a `user_id` filter and checks the SDK's current user. The existing `(user_id, id)` identity and `auth.uid() = user_id` RLS policies remain the security boundary. Local caches and attachment directories are partitioned by Supabase user ID; switching accounts starts with an empty visible cloud list before loading that account's cache.

## Downloads, offline storage and conflicts

`MistakeRepository` has a versioned, atomic cache at `files/examtrack/<user-id>/cache.json`. It contains original mistake payloads, pending IDs, tombstones, source-attempt context, local page references and last sync time. It contains no tokens. `mistakes` and `attempts` downloads are paginated in stable ID order. Only attempt subject/title/paper context is retained; no unrelated tables are accessed. Invalid individual mistake payloads are counted and skipped.

The codec tolerates absent older scheduling fields and retains the entire original payload. Pure Kotlin scheduling ports `getMistakeSchedule`, `previewMistakeReview`, `recordMistakeReview` and `getDueMistakes`, including legacy incorrect/assisted/correct history. TypeScript-generated fixtures verify exact parity.

Sync follows ExamTrack's timestamp precedence: a newer remote row replaces the older local mistake; otherwise the local scheduled review can upload. A newer remote edit can therefore supersede an offline review, just as in ExamTrack's last-writer-wins merge. The handwritten attempt remains locally with its review ID/rating even in that conflict. Folio never automatically restores a remotely tombstoned mistake, including when an offline rating is newer than deletion. An explicit later active remote version can supersede an already-cached tombstone.

Before upload, Folio reads remote rows and applies **only scheduling fields** to the latest remote payload, preserving current question content, attachments, suspended state and unknown fields. It uses a conditional PATCH of the existing row matching `user_id`, `id`, `updated_at` and `deleted_at IS NULL`. This is intentionally safer than an unconditional upsert for existing mistakes: a concurrent web edit/deletion returns no updated row and leaves the operation queued for another sync. Missing/malformed rows are never recreated. The existing backend has no cross-client transaction/RPC, so a web write after Folio's successful update can still win, consistent with ExamTrack's timestamp model.

Reviews save locally before any upload. Pending operations survive process restarts. Sync runs after login, opening Mistakes, foreground/resume, network availability, manual Sync now and a rating. Requests are debounced, overlapping syncs are suppressed, and local rating saves cancel a slow network sync. No background polling or requirement to be online when opening the screen.

## Attachments

JPEG, PNG, WebP and GIF are downloaded through the SDK's authenticated Storage API from the **private** `mistake-attachments` bucket. Paths must start with the current user's ID, matching ExamTrack's `<user>/<mistake>/<attachment>.<ext>` convention and Storage policy. No public or permanent URLs are produced. Original bytes are atomically cached in user-scoped, hashed-path files for offline reuse; Coil decodes/displays them, including GIFs. These derived image caches are not backed up.

## Handwriting and migration

Each practice creates a genuine hidden Folio notebook with an infinite maths-grid `NotePage`. This keeps every review independent and supports adding more pages with the normal editor. The entire existing `EditorScreen` is reused: `InkView`, pressure, pen/highlighter, eraser, lasso, undo/redo, tool preferences and viewport. There is no alternate canvas and no handwriting grading.

Notebook metadata version **6** adds `mistakePractice` and `mistakeReviews`; versions 1–5 still load and migrate using the existing storage path. Ink remains in the normal split page files. `mistakeReviews` contains user/mistake/review IDs and notebook/page references, completion time and rating. The same review ID is used in the ExamTrack `reviewHistory` entry. These Folio-only fields never enter the cloud payload.

Notebook `.folio` archives include the local review metadata and all normal page contents. Import remaps the notebook reference to the new notebook ID while preserving page/review IDs. The existing portable archive format remains backward compatible through optional fields. Android backup includes the mistake cache and notebook directories, but excludes the encrypted session. Cache-to-notebook completion recovery repairs an interruption between the two durable writes when Mistakes next opens.

## Validation

```sh
ANDROID_HOME=/path/to/android/sdk ./gradlew \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
# With an isolated test device/emulator connected:
./gradlew :app:connectedDebugAndroidTest
# Regenerate the 320 scheduler cases from the actual ExamTrack checkout:
node tools/examtrack-scheduler-fixtures.cjs ../examtrack
```

Tests cover payload/unknown-field preservation, all scheduler states/ratings, legacy migration, due sorting, attachments, downloads, offline upload recovery, conflicts/tombstones, malformed rows, account isolation, signed-out SDK requests, pagination, conditional authenticated writes, independent attempt pages and portable backups. Device tests cover Keystore persistence and actual split-page storage.

Limitations: no real-account/password or connected-device end-to-end run was performed during implementation. On-device pen interaction, Keystore behavior and live RLS/storage authorization still need the smoke test below. Local linking to an imported exam/PeekAnchor, JSON import, account registration and background scheduled sync are not included.

### Device smoke test

Use an isolated test install. Sign in with an existing ExamTrack account, verify counts and a private image, then enable airplane mode. Restart Folio, review a mistake with several strokes, reveal/rate, and inspect the saved attempt. Restore connectivity and verify the same rating/review ID and due date in ExamTrack. Review again and verify both Folio pages remain. Edit/delete a card in ExamTrack, sync Folio, and verify update/deletion. Sign out, sign in as a second test account, and confirm its cloud list is separate. Export/import a practice `.folio` and verify ink and review references. Never run the session instrumentation test against a student's signed-in installation.
