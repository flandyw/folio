package com.folio.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Feature presentation owners share the application engine; none retains a second notebook. */
internal class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as FolioApplication).runtime
    val state = runtime.state
    val documents: DocumentReader = runtime._state
    val commands = runtime.documentEngine
}
internal class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as FolioApplication).runtime
    val state = runtime.shellState
    val commands = runtime.notebookCatalog
}
internal class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as FolioApplication).runtime
    val state = runtime.shellState
    val commands = runtime.workspaceManager
}
internal class StudyViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as FolioApplication).runtime
    val state = runtime.examSessionManager.state
    val commands = runtime.examSessionManager
}
