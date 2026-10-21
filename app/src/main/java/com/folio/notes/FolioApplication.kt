package com.folio.notes

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.folio.notes.app.AppPrefs
import com.folio.notes.app.FolioRuntime
import com.folio.notes.backup.LibraryAutoBackup
import com.folio.notes.chatgpt.ChatGptManager
import com.folio.notes.editor.page.PageThumbnailCache
import com.folio.notes.ink.InkTaper
import com.folio.notes.ink.pen.PenHapticsManager
import com.folio.notes.ink.pen.PenWritingFeedback
import com.folio.notes.progress.ExamProgressManager
import com.folio.notes.storage.NoteRepository
import com.folio.notes.study.FocalStudyManager
import com.folio.notes.update.FolioUpdates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class FolioApplication : Application() {
    internal val chatGpt by lazy { ChatGptManager(this) }
    val updates by lazy { FolioUpdates(this) }
    val repository by lazy { NoteRepository(this) }
    internal val runtime by lazy { FolioRuntime(this, SavedStateHandle()) }
    val thumbnails by lazy { PageThumbnailCache(this, repository) }
    val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes library backup reads against the app's persisted write queue. */
    val storageGate = Mutex()
    /** App-scoped so a pen connection survives configuration changes but is still editor-bound. */
    val penHaptics by lazy { PenHapticsManager(this) }
    /** Continuous in-pen writing feedback, on with the same setting and lifetime as [penHaptics]. */
    val penFeedback by lazy { PenWritingFeedback(this) }
    val focalStudy by lazy { FocalStudyManager(this) }
    val focalProgress by lazy { ExamProgressManager(this) }

    /** Held for the process lifetime: SharedPreferences keeps its listeners weakly. */
    private val inkLookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == AppPrefs.INK_TAPER) InkTaper.configure(AppPrefs.inkTaper(prefs.getFloat(AppPrefs.INK_TAPER, 0f)))
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("preferences", 0).let {
            InkTaper.configure(AppPrefs.inkTaper(it.getFloat(AppPrefs.INK_TAPER, 0f)))
            it.registerOnSharedPreferenceChangeListener(inkLookListener)
        }
        LibraryAutoBackup.ensureScheduled(this)
    }
}
