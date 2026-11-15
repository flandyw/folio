package com.folio.notes.study

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.app.StudyViewModel
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.model.Notebook

/** A peer of Library and Mistakes, not a dialog or a notebook-dependent tool. */
@Composable
fun StudyTimerScreen(notebooks: List<Notebook>, initialExamTimer: ExamTimerState, onAccount: () -> Unit) {
    val feature: StudyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val examTimer by feature.state.collectAsStateWithLifecycle()
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        FocalScreenHeading("Study timer", studySyncIcon(state), state.syncStatus, state.syncing,
            canSync = state.userId != null && state.configured && !state.needsSignIn, onSync = manager::retry,
            onAccount = onAccount, trouble = state.error != null && !state.syncing)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FocalStudyContent(null, examTimer, dedicated = true, notebooks = notebooks, onAccount = onAccount)
        }
    }
}
