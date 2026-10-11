package com.folio.notes.mistakes

import com.folio.notes.sync.ApplyResult
import com.folio.notes.sync.ReadResult
import com.folio.notes.sync.SupabaseSyncRemote
import com.folio.notes.sync.SyncProtocol
import io.github.jan.supabase.SupabaseClient

/** Mistakes use the shared cursor and versioned change RPC; direct table CAS is retired. */
class ExamTrackSyncService(
    client: SupabaseClient,
    private val deviceId: String
) : ExamTrackRemote {
    private val syncRemote = SupabaseSyncRemote(client)

    override suspend fun read(userId: String, cursor: Long, limit: Int): ReadResult {
        return syncRemote.read(userId, cursor, limit)
    }

    override suspend fun apply(userId: String, change: SyncProtocol.QueuedChange): ApplyResult {
        require(change.entity == "mistakes" && change.expectedSeq != null) { "Mistake changes require a durable row version" }
        return syncRemote.apply(userId, listOf(change), deviceId)
    }
}
