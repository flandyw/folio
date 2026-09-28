# Sync

Folio uses the shared Focal Supabase project and follows the contract in
[`focal/docs/sync-protocol.md`](../../focal/docs/sync-protocol.md). Supabase Auth identifies
the account; Realtime is only a wakeup, while `sync_read_changes` and its cursor are the
durable source of updates.

## Study sessions

Canonical sessions live in `study_sessions`, with active intervals in
`study_session_segments`. Folio queues lifecycle mutations locally and publishes them through
`study_session_mutate`; it never writes session records through `sync_apply_changes`, direct
table CAS, or the compatibility view. Commands retain stable mutation IDs and order until a
receipt or a safe stale-state reconciliation is received. Terminal sessions cannot be reopened.

Timing uses `SystemClock.elapsedRealtime()` deltas and a persisted boot count, plus a
server-clock estimate when available. Device wall-clock time and reconnect receipt time are
not used to infer offline duration. A process restart recovers an ordinary focus timer as
paused at its last durable checkpoint; a device reboot breaks elapsed-realtime continuity.

A publish is acknowledged from the canonical session `study_session_mutate` returns: the entry
takes the server revision, canonical payload and the change id the feed will echo, and every
mutation id the device sent is remembered. A pull therefore never reads this device's own write
back as another device's change. The "changed on another device" notice is also reported once
per (session, change id), so a retried sync cannot repeat the same remote edit.

## Mistakes and attempts

The mistake cache is account-scoped and stored atomically under
`files/examtrack/<user-id>/cache.json`. It includes a cursor, per-row feed sequences and a
persistent mutation outbox. Folio reads shared `mistakes` and ExamTrack `attempts` through
`sync_read_changes`; queued mistake edits use `sync_apply_changes` with `expected_seq`.
Folio's notebook practice attempts remain local. A stale scheduling update
is rebased against the current remote row while retaining remote question content; review
history is merged by stable review ID. Missing/deleted remote mistakes are not recreated by a
stale local edit. Attempts and notebook references remain local data; only the supported
shared entities are synchronized.

Sync resumes after sign-in, foreground/network changes, ratings, manual retry and Realtime
wakeup. Local edits are saved before network work and remain queued after failures. Cache and
session data are isolated by account ID; sign-out hides the previous account's data without
purging it.

### RPC parameter typing

`postgrest.rpc` arguments are encoded by kotlinx.serialization using the argument's static
type. A parameter map whose values are a json element and a string infers
`Map<String, Any>`, which has no serializer: the call fails with
`SerializationException: Serializer for class 'Any' is not found` and never reaches the
network. Every parameter map in `sync/SyncRemote.kt` is therefore typed
`Map<String, JsonElement>`, and `SyncRemoteParamsTests` asserts the encoded bodies.

## Validation

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Database integration tests live in `focal/supabase/tests/` and run with
`supabase test db` against a local Supabase stack. They require Docker and include canonical
session lifecycle, offline timing, cursor and versioned-change cases. A live sync check also
requires the shared Focal URL/key and a test account.
