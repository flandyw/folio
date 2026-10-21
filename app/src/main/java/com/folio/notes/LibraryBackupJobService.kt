package com.folio.notes

import android.app.job.JobParameters
import android.app.job.JobService
import com.folio.notes.backup.IncrementalBackup
import com.folio.notes.backup.LibraryAutoBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** OS-scheduled SAF backup so it can finish after Folio leaves the foreground. */
class LibraryBackupJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Int, Job>()
    private val stoppedJobs = ConcurrentHashMap.newKeySet<Job>()
    private val jobGate = Mutex()

    override fun onStartJob(params: JobParameters): Boolean {
        val job = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var retry = false
            try {
                jobGate.withLock {
                    val treeUri = LibraryAutoBackup.configuredTreeUri(this@LibraryBackupJobService)
                        ?: return@launch
                    retry = params.jobId != LibraryAutoBackup.PERIODIC_JOB_ID
                    try {
                        LibraryAutoBackup.reportProgress("Waiting for saved changes…")
                        val progress: (String) -> Unit = LibraryAutoBackup::reportProgress
                        val app = application as FolioApplication
                        // Only the local read holds the gate; the slow write to the backup folder must not
                        // stall note saves, which the editor (and mistake ratings) wait on.
                        val staged = app.storageGate.withLock {
                            val (notes, folders) = app.repository.load()
                            app.repository.stageLibrary(notes, folders, onProgress = progress)
                        }
                        staged.use {
                            IncrementalBackup.write(this@LibraryBackupJobService, treeUri, staged.prepare(progress), progress)
                            if (LibraryAutoBackup.configuredTreeUri(this@LibraryBackupJobService) == treeUri) {
                                LibraryAutoBackup.setSuccess(this@LibraryBackupJobService)
                            }
                            retry = false
                        }
                    } finally { LibraryAutoBackup.reportProgress(null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LibraryAutoBackup.setFailure(this@LibraryBackupJobService,
                    e.message?.takeIf { it.isNotBlank() } ?: "Automatic backup failed")
            } finally {
                val running = kotlinx.coroutines.currentCoroutineContext()[Job]!!
                activeJobs.remove(params.jobId, running)
                if (!stoppedJobs.remove(running)) jobFinished(params, retry)
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
            stoppedJobs.add(job)
            activeJobs.remove(params.jobId, job)
            job.cancel()
        }
        return true
    }

    override fun onDestroy() {
        stoppedJobs.addAll(activeJobs.values)
        serviceScope.cancel()
        activeJobs.clear()
        super.onDestroy()
    }
}
