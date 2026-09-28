package com.folio.notes.mistakes

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.folio.notes.focalSyncDeviceId
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class MistakesState(val userId: String? = null, val email: String? = null,
    val cache: MistakeCache = MistakeCache(), val status: String = "Not connected",
    val busy: Boolean = false, val error: String? = null, val authMessage: String? = null)

class MistakesViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = ExamTrackAuthRepository(application)
    private val repository = MistakeRepository(FileMistakeCacheStore(application),
        ExamTrackSyncService(auth.client, focalSyncDeviceId(application)))
    val attachments = MistakeAttachmentRepository(application, auth.client)
    private val _state = MutableStateFlow(MistakesState())
    val state = _state.asStateFlow()
    private var signingOut = false
    private var syncJob: Job? = null
    private var rerunSync = false
    private var lastRequest = 0L
    private val foreground = MutableStateFlow(false)
    fun setForeground(value: Boolean) { foreground.value = value }
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { viewModelScope.launch { requestSync() } }
    }
    init {
        connectivity.registerDefaultNetworkCallback(callback)
        viewModelScope.launch {
            combine(auth.client.auth.sessionStatus, foreground) { status, visible -> status to visible }
                .collectLatest { (status, visible) ->
                    val user = (status as? SessionStatus.Authenticated)?.session?.user?.id
                    if (visible && user != null) watchRemoteChanges(user)
                }
        }
        viewModelScope.launch {
            // The SDK imports the saved identity before attempting any network refresh.
            auth.awaitRestoration()
            auth.restoredUser?.let { acceptUser(it.id, it.email) }
            auth.client.auth.sessionStatus.collect { status ->
                if (signingOut) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        status.session.user?.let { acceptUser(it.id, it.email) }
                        requestSync(force = true)
                    }
                    is SessionStatus.NotAuthenticated -> {
                        syncJob?.cancel()
                        _state.value = MistakesState(status = if (status.isSignOut) "Not connected" else "Sign in to connect ExamTrack")
                    }
                    is SessionStatus.RefreshFailure -> _state.update { it.copy(status = "Offline · session refresh will retry") }
                    else -> Unit
                }
            }
        }
    }
    /** Listen while the app is visible; the 30-second pull also covers missed socket events. */
    private suspend fun watchRemoteChanges(user: String) {
        while (foreground.value && auth.client.auth.currentUserOrNull()?.id == user) {
            val channel = auth.client.realtime.channel("folio-mistakes-$user")
            try {
                coroutineScope {
                    // ExamTrack publishes the append-only sync log. Base tables are not
                    // guaranteed to be in the Realtime publication.
                    for (entity in listOf("mistakes", "attempts")) {
                        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                            table = "sync_log"; filter("entity", FilterOperator.EQ, entity)
                        }.onEach { requestSync(force = true) }.launchIn(this)
                    }
                    channel.status.onEach { status ->
                        if (status == RealtimeChannel.Status.SUBSCRIBED) requestSync(force = true)
                    }.launchIn(this)
                    channel.subscribe(blockUntilSubscribed = true)
                    awaitCancellation()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { delay(7_000) }
            finally { withContext(NonCancellable) { runCatching { auth.client.realtime.removeChannel(channel) } } }
        }
    }
    private suspend fun acceptUser(id: String, email: String?) {
        if (_state.value.userId == id) return
        _state.value = MistakesState(userId = id, email = email, status = "Loading saved mistakes…")
        reload(id)
    }
    private suspend fun reload(id: String) {
        try {
            val cache = repository.cache(id)
            if (_state.value.userId == id) _state.update { it.copy(cache = cache) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "Could not read saved mistakes. Stored files have been kept.") } }
    }
    fun signIn(email: String, password: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        viewModelScope.launch {
            try { auth.signIn(email.trim(), password) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not sign in. Check your email, password and connection.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun signUp(email: String, password: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        viewModelScope.launch {
            try {
                auth.signUp(email.trim(), password)
                _state.update { it.copy(authMessage = "Account created. If asked, confirm your email, then sign in.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not create the account. Check your details and connection.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun resetPassword(email: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        viewModelScope.launch {
            try {
                auth.resetPassword(email.trim())
                _state.update { it.copy(authMessage = "If this email has an account, a password reset link is on its way.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not request a reset. Check your connection and try again.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun signOut() {
        if (signingOut) return
        signingOut = true
        rerunSync = false
        _state.value = MistakesState(busy = true) // Hide outgoing data before any asynchronous work.
        viewModelScope.launch {
            try {
                syncJob?.cancelAndJoin()
                auth.signOut()
            } finally {
                signingOut = false
                _state.value = MistakesState()
            }
        }
    }
    /** Hides a transient sync/status banner; the next sync or session event can show it again. */
    fun dismissStatus() { _state.update { it.copy(status = "") } }

    fun requestSync(force: Boolean = false) {
        val user = _state.value.userId ?: return
        if (syncJob?.isActive == true) { rerunSync = true; return }
        val now = System.currentTimeMillis()
        val waitMillis = if (force) 300L else maxOf(300L, 5_000 - (now - lastRequest))
        syncJob = viewModelScope.launch {
            delay(waitMillis)
            lastRequest = System.currentTimeMillis()
            if (_state.value.userId != user) return@launch
            _state.update { it.copy(status = "Syncing…", error = null) }
            try {
                val result = repository.sync(user)
                if (_state.value.userId == user) _state.update { it.copy(status = result.toString()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (_state.value.userId == user) _state.update { it.copy(status = examTrackSyncError(e)) }
            } finally {
                withContext(NonCancellable) { reload(user) }
            }
        }
        syncJob?.invokeOnCompletion {
            if (rerunSync && !signingOut) {
                rerunSync = false
                viewModelScope.launch { requestSync(force = true) }
            }
        }
    }
    suspend fun addAttempt(attempt: LocalMistakeReviewAttempt) {
        check(_state.value.userId == attempt.userId)
        syncJob?.cancelAndJoin()
        try {
            repository.addAttempt(attempt.userId, attempt)
            reload(attempt.userId)
        } finally {
            // Advancing immediately after rating cancels the debounced upload above.
            // Resume even if this page write fails so earlier ratings are not stranded.
            requestSync(force = true)
        }
    }
    suspend fun rate(attempt: LocalMistakeReviewAttempt, rating: ReviewRating): LocalMistakeReviewAttempt {
        check(_state.value.userId == attempt.userId)
        // A local save has priority over a slow network request.
        syncJob?.cancelAndJoin()
        val result = repository.rate(attempt.userId, attempt, rating, isoTime())
        reload(attempt.userId)
        requestSync(force = true)
        return result
    }
    /**
     * Deletes a card: local removal now, remote soft-delete on the next sync.
     * Handwriting stays on this device, as with web-side deletions.
     */
    suspend fun deleteMistake(mistakeId: String) {
        val user = _state.value.userId ?: return
        syncJob?.cancelAndJoin()
        try {
            repository.delete(user, mistakeId, isoTime())
            reload(user)
        } finally {
            requestSync(force = true)
        }
    }
    /** Forgets cached reviews whose practice notebooks were deleted from this device. */
    suspend fun removeAttemptsForNotebooks(notebookIds: Set<String>) {
        val user = _state.value.userId ?: return
        if (notebookIds.isEmpty()) return
        syncJob?.cancelAndJoin()
        try {
            repository.removeAttemptsForNotebooks(user, notebookIds)
            reload(user)
        } finally {
            requestSync(force = true)
        }
    }
    override fun onCleared() {
        connectivity.unregisterNetworkCallback(callback)
        // The Supabase client is process-scoped and shared with study sessions, so it is not closed here.
        super.onCleared()
    }
}
