# Sync

Folio uses the shared Focal Supabase project and follows the contract in
[`focal/docs/sync-protocol.md`](../../focal/docs/sync-protocol.md). Supabase Auth identifies
the account; Realtime is only a wakeup, while `sync_read_changes` and its cursor are the
durable source of updates.

## Study sessions

Canonical sessions live in `study_sessions`, with active intervals in the row's
`segments` JSON array (the shared single-table protocol). Folio queues lifecycle mutations locally and publishes them through
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
`Map<String, JsonElement>`, so the encoded bodies stay serializable.

## Validation

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

A live sync check requires the shared Focal URL/key and a test account. The dedicated
[study timer](study-timer.md) documents the device/account/offline release checklist.

## Notebook paper defaults: manual release check

`defaultPaper` is an optional notebook field in both the index and portable `.folio`
JSON. Missing fields preserve current-page inheritance; codec versions and page
journals are unchanged. `SPLIT_RULED` adds ruled paper with a centre divider.

Before release, open an older notebook on a device, long-press the final Add page
button, select Split ruled without the default switch, and confirm only the next
blank page uses it. Choose a different style with “Use as notebook default” enabled;
add pages from different existing pages and confirm they use that default. Repeat
through Page options → Page & view → Paper style, checking the current page changes
and other existing pages retain their styles. Restart the app and export/import a
`.folio` archive, then confirm the default and split paper survive both round trips.
Check the divider in the editor, thumbnail and PDF export, including an infinite
canvas.
