package com.folio.notes.mistakes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.folio.notes.FocalSupabaseConnection
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val gate = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun saveSession(session: UserSession) = withContext(NonCancellable + Dispatchers.IO) { gate.withLock {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(Json.encodeToString(UserSession.serializer(), session).toByteArray())
        val stream = file.startWrite()
        try { stream.write(cipher.iv.size); stream.write(cipher.iv); stream.write(encrypted); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    } }
    override suspend fun loadSession(): UserSession? = withContext(Dispatchers.IO) { gate.withLock {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.isNotEmpty()) { "Empty saved session" }
        val size = bytes[0].toInt().also { require(it == 12 && bytes.size > it + 16) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, size + 1)))
        }
        json.decodeFromString(UserSession.serializer(), cipher.doFinal(bytes.copyOfRange(size + 1, bytes.size)).decodeToString())
    } }
    override suspend fun deleteSession() = withContext(NonCancellable + Dispatchers.IO) { gate.withLock { file.delete() } }
}

/** Mistake review signs in through the shared Focal connection; one account covers both features. */
class ExamTrackAuthRepository(context: Context) {
    private val connection = FocalSupabaseConnection.of(context)
    val sessions: SessionManager = connection.sessions
    val client = connection.client
    val lifecycle = connection.lifecycle
    val account = lifecycle.state
    suspend fun awaitRestoration() = lifecycle.awaitRestoration()
    suspend fun signIn(email: String, password: String) {
        awaitRestoration()
        lifecycle.signIn(email, password)
    }
    suspend fun signUp(email: String, password: String) {
        awaitRestoration()
        lifecycle.signUp(email, password)
    }
    suspend fun resetPassword(email: String) {
        awaitRestoration()
        client.auth.resetPasswordForEmail(email)
    }
    suspend fun signOut() {
        // Local sign-out must work offline; clearing also stops SDK token refresh. Study sessions
        // share this session, so signing out of mistakes signs out of Focal too.
        lifecycle.signOut()
    }
}
