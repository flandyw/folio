# Focal sync and session reliability

## Progress and Planner sync (15 November 2026)

`ExamProgressManager` (exam log, mistakes, SACs, checklist, goals, events, timetable) was upgraded from poll-only single-RPC sync to batched, live-updating sync:

- **Batched outbox:** Queued changes send to `sync_apply_changes` in batches of 50, folded via pure `ProgressCache.settle`. Content refusals (HTTP 4xx except 401/403/408/429) retry individually so one bad row never blocks the queue; transport, auth, and 5xx errors pause for later retry.
- **Non-blocking saves:** The lock holds only during cache updates. Edits saved mid-sync adopt the receipt's sequence as `expectedSeq` for the follow-up pass instead of self-colliding. Calling `sync()` mid-pass queues exactly one follow-up pass.
- **Realtime + fallback:** While Progress or Planner is visible, `keepFresh()` subscribes to `sync_log` inserts via shared `watchFocalFeed` (also used by Study and Mistakes) with a 30-second fallback poll.
- **Pending protection & conflicts:** `ProgressCache.absorb` preserves local rows with queued edits, and Planner exposes *Use Focal's* / *Keep mine* conflict resolution directly (including events and timetable rows).

Covered by `ProgressSyncTest` (batch receipts, mid-flight edits, collisions, echoes, snapshots). Device check: burst edits offline then online, edit in Focal while Planner is open (arrives within 1–2 s), and edit the same SAC list twice quickly.

## Account recovery hardening (16 October 2026)

- Focal's sign-out now uses `scope: "local"` instead of Supabase's global default so signing out of Focal does not revoke Folio's session.
- Folio owns a single serialised refresh loop in `ExamTrackSessionLifecycle` with SDK 3.0.3's automatic session management disabled (SDK 3.0.3 deletes sessions on *any* non-5xx refresh error, including 408/429). Folio retries transport, rate-limit, and 5xx errors with bounded backoff, and prompts **Sign in again** only on HTTP 400/401/422.
- RPCs and attachment downloads share this owner, retry 401 once after refresh, and surface a second rejection as a sync error without spinning.
- Token rotation serialises with sign-in/sign-out, commits independently of screen cancellation, and retries persisting a rotated token before resuming sync. Encrypted storage path, Keystore alias, and format stay unchanged; unreadable files are preserved rather than wiped.
- Study, Mistakes, Progress, and the shared account panel show unified states: **Sign in again** (revoked session, cached offline data preserved), **Reconnecting** (transient refresh error), and explicit sign-out (hides account while keeping durable caches/outboxes).

## Earlier investigation (11 October 2026)

Investigated recurring *"Checking Focal for session changes failed"* errors that recovered after sign-out/sign-in:

- `FocalStudyManager` calls Supabase's `sync_read_changes` RPC directly via `SupabaseSyncRemote` over the single process-wide `FocalSupabaseConnection` (no competing Folio clients). Normal frontend deploys or APK updates preserve logins.
- **Client fixes applied:**
  - Wait up to 15 s for an unexpired session during SDK `RefreshFailure` instead of treating `currentUserOrNull() == null` as an expired account; wait for token recovery before syncing restored expired sessions.
  - Move Study sync to a dedicated worker with a conflated wakeup channel so auth/foreground `collectLatest` no longer cancels in-flight RPCs; tie Realtime subscriptions to account/foreground state rather than token refreshes; cancel startup refresh on explicit sign-in/out.
  - Retry transient feed reads (transport, 408/429/5xx) twice at 750 ms / 1500 ms; fold sessions and custom subjects into one feed pass; skip background polling for accounts with no pending work.
  - Classify HTTP auth, permission, missing backend, rate-limit, 5xx, timeout, and transport errors accurately without exposing raw SDK internals, headers, or tokens.
- **Supabase notes:** Verify whether **single session per user** is enabled in [Supabase session policies](https://supabase.com/docs/guides/auth/sessions) if Folio and Focal must stay signed in concurrently, and review [Postgres timeouts](https://supabase.com/docs/guides/database/postgres/timeouts).

## Verification

- **Automated:** `python3 tools/focal-sync-smoke.py` (loopback HTTP test of transient/permanent errors, expired sessions, rate-limited refresh, concurrent 401s, rotation write failures, revoked sessions, and sign-out races), `node tools/focal-study-smoke.cjs`, `ProgressSyncTest`, and `./build.sh -p`.
- **On device:** Leave Folio signed in across token expiry and sleep/wake; toggle Wi-Fi; edit concurrently in Focal; retry from Study and Mistakes; sign out during a pending refresh; and switch accounts (confirming old-account queued work never uploads to the new account). Record exact error text and timestamps to correlate with Supabase logs if issues recur.
