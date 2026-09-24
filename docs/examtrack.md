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
3. **Today** opens a focused dashboard. Choose 5, 10 or all due questions, optionally shuffle, then start a session. Unfinished questions have a **Continue** action that reuses their existing handwriting page.
4. **Library** searches question text, corrections, explanations, topics, subjects, titles and categories. Due/upcoming/suspended tabs and the **Filters** sheet narrow the list. **Review matching due questions** starts a session within those filters; Today always reviews the complete due queue.
5. Open a question for separate **Question**, **Solution**, and **Attempts** tabs. Images open in a full-screen viewer with pinch, pan, zoom buttons and reset. Unfinished attempts can be resumed; completed attempts open their saved notebook.
6. Landscape tablets keep the library and selected question side by side, with independent scrolling and a highlighted selection. Portrait tablets show a two-column library; the Today dashboard uses two or three columns as space allows. During review, landscape places the reference beside the handwriting canvas, while portrait keeps a full-width canvas below an expandable question panel. The panel’s **Adjust question panel and text size** control changes its share of space, remembering portrait and landscape sizes separately. Layout follows the available window, including split-screen. Stuck on a question? **Compare with your last attempt** (history button or the prompt under the question) opens earlier handwriting read-only while the current page keeps saving. Double-tap **Compare answer**, then choose Again, Hard, Good or Easy — each shows its next interval. Double-tapping **Skip** moves the question to the end and reuses its saved page when it returns.
7. Ratings save locally and queue for sync. Session progress counts completed questions, and the final rating opens a completion summary. Due counts refresh while the screen is open.

**Handwriting** lists every practice notebook on this device, with no 20-page cap, and remains accessible when signed out. The header shows total device space used, with per-notebook size, page count and review status. Delete one page, empty pages only, or everything; empty notebooks are removed automatically and untouched pages are never saved to disk. Open a notebook to view or export it. These device-owned pages include work from previous accounts; the question list and attempt counts remain scoped to the signed-in account. **Account and sync** in the top bar opens connection information, manual sync and sign-out. Offline or pending-upload notices remain visible without pushing account controls ahead of study content.

## Text and maths rendering

During review, **Adjust question panel and text size** opens the panel-space and text-size controls. Text size ranges from 75% to 200%, applies to the question, maths and revealed answer, and is saved on this device for future reviews. **Compare answer** and both **Skip** buttons require two taps on the same control within one second; a single tap expires without performing the action.

Question, correction and explanation use native Compose Markdown-lite with `$…$`, `$$…$$`, `\(…\)` and `\[…\]` math. `MistakeRichText.kt` only segments text, code, Markdown and opaque math source. It preserves the exact contents of paired delimiters; escaped currency and unclosed delimiters stay readable. Plain-text previews and accessibility alternatives keep the raw LaTeX rather than interpreting TeX.

`math/KaTeXMath.kt` provides a reusable `KaTeXMath` block and an `InlineTextContent` adapter. `math/KaTeXWebView.kt` loads one small local HTML/JS shell from `app/src/main/assets/katex/`. KaTeX 0.18.7, its CSS, every referenced font and its MIT license are bundled; `VERSION.txt` records the upstream package and SHA-512 integrity. Nothing is downloaded at runtime. `\Pr`, fractions, roots, sums, integrals, binomials, matrices, cases and other supported commands are handled entirely by KaTeX.

Rendering is demand-driven by Compose (including the existing lazy card list). A global two-slot pool bounds live rendering WebViews and reuses warmed shells: the first miss loads the local page, later misses reuse it, so each pays only for layout plus capture. Rich text prewarms one shell while native prose lays out. A cache miss briefly attaches a pooled transparent WebView, waits for local fonts, measures the KaTeX DOM and captures its pixels, then detaches it back to the pool; completed formulas use native images and dead renderers are destroyed, never reused. Completion is polled every 8 ms after an immediate check. A 16 MiB LRU stores only bitmaps, keyed by exact source, display mode, half-pixel-quantized font size, density and theme color, plus a versioned 32 MiB disk cache so repeated questions need no WebView at all. Unchanged recompositions do not render again. Evicted bitmaps are not recycled while visible; neither the caches nor the pool retains an Activity. Pathological output above four million pixels falls back to native source rather than allocating an unbounded image.

Normal prose never enters HTML. Inline images sit inside native text with text-center alignment; this is an approximate baseline, not a TeX baseline shared with Compose. A formula is indivisible and may wrap to the next text line; very wide fragments scroll horizontally. Display blocks are centered and scroll horizontally at natural size. Transparent pixels and surrounding text size/color support light/dark themes and font scaling. A cold render initially shows raw source and may shift line wrapping when its measured image arrives. Screen readers receive raw LaTeX; image output is not selectable MathML.

