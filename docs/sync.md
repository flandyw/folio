# Sync

Folio is moving onto the shared change log described in Focal's
[`docs/sync-protocol.md`](../../focal/docs/sync-protocol.md). That document is the contract:
the SQL migrations and both client implementations follow it, and anything that disagrees
with it is a bug in the implementation.

## What is installed

**ExamTrack's Supabase project** has `supabase/migrations/20260926020000_change_log.sql`
(in the `examtrack` repository). It adds:

- `sync_log` — an append-only log. `seq` is the only ordering authority; `change_id` is the
  idempotency key; `lamport` orders concurrent edits between devices that never see each other.
- `sync_state` — the materialized current value of every row, tombstones included. A tombstone
  is never dropped, so a stale device cannot resurrect a deleted row.
- `sync_floors` — the sequence below which log rows have been compacted away, so a client that
  fell too far behind is told to snapshot instead of silently missing changes.
- `sync_apply_changes` and `sync_read_changes` — the only two calls a client needs. Both force
  the caller's identity, so a client cannot publish or read on someone else's behalf.
- Triggers that turn writes to `mistakes`, `attempts` and `user_state` into log entries, and
  project log winners back into those tables. **ExamTrack's web app needs no changes** and keeps
  its existing read/write behaviour; Folio's reviews show up on the web through the projection.
- A `folio-pages` storage bucket for content-addressed page blocks: images, imported PDFs and
  page snapshots, named by `sha256(bytes)` at `<user id>/<hash prefix>/<hash>`.

Apply it to the ExamTrack project before the wiring below can do anything:

```sh
supabase db push          # or: psql -f supabase/migrations/20260926020000_change_log.sql
```

The migration ends in assertions that fail loudly, so a half-installed protocol cannot pass
unnoticed, and `examtrack/scripts/check-sync-migration.mjs` (`bun run check:sync`) checks its
structure without a database.

## What is implemented in the app

| Piece | Where | State |
| --- | --- | --- |
| Protocol core: ordering, tombstones, echo suppression, coalescing, backoff | `sync/SyncProtocol.kt` | done, mirrors Focal's `src/lib/sync/reduce.ts` rule for rule |
| Durable queue, cursor, lamport clock, applied versions | `sync/SyncStore.kt` | done, write-temp + fsync + rename |
| Publish and read over the two RPCs | `sync/SyncRemote.kt` | done, behind an interface so the engine is testable offline |
| Conformance vectors | `sync/SyncConformanceTests.kt` | done — runs the same `conformance.json` Focal runs |
| Store durability | `sync/SyncStoreTests.kt` | done |

Run them with `./gradlew :app:testDebugUnitTest`. The vector file is a copy of Focal's
`src/lib/sync/vectors/conformance.json`; both copies record the same SHA-256, so editing one
without the other fails a build.

## What is not wired yet

`SyncStore` and `SyncRemote` are the client half of the installed schema, but no screen calls
them yet. The remaining work, in the order it should be done:

1. **Mistakes.** Replace the full-table fetch and compare-and-set in
   `mistakes/ExamTrackSyncService.kt` and `mistakes/MistakeRepository.kt` with
   `read(cursor)` plus `apply(queued)`. Two domain rules have to survive the move, and both are
   easy to lose:
   - *Folio never creates a mistake.* Today a queued update is skipped when the row is missing
     remotely. With an append-only log a `put` would create it, so the queue must only hold
     puts for rows this device has seen come down from the log (track that in the cache).
   - *Folio only edits scheduling.* `preserveRemoteFields` needs the last remote payload, which
     the current cache does not keep — it stores the merged card. Keep the remote payload
     alongside it, or the projection will write a card that drops the question text.
   The cache becomes a protocol participant rather than a side store: `outbox`, `versions` and
   `cursor` alongside today's `mistakes`, with a codec bump that re-queues anything a v2 cache
   still had pending.
2. **Notes, pages and strokes.** `folio_notebooks` and `folio_pages` are ordinary last-writer-wins
   rows. `folio_strokes` is not: it is one row per stroke, merged by union, so two devices
   writing the same page both keep their ink. This needs a stable id on `Stroke` — the model
   currently identifies strokes by object reference — which means a `NoteStore`/`PageJournal`
   codec bump, a migration for existing pages, and round-trip tests. Do that as its own change;
   it touches the storage format the rest of the app depends on.
3. **Focal study sessions** (`FocalStudy.kt`) keep working through the compatibility view on
   Focal's project. They can move onto `sync_apply_changes`/`sync_read_changes` later; nothing
   is blocked on it.

## Rules that must not be broken

These are the ones a future change can quietly undo:

- The local write is durable and queued before the UI is told anything. Nothing waits on the
  network, and the network never waits on the UI.
- A change leaves the queue only on a receipt. A crash mid-push resends, which is free.
- Pull is a cursor. There is no timestamp comparison and no full-table scan.
- Echo suppression is by device id. Never by comparing payloads.
- A tombstone is permanent.
- A row with an unpublished local edit is never overwritten by a pull or a snapshot.

The last one is the subtle one, and it is now covered by a shared vector so both clients fail
loudly if it regresses.
