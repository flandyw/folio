package com.folio.notes.mistakes

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.*

/** Owns initial refresh cancellation; token exchange itself remains entirely in the SDK. */
internal class ExamTrackSessionLifecycle(client: SupabaseClient, sessions: SessionManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restored = CompletableDeferred<Unit>()
    var restoredUser: UserInfo? = null
        private set
    // In SDK 3.0.3 an expired auto-load retry is not attached to sessionJob, so
    // clearSession alone cannot cancel that startup retry. Keep explicit ownership.
    private val restoreJob = scope.launch {
        try {
            sessions.loadSession()?.let {
                restoredUser = it.user
                client.auth.importSession(it, autoRefresh = false)
            }
        } finally { restored.complete(Unit) }
        if (client.auth.currentSessionOrNull() != null) client.auth.startAutoRefreshForCurrentSession()
    }
    suspend fun awaitRestoration() = restored.await()
    suspend fun cancelRestoration() {
        restoreJob.cancelAndJoin()
        scope.cancel()
    }
}