The shell uses `throwOnError: false`, `strict: false`, `trust: false` and bounded expansion/size. Parse errors retain readable source. A renderer failure or timeout leaves native source visible. JavaScript receives JSON, never interpolated HTML. There is no JavaScript interface. File/content access, network loads and navigation are blocked; only exact local shell/KaTeX/font URLs on a synthetic HTTPS origin are served. A content security policy also denies connections, images and external scripts.

Rendering-heavy detail, attachment and review components are in `MistakeDetailScreen.kt` and `MistakeReviewScreen.kt`; the mistake model and backend remain data/sync responsibilities.

Run `node tools/katex-smoke.cjs` to check bundled assets and representative expressions. `KaTeXRenderingTests` exercises the real Android WebView shell and bitmap capture; run it on an isolated emulator/device. No Android device/emulator was connected during this implementation, so on-device visual/scroll testing remains required. In airplane mode, check mixed prose and `\Pr`, a wide display sum, fractions/matrices/cases, malformed source, light/dark themes, larger font settings, and repeated scroll-away/back. Verify no clipped tall formulas or lingering WebViews.

## Authentication and isolation

Email/password matches `src/lib/sync.ts` in ExamTrack. Supabase owns token exchange and refresh. Its session manager writes AES-GCM ciphertext using an Android Keystore key, under `noBackupFilesDir`. Passwords are held only for the login request; tokens are neither logged nor exposed to Compose. SDK logging is disabled. No authentication material enters notebook files, cache JSON, Android backups or `.folio` archives.

Startup restores the saved account and cache without requiring network access. The SDK refreshes expiring tokens and retries temporary refresh failures. Folio explicitly owns the startup refresh coroutine so an expired-session startup retry in SDK 3.0.3 is cancelled by sign-out. An invalid session hides the account cache and requires sign-in again. Sign-out clears the local SDK session, stops refresh and cancels sync; it works offline and does not log the user out of ExamTrack on other devices. It does not delete handwriting or the account-partitioned offline cache.

Every table request includes a `user_id` filter and checks the SDK's current user. The existing `(user_id, id)` identity and `auth.uid() = user_id` RLS policies remain the security boundary. Local caches and attachment directories are partitioned by Supabase user ID; switching accounts starts with an empty visible cloud list before loading that account's cache.

## Downloads, offline storage and conflicts

Sync runs on resume, reconnect and every 30 seconds while Folio is in the foreground. Starting the next practice page resumes any sync cancelled for its local write, so queued ratings are not stranded. Account and sync, and the mistakes list, distinguish authentication, permission, backend, timeout and connection failures; raw SDK messages and request headers are never displayed. Local builds must provide the same public Supabase URL/key as ExamTrack in ignored `local.properties` (mapped from its `VITE_SUPABASE_URL` and `VITE_SUPABASE_PUBLISHABLE_KEY`); placeholder builds cannot connect to a real account.

`MistakeRepository` has a versioned, atomic cache at `files/examtrack/<user-id>/cache.json`. It contains original mistake payloads, pending IDs, tombstones, source-attempt context, local page references and last sync time. It contains no tokens. `mistakes` and `attempts` downloads are paginated in stable ID order. Only attempt subject/title/paper context is retained; no unrelated tables are accessed. Invalid individual mistake payloads are counted and skipped.

The codec tolerates absent older scheduling fields and retains the entire original payload. Pure Kotlin scheduling ports `getMistakeSchedule`, `previewMistakeReview`, `recordMistakeReview` and `getDueMistakes`, including legacy incorrect/assisted/correct history. TypeScript-generated fixtures verify exact parity.

Sync follows ExamTrack's timestamp precedence: a newer remote row replaces the older local mistake; otherwise the local scheduled review can upload. A newer remote edit can therefore supersede an offline review, just as in ExamTrack's last-writer-wins merge. The handwritten attempt remains locally with its review ID/rating even in that conflict. Folio never automatically restores a remotely tombstoned mistake, including when an offline rating is newer than deletion. An explicit later active remote version can supersede an already-cached tombstone.

Deleting a card in Folio (mid-review or from its details) removes it locally at once, drops any queued rating, keeps handwriting on this device, and queues a conditional remote `deleted_at` write for the next sync. The same write sets `payload` to null, as required by ExamTrack's `mistakes_deleted_payload` constraint. A queued delete is never resurrected by the still-active remote row; a conflicting concurrent web edit keeps the delete queued for a later sync.

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
