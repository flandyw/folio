package com.folio.notes

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** SAF-backed rolling library backup. Access is retained only for the folder the user chooses. */
object LibraryAutoBackup {
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
    private const val MIME_ZIP = "application/zip"
    private const val AUTO_PREFIX = "Folio-auto-"
    private const val AUTO_SUFFIX = ".folio-backup.zip"

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
            setFailure(context, "Folder access was revoked. Choose a backup folder again.")
        }
    }

    fun disable(context: Context) {
        val prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
        configuredTreeUri(context)?.let { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
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

    internal fun createBackupDocument(context: Context, treeUri: Uri): Uri {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        var directory: Uri? = null
        resolver.query(childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == BACKUP_FOLDER &&
                    cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    directory = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    break
                }
            }
        }
        val targetDirectory = directory ?: DocumentsContract.createDocument(resolver, root,
            DocumentsContract.Document.MIME_TYPE_DIR, BACKUP_FOLDER)
            ?: error("Couldn't create the Folio backup folder")
        val name = "$AUTO_PREFIX${System.currentTimeMillis()}$AUTO_SUFFIX"
        return DocumentsContract.createDocument(resolver, targetDirectory, MIME_ZIP, name)
            ?: error("Couldn't create the automatic backup file")
    }

    internal fun pruneOldBackups(context: Context, treeUri: Uri, latest: Uri) {
        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val rootChildren = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            var backupDirectory: Uri? = null
            context.contentResolver.query(rootChildren,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) if (cursor.getString(nameCol) == BACKUP_FOLDER) {
                    backupDirectory = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    break
                }
            }
            val parent = backupDirectory ?: return
            val parentId = DocumentsContract.getDocumentId(parent)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val keepId = DocumentsContract.getDocumentId(latest)
            context.contentResolver.query(children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val id = cursor.getString(idCol)
                    if (name.startsWith(AUTO_PREFIX) && name.endsWith(AUTO_SUFFIX) && id != keepId) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver,
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id)) }
                    }
                }
            }
        }
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
    private fun scheduleNow(context: Context) = scheduleOneShot(context, IMMEDIATE_JOB_ID, 0, 60_000)

    private fun scheduleOneShot(context: Context, jobId: Int, delay: Long, deadline: Long) {
        val job = JobInfo.Builder(jobId, ComponentName(context, LibraryBackupJobService::class.java))
            .setPersisted(true)
            .setMinimumLatency(delay)
            .setOverrideDeadline(deadline)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_LINEAR)
            .build()
        scheduler(context)?.schedule(job)
    }

    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)
}

/** OS-scheduled SAF backup so it can finish after Folio leaves the foreground. */
class LibraryBackupJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val stoppedJobs = ConcurrentHashMap.newKeySet<Int>()

    override fun onStartJob(params: JobParameters): Boolean {
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var retry = false
            try {
                val treeUri = LibraryAutoBackup.configuredTreeUri(this@LibraryBackupJobService)
                    ?: return@launch
                retry = params.jobId != LibraryAutoBackup.PERIODIC_JOB_ID
                val app = application as FolioApplication
                app.storageGate.withLock {
                    val (notes, folders) = app.repository.load()
                    val target = LibraryAutoBackup.createBackupDocument(this@LibraryBackupJobService, treeUri)
                    try {
                        app.repository.exportLibrary(target, notes, folders)
                        LibraryAutoBackup.pruneOldBackups(this@LibraryBackupJobService, treeUri, target)
                        LibraryAutoBackup.setSuccess(this@LibraryBackupJobService)
                        retry = false
                    } catch (e: Exception) {
                        runCatching { DocumentsContract.deleteDocument(contentResolver, target) }
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryAutoBackup.setFailure(this@LibraryBackupJobService,
                    e.message?.takeIf { it.isNotBlank() } ?: "Automatic backup failed")
            } finally {
                activeJobs.remove(params.jobId)
                if (!stoppedJobs.remove(params.jobId)) jobFinished(params, retry)
            }
        }
        if (activeJobs.putIfAbsent(params.jobId, job) != null) {
            job.cancel()
            return false
        }
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeJobs[params.jobId]?.let { job ->
            stoppedJobs.add(params.jobId)
            activeJobs.remove(params.jobId, job)
            job.cancel()
        }
        return true
    }

    override fun onDestroy() {
        stoppedJobs.addAll(activeJobs.keys)
        serviceScope.cancel()
        activeJobs.clear()
        super.onDestroy()
    }
}
