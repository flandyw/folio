package com.folio.notes

import android.content.Context
import com.folio.notes.mistakes.EncryptedExamTrackSession
import com.folio.notes.mistakes.ExamTrackSessionLifecycle
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
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
    val client: SupabaseClient = createSupabaseClient(BuildConfig.FOCAL_SUPABASE_URL, BuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = sessions
            autoLoadFromStorage = false
            alwaysAutoRefresh = true
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
