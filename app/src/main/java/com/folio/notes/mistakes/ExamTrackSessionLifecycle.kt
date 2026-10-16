package com.folio.notes.mistakes

import com.folio.notes.sync.FocalSessionRefreshingException
import com.folio.notes.sync.FocalSessionRequiredException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

enum class FocalAccountStatus { RESTORING, SIGNED_OUT, CONNECTED, RECONNECTING, SIGN_IN_REQUIRED }

data class FocalAccountState(
    val status: FocalAccountStatus = FocalAccountStatus.RESTORING,
    val user: UserInfo? = null,
) {
    val needsSignIn get() = status == FocalAccountStatus.SIGN_IN_REQUIRED
    val message get() = when (status) {
        FocalAccountStatus.RESTORING -> "Restoring Focal account…"
        FocalAccountStatus.SIGNED_OUT -> "Sign in to connect Focal"
        FocalAccountStatus.CONNECTED -> "Connected to Focal"
        FocalAccountStatus.RECONNECTING -> "Focal login is reconnecting · saved work will sync automatically"
        FocalAccountStatus.SIGN_IN_REQUIRED -> "Focal session ended · sign in again. Saved work is kept on this device."
    }
}

/** One owner for restoration, refresh and account changes. The SDK still exchanges the tokens.
 * SDK 3.0.3's auto-refresh deletes credentials on temporary HTTP 408/429 responses; it is disabled.
 * The mutex also prevents a late refresh from replacing a new login or undoing a local sign-out.
 */
