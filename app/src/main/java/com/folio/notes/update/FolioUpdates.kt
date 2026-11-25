package com.folio.notes.update

import android.content.Context
import com.folio.notes.app.AppPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FolioUpdateState(
    val initializing: Boolean = true,
    val available: FolioUpdate? = null,
    val ready: DownloadedUpdate? = null,
    val checking: Boolean = false,
    val downloading: FolioUpdate? = null,
    val progress: UpdateDownloadProgress = UpdateDownloadProgress(),
    val message: String? = null,
    /** Newest version code whose banner the user dismissed; a newer update shows it again. */
    val dismissed: Long = 0L,
) {
    val busy get() = initializing || checking || downloading != null
    val downloadCandidate get() = available?.takeIf { it.versionCode > (ready?.update?.versionCode ?: 0) }
    private val offered get() = maxOf(downloadCandidate?.versionCode ?: 0L, ready?.update?.versionCode ?: 0L)
    val showBanner get() = downloading != null || offered > dismissed
    fun dismissible() = downloading == null && offered > 0L
    internal fun dismissedNow() = maxOf(dismissed, offered)
}

/** Process-owned transfers survive Activity recreation; verified downloads also survive process death. */
class FolioUpdates(context: Context) {
    private val checker = FolioUpdateChecker(context.applicationContext)
    private val prefs = context.getSharedPreferences("preferences", 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(FolioUpdateState(dismissed = prefs.getLong(AppPrefs.UPDATE_DISMISSED, 0L)))
    val state = mutableState.asStateFlow()
    private var source: Boolean? = null
    private val initialized = scope.async {
        try {
            val ready = withContext(Dispatchers.IO) { checker.restoreDownload() }
            mutableState.update { it.copy(ready = ready) }
        } catch (_: Exception) {
            message("Couldn't restore the downloaded update.")
        } finally {
            mutableState.update { it.copy(initializing = false) }
        }
    }

    fun sourceChanged(experimental: Boolean) {
        if (source != null && source != experimental) {
            mutableState.update { it.copy(available = null, message = null) }
        }
        source = experimental
    }

    /** Hides the app-wide banner until a newer update turns up; Settings still lists it. */
    fun dismiss() {
        val code = state.value.dismissedNow()
        prefs.edit().putLong(AppPrefs.UPDATE_DISMISSED, code).apply()
        mutableState.update { it.copy(dismissed = code) }
    }

    fun message(value: String) { mutableState.update { it.copy(message = value) } }

    fun check(experimental: Boolean, manual: Boolean) {
        if (state.value.busy) return
        val now = System.currentTimeMillis()
        val retryKey = AppPrefs.updateRetryAtKey(experimental)
        val lastCheckKey = AppPrefs.lastUpdateCheckKey(experimental)
        val retryAt = prefs.getLong(retryKey, 0L)
        if (retryAt > now) {
            if (manual) message("Update check paused · try again in ${((retryAt - now) + 59_999) / 60_000} min")
            return
        }
        if (!manual && !shouldAutoUpdateCheck(now, prefs.getLong(lastCheckKey, 0L))) return
        mutableState.update { it.copy(checking = true, message = null) }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { checker.check(experimental) }
                prefs.edit().putLong(lastCheckKey, System.currentTimeMillis()).remove(retryKey).apply()
                mutableState.update { it.copy(available = result, message = if (manual) {
                    when {
                        result == null -> if (experimental) "No newer experimental build is available." else "You're up to date."
                        result.versionCode <= (it.ready?.update?.versionCode ?: 0) -> "The latest update is already downloaded."
                        else -> null
                    }
                } else null) }
            } catch (error: Exception) {
                val rateRetry = (error as? UpdateHttpException)?.retryAtMillis
                if (rateRetry != null) prefs.edit().putLong(retryKey, rateRetry).apply()
                else if (!manual) prefs.edit().putLong(lastCheckKey,
                    System.currentTimeMillis() - AUTO_UPDATE_CHECK_INTERVAL_MILLIS + UPDATE_FAILURE_RETRY_MILLIS).apply()
                if (manual) message(error.message ?: "Couldn't check for updates.")
            } finally {
                mutableState.update { it.copy(checking = false) }
            }
        }
    }

    fun download(update: FolioUpdate) {
        if (state.value.busy || update.versionCode <= (state.value.ready?.update?.versionCode ?: 0)) return
        mutableState.update { it.copy(downloading = update, progress = UpdateDownloadProgress(), message = null) }
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    checker.download(update) { progress -> mutableState.update { it.copy(progress = progress) } }
                }
                mutableState.update { it.copy(ready = DownloadedUpdate(update, file)) }
            } catch (error: Exception) {
                message(error.message ?: "Couldn't download the update.")
            } finally {
                mutableState.update { it.copy(downloading = null) }
            }
        }
    }

    fun discard() {
        if (state.value.busy) return
        val ready = state.value.ready ?: return
        mutableState.update { it.copy(initializing = true) }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { checker.discardDownload(ready) }
                mutableState.update { it.copy(ready = null, message = "Downloaded update deleted.") }
            } catch (_: Exception) {
                message("Couldn't delete the downloaded update. Try again.")
            } finally {
                mutableState.update { it.copy(initializing = false) }
            }
        }
    }

    suspend fun awaitReady() { initialized.await() }
}
