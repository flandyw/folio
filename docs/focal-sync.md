# Focal session-check reliability

Investigated 11 October 2026 after repeated “Checking Focal for session changes failed”
messages, with the generic server/account advice, recovering after sign-out/sign-in.

## What that message checks

`FocalStudyManager` calls the configured Supabase project's `sync_read_changes` RPC
directly through `SupabaseSyncRemote`. The Focal web/desktop frontend is not an
intermediate server. The SQL implementation and migrations belong to `~/focal`.
Study, Mistakes, Progress and attachment storage use the same process-wide
`FocalSupabaseConnection`, encrypted session and account. There is no second Folio
client competing to rotate the same saved refresh token.

An ordinary frontend deployment or APK update preserves the login. Changing the
Supabase project, signing keys, session policies, credentials or RPC contract can
affect it. Sign-out/sign-in replaces the session, so recovery that way suggests
token trouble, but the old generic message cannot establish the actual HTTP error.

## Confirmed client problems and changes

- SDK 3.0.3's `currentUserOrNull()` returns null during `RefreshFailure`, while the
  SDK is still retrying. Study treated that null as an expired account. The shared
  RPC boundary now waits up to 15 seconds for a usable, unexpired session; a real
  sign-out or different account fails immediately. It does not launch a competing
  refresh exchange. Mistakes no longer performs a redundant immediate-user check;
  authenticated attachment downloads use the same recovery guard.
- Restoring an expired saved session exposes its identity before network refresh.
  Sync now waits for token recovery rather than sending that expired token.
- Auth/foreground `collectLatest` previously owned the sync RPC, so changing either
  could cancel an in-flight pull or publication. A separate worker now owns Study
  sync, and a conflated channel combines wakeups. Realtime subscriptions follow
  account/foreground changes, rather than restarting on every token refresh.
- Study's sign-in/sign-up/sign-out now cancels the owned startup-refresh job,
  matching the existing Mistakes account actions, so an old restore cannot race
  the replacement login.
- A transient feed read previously failed visibly on its first attempt. Reads now
  retry transport errors and HTTP 408/429/5xx twice, after 750/1500 ms. Permanent
  refusals and cancellation do not retry. Writes retain their existing durable
  mutation/receipt handling.
- Every idle Study sync previously read sessions twice and subjects separately.
  Sessions and custom subjects now consume one feed pass; confirmation runs only
  when there were local sessions to publish. Both existing cursor fields are
  checkpointed together, starting at the older cursor on the first shared pass.
  Background polling skips accounts with no pending session work.
- Study and Progress now distinguish HTTP authentication, permissions, missing backend,
  rate limits, server failures, request timeouts and transport failures. SDK 3.0.3
  wraps some network failures in `HttpRequestException` without keeping the cause;
  that previously produced the misleading server/account advice. Raw SDK messages,
  request headers, tokens and payloads are never shown by this formatter.

## Focal and Supabase surfaces checked

Focal's web/desktop clients use the same account-pinned feed and session mutation
RPCs. Its desktop session storage serializes operations; the browser and desktop
builds avoid creating two clients. Its command outbox already holds authentication
failures and resumes on token refresh. No Focal frontend change was needed.

The migration chain defines authenticated execute grants, owner checks, RLS,
`sync_log` Realtime publication and indexes on `(user_id, seq)` and
`(user_id, entity, row_id, seq desc)`. Account-pinned RPC overloads were added in
migration 0013; old overloads remain for compatibility. A migration can still
cause errors if deployed incorrectly, but frontend deployment alone does not
change these functions.

Using Folio's configured public key, the live Auth settings endpoint returned 200.
The live account-pinned and legacy `sync_read_changes` / `sync_apply_changes` RPCs
were resolved and denied anonymous execution with SQLSTATE 42501, as expected.
These probes wrote no user data. This verifies endpoint/key acceptance and RPC
availability, not authenticated permissions, complete migration parity or latency.

No Supabase management token or attached Android device was available. The CLI
reported `AccessTokenRequiredError`. Dashboard browser access was unavailable due
to the host's AppArmor browser restriction. Authenticated server logs, active
session policies, signing-key history and query timings therefore remain unchecked.
In particular, check whether **single session per user** is enabled when Folio and
Focal should remain signed in together; that policy can invalidate the other login.
Also inspect inactivity/time-box limits and refresh failures before changing them.
See [Supabase session policies](https://supabase.com/docs/guides/auth/sessions) and
[database timeouts](https://supabase.com/docs/guides/database/postgres/timeouts).

## Verification

The canonical signed/published build is `./build.sh -p`. The existing
`node tools/focal-study-smoke.cjs` checks timer intervals and restore behavior.
`python3 tools/focal-sync-smoke.py` compiles the actual shared RPC/error sources against cached
SDK 3.0.3 and uses a loopback HTTP server: transient/permanent failures, expired
session recovery, `RefreshFailure` recovery, account mismatch, sign-out,
cancellation and safe error classification. Focal's `bun run check` checks its
unchanged frontend types and lint.

These checks passed, and `./build.sh -p` built, signature-verified and published
Folio **4.0.7-exp.1** (version code 4070001). Focal's check completed with five
warnings in unchanged code. The tablet scenarios below remain manual checks.

On the tablet, leave Folio signed in across token expiry and sleep/wake; disconnect
and restore Wi-Fi; use Focal concurrently; retry from Study and Mistakes; and sign
out while refresh is pending, then sign in to the same and a different account.
Verify local timers/reviews remain saved, reconnect without repeated manual login,
custom subjects still arrive/delete, and old-account work never uploads to the new
account. If a failure persists, record the new specific HTTP/status wording and
timestamp to match it to Supabase Auth/PostgREST logs.