class ExamTrackSessionLifecycle(private val client: SupabaseClient, private val sessions: SessionManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(FocalAccountState())
    val state = mutable.asStateFlow()
    private var retryAt = 0L
    private var failures = 0
    private var restorePending = true
    @Volatile private var pendingSave: UserSession? = null

    init {
        scope.launch {
            try { gate.withLock { restoreLocked() } }
            finally { restored.complete(Unit) }
            while (isActive) {
                gate.withLock {
                    if (restorePending && System.currentTimeMillis() >= retryAt) restoreLocked()
                    val session = client.auth.currentSessionOrNull()
                    if (!state.value.needsSignIn && session != null &&
                        System.currentTimeMillis() >= retryAt &&
                        (pendingSave != null || state.value.status == FocalAccountStatus.RECONNECTING ||
                            session.expiresAt.toEpochMilliseconds() - System.currentTimeMillis() <= 60_000)) refreshLocked()
                }
                val expires = client.auth.currentSessionOrNull()?.expiresAt?.toEpochMilliseconds()
                val wait = if (restorePending) (retryAt - System.currentTimeMillis()).coerceIn(1, 30_000)
                else if (expires == null || state.value.needsSignIn) 30_000L else
                    (if (state.value.status == FocalAccountStatus.RECONNECTING) retryAt else maxOf(retryAt, expires - 60_000))
                        .minus(System.currentTimeMillis()).coerceIn(1, 30_000)
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    suspend fun awaitRestoration() = restored.await()

    fun retry() { wake.trySend(Unit) }

    private suspend fun restoreLocked() {
        try {
            val session = sessions.loadSession()
            if (session == null) mutable.value = FocalAccountState(FocalAccountStatus.SIGNED_OUT)
            else {
                client.auth.importSession(session, autoRefresh = false)
                mutable.value = FocalAccountState(FocalAccountStatus.CONNECTED, session.user)
            }
            restorePending = false
            failures = 0
            retryAt = 0
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { holdForRetry() }
        catch (_: Exception) {
            // Corrupt/undecryptable credentials require login, but are kept for diagnosis/recovery.
            restorePending = false
            mutable.value = FocalAccountState(FocalAccountStatus.SIGN_IN_REQUIRED)
        }
    }

    private suspend fun refreshLocked() {
        val old = client.auth.currentSessionOrNull() ?: return
        mutable.value = state.value.copy(status = FocalAccountStatus.RECONNECTING)
        try {
            val refreshed = pendingSave ?: withTimeout(30_000) { client.auth.refreshSession(old.refreshToken) }
            // A rotated refresh token must reach durable storage even if the requesting screen leaves.
            withContext(NonCancellable) {
                pendingSave = refreshed
                client.auth.importSession(refreshed, autoRefresh = false, source = SessionSource.Refresh(old))
                sessions.saveSession(refreshed)
                pendingSave = null
                failures = 0
                retryAt = 0
                mutable.value = FocalAccountState(FocalAccountStatus.CONNECTED, refreshed.user)
            }
        } catch (e: TimeoutCancellationException) { holdForRetry() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (focalRefreshRequiresSignIn(e)) {
                mutable.value = FocalAccountState(FocalAccountStatus.SIGN_IN_REQUIRED, old.user)
                client.auth.clearSession()
            } else holdForRetry()
        }
    }

    private fun holdForRetry() {
        failures = (failures + 1).coerceAtMost(5)
        retryAt = System.currentTimeMillis() + (2_000L shl failures).coerceAtMost(60_000)
        mutable.value = state.value.copy(status = FocalAccountStatus.RECONNECTING)
    }

    suspend fun awaitSession(accountId: String) {
        awaitRestoration()
        retry()
        val account = withTimeoutOrNull(15_000) {
            state.first { account ->
                account.user?.id != accountId || account.needsSignIn ||
                    account.status == FocalAccountStatus.SIGNED_OUT ||
                    (pendingSave == null &&
                        (client.auth.currentSessionOrNull()?.expiresAt?.toEpochMilliseconds() ?: 0L) > System.currentTimeMillis())
            }
        } ?: throw FocalSessionRefreshingException()
        if (account.user?.id != accountId || account.needsSignIn || account.status == FocalAccountStatus.SIGNED_OUT)
            throw FocalSessionRequiredException()
    }

    /** Retry a rejected request once, sharing any refresh another sync lane has already completed. */
    suspend fun <T> withSession(accountId: String, request: suspend () -> T): T {
        awaitSession(accountId)
        val token = client.auth.currentAccessTokenOrNull()
        try { return request() }
        catch (e: CancellationException) { throw e }
        catch (e: RestException) { if (e.statusCode != 401) throw e }
        // Finish exchange + persistence in the account owner, not in a cancelable view's job.
        scope.async {
            gate.withLock {
                if (state.value.user?.id == accountId && !state.value.needsSignIn &&
                    client.auth.currentAccessTokenOrNull() == token && System.currentTimeMillis() >= retryAt) refreshLocked()
            }
        }.await()
        awaitSession(accountId)
        if (client.auth.currentAccessTokenOrNull() == token) throw FocalSessionRefreshingException()
        return request()
    }

    suspend fun signIn(email: String, password: String) = changeAccount {
        client.auth.signInWith(Email) { this.email = email; this.password = password }
    }

    suspend fun signUp(email: String, password: String) = changeAccount {
        client.auth.signUpWith(Email) { this.email = email; this.password = password }
    }

    private suspend fun changeAccount(action: suspend () -> Unit) {
        awaitRestoration()
        scope.async { gate.withLock {
            action()
            client.auth.currentSessionOrNull()?.let {
                restorePending = false
                mutable.value = FocalAccountState(FocalAccountStatus.RECONNECTING, it.user)
                pendingSave = it
                try { withContext(NonCancellable) { sessions.saveSession(it) } }
                catch (e: Exception) { holdForRetry(); throw e }
                pendingSave = null
                failures = 0
                retryAt = 0
                mutable.value = FocalAccountState(FocalAccountStatus.CONNECTED, it.user)
            }
        } }.await()
        retry()
    }

    suspend fun signOut() {
        awaitRestoration()
        gate.withLock {
            client.auth.clearSession()
            restorePending = false
            pendingSave = null
            mutable.value = FocalAccountState(FocalAccountStatus.SIGNED_OUT)
        }
        retry()
    }

    internal fun close() { scope.cancel() }
}

internal fun focalRefreshRequiresSignIn(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(12).filterIsInstance<RestException>()
        .any { it.statusCode in setOf(400, 401, 422) }
