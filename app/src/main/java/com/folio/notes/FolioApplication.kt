package com.folio.notes

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class FolioApplication : Application() {
    internal val chatGpt by lazy { com.folio.notes.chatgpt.ChatGptManager(this) }
    val updates by lazy { FolioUpdates(this) }
    val repository by lazy { NoteRepository(this) }
    internal val runtime by lazy { FolioRuntime(this, SavedStateHandle()) }
    val thumbnails by lazy { PageThumbnailCache(this, repository) }
    val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes library backup reads against the app's persisted write queue. */
    val storageGate = Mutex()
    /** App-scoped so a pen connection survives configuration changes but is still editor-bound. */
    val penHaptics by lazy { PenHapticsManager(this) }
    val focalStudy by lazy { FocalStudyManager(this) }
    val focalProgress by lazy { com.folio.notes.progress.ExamProgressManager(this) }

    override fun onCreate() {
        super.onCreate()
        LibraryAutoBackup.ensureScheduled(this)
    }
}
