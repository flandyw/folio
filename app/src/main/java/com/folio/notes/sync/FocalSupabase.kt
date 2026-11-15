package com.folio.notes.sync

import android.content.Context
import com.folio.notes.FocalBuildConfig
import com.folio.notes.mistakes.sync.EncryptedExamTrackSession
import com.folio.notes.mistakes.sync.ExamTrackSessionLifecycle
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * One Supabase connection for the whole app. Study sessions and mistake review live in the same
 * Focal project since ExamTrack was merged into it, so they share a client, a session and a sign-in.
 * The SDK is created once per process: two clients on one session file would each refresh tokens
 * independently and overwrite the other's ciphertext.
 */
internal fun focalSyncDeviceId(context: Context): String = context.applicationContext
    .getSharedPreferences("focal-sync", Context.MODE_PRIVATE).let { preferences ->
        preferences.getString("device-id", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("device-id", it).commit()) { "Could not save Folio's sync device ID" }
        }
    }

class FocalSupabaseConnection(context: Context) {
    private val appContext = context.applicationContext
    val sessions = EncryptedExamTrackSession(appContext, "focal-session", "folio-focal")
    val client: SupabaseClient = createSupabaseClient(FocalBuildConfig.FOCAL_SUPABASE_URL, FocalBuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = sessions
            autoLoadFromStorage = false
            autoSaveToStorage = false // The lifecycle commits rotated tokens before reporting recovery.
            alwaysAutoRefresh = false // ExamTrackSessionLifecycle owns refresh and retry classification.
            enableLifecycleCallbacks = false // Keep the account and cache usable while offline or backgrounded.
        }
        install(Postgrest)
        install(Realtime)
        install(Storage)
    }
    internal val lifecycle = ExamTrackSessionLifecycle(client, sessions)

    init {
        // The pre-merge ExamTrack project is gone; its encrypted session can never authenticate again.
        runCatching { File(appContext.noBackupFilesDir, "examtrack-session").delete() }
    }

    companion object {
        @Volatile private var shared: FocalSupabaseConnection? = null
        fun of(context: Context): FocalSupabaseConnection = shared ?: synchronized(this) {
            shared ?: FocalSupabaseConnection(context).also { shared = it }
        }
    }
}

/**
 * Calls [onChange] when the account's change log gains one of [entities], and on every (re)join, so
 * nothing written while the socket was down is missed. Focal publishes the append-only `sync_log`;
 * base tables are not guaranteed to be in the Realtime publication. Runs until cancelled: a failed
 * socket is retried, and the channel is always removed so a later watch never doubles its events.
 */
internal suspend fun watchFocalFeed(client: SupabaseClient, name: String, entities: List<String>, onChange: () -> Unit): Nothing {
    while (true) {
        val channel = client.realtime.channel(name)
        try {
            coroutineScope {
                for (entity in entities) channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "sync_log"
                    filter("entity", FilterOperator.EQ, entity)
                }.onEach { onChange() }.launchIn(this)
                channel.status.onEach { if (it == RealtimeChannel.Status.SUBSCRIBED) onChange() }.launchIn(this)
                channel.subscribe(blockUntilSubscribed = true)
                awaitCancellation()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { delay(7_000) }
        finally { withContext(NonCancellable) { runCatching { client.realtime.removeChannel(channel) } } }
    }
}
