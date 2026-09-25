package com.folio.notes.mistakes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.folio.notes.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-encrypted SDK session, outside Android backup and all .folio archives. */
class EncryptedExamTrackSession(context: Context, name: String = "examtrack-session", alias: String = "folio-examtrack") : SessionManager {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    private val keyAlias = alias
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun saveSession(session: UserSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(Json.encodeToString(UserSession.serializer(), session).toByteArray())
        val stream = file.startWrite()
        try { stream.write(cipher.iv.size); stream.write(cipher.iv); stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        runCatching {
            val bytes = file.openRead().use { it.readBytes() }
            val size = bytes[0].toInt().also { require(it == 12) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)))
            }
            Json.decodeFromString(UserSession.serializer(), cipher.doFinal(bytes.copyOfRange(size + 1, bytes.size)).decodeToString())
        }.getOrElse { file.delete(); null }
    }
    override suspend fun deleteSession() = withContext(Dispatchers.IO) { file.delete() }
}

class ExamTrackAuthRepository(context: Context) {
    val sessions = EncryptedExamTrackSession(context)
    val client = createSupabaseClient(BuildConfig.EXAMTRACK_SUPABASE_URL, BuildConfig.EXAMTRACK_SUPABASE_PUBLISHABLE_KEY) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = sessions
            autoLoadFromStorage = false
            alwaysAutoRefresh = true
            enableLifecycleCallbacks = false // Keep the account/cache usable while offline or backgrounded.
        }
        install(Postgrest)
        install(Storage)
    }
    private val lifecycle = ExamTrackSessionLifecycle(client, sessions)
    val restoredUser get() = lifecycle.restoredUser
    suspend fun awaitRestoration() = lifecycle.awaitRestoration()
    suspend fun signIn(email: String, password: String) {
        awaitRestoration()
        lifecycle.cancelRestoration()
        client.auth.signInWith(Email) { this.email = email; this.password = password }
    }
    suspend fun signUp(email: String, password: String) {
        awaitRestoration()
        lifecycle.cancelRestoration()
        client.auth.signUpWith(Email) { this.email = email; this.password = password }
    }
    suspend fun resetPassword(email: String) {
        awaitRestoration()
        client.auth.resetPasswordForEmail(email)
    }
    suspend fun signOut() {
        // Local sign-out must work offline; clearing also stops SDK token refresh.
        lifecycle.cancelRestoration()
        client.auth.clearSession()
        sessions.deleteSession()
    }
    suspend fun close() {
        lifecycle.cancelRestoration()
        client.close()
    }
}
