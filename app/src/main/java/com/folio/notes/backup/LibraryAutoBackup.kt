package com.folio.notes.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.folio.notes.LibraryBackupJobService
import com.folio.notes.app.AppPrefs
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SAF-backed rolling library backup. Access is retained only for the folder the user chooses. */
object LibraryAutoBackup {
    private val currentProgress = MutableStateFlow<String?>(null)
    val progress = currentProgress.asStateFlow()
    internal fun reportProgress(message: String?) { currentProgress.value = message }

    const val TREE_URI = AppPrefs.AUTO_BACKUP_TREE_URI
    const val LAST_SUCCESS = AppPrefs.AUTO_BACKUP_LAST_SUCCESS
    const val LAST_ERROR = AppPrefs.AUTO_BACKUP_LAST_ERROR
    const val BACKUP_FOLDER = "Folio automatic backup"
    const val PERIODIC_JOB_ID = 7_241
    const val DEBOUNCED_JOB_ID = 7_242
    const val IMMEDIATE_JOB_ID = 7_243
    private const val DAILY_INTERVAL = 24L * 60 * 60 * 1000
    private const val EDIT_DELAY = 2L * 60 * 1000
    private const val EDIT_DEADLINE = 30L * 60 * 1000

    fun enable(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        val previous = prefs.getString(TREE_URI, null)
        if (previous != null && previous != treeUri.toString()) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previous),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs.edit().putString(TREE_URI, treeUri.toString()).remove(LAST_ERROR).also { edit ->
            if (previous != treeUri.toString()) edit.remove(LAST_SUCCESS)
        }.apply()
        scheduleDaily(context)
        scheduleNow(context)
    }

    fun ensureScheduled(context: Context) {
        val treeUri = configuredTreeUri(context) ?: return
        val scheduler = scheduler(context) ?: return
        if (scheduler.getPendingJob(PERIODIC_JOB_ID) == null) scheduleDaily(context)
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        if (prefs.getLong(LAST_SUCCESS, 0L) == 0L) scheduleNow(context)
        // Drop a stale preference if the grant was revoked in Android settings.
        val hasGrant = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        if (!hasGrant) {
            scheduler.cancel(PERIODIC_JOB_ID)
            scheduler.cancel(DEBOUNCED_JOB_ID)
            scheduler.cancel(IMMEDIATE_JOB_ID)
            reportProgress(null)
            setFailure(context, "Folder access was revoked. Choose a backup folder again.")
        }
    }

    fun disable(context: Context) {
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        configuredTreeUri(context)?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        reportProgress(null)
        prefs.edit().remove(TREE_URI).remove(LAST_SUCCESS).remove(LAST_ERROR).apply()
        scheduler(context)?.cancel(PERIODIC_JOB_ID)
        scheduler(context)?.cancel(DEBOUNCED_JOB_ID)
        scheduler(context)?.cancel(IMMEDIATE_JOB_ID)
    }

    fun requestAfterSave(context: Context) {
        if (configuredTreeUri(context) != null) scheduleEditBackup(context)
    }

    fun requestNow(context: Context) {
        if (configuredTreeUri(context) != null) scheduleNow(context)
    }

    fun configuredTreeUri(context: Context): Uri? = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        .getString(TREE_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun folderName(context: Context, uri: Uri?): String? {
        if (uri == null) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    fun lastSuccess(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        .getLong(LAST_SUCCESS, 0L)

    fun lastError(context: Context) = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        .getString(LAST_ERROR, null)

    internal fun setSuccess(context: Context) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit()
            .putLong(LAST_SUCCESS, System.currentTimeMillis()).remove(LAST_ERROR).apply()
    }

    internal fun setFailure(context: Context, message: String) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putString(LAST_ERROR, message).apply()
    }

    private fun scheduleDaily(context: Context) {
        val scheduler = scheduler(context) ?: return
        val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, LibraryBackupJobService::class.java))
            .setPersisted(true)
            .setPeriodic(DAILY_INTERVAL)
            .build()
        scheduler.schedule(job)
    }

    private fun scheduleEditBackup(context: Context) = scheduleOneShot(context, DEBOUNCED_JOB_ID, EDIT_DELAY, EDIT_DEADLINE)
    private fun scheduleNow(context: Context) {
        if (scheduleOneShot(context, IMMEDIATE_JOB_ID, 0, 60_000) == JobScheduler.RESULT_SUCCESS) {
            currentProgress.compareAndSet(null, "Backup queued · waiting for Android to start…")
        }
    }

    private fun scheduleOneShot(context: Context, jobId: Int, delay: Long, deadline: Long): Int? {
        val job = JobInfo.Builder(jobId, ComponentName(context, LibraryBackupJobService::class.java))
            .setPersisted(true)
            .setMinimumLatency(delay)
            .setOverrideDeadline(deadline)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_LINEAR)
            .build()
        return scheduler(context)?.schedule(job)
    }

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
}
