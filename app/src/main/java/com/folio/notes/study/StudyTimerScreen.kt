package com.folio.notes.study

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.app.StudyViewModel
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.model.Notebook
import com.folio.notes.ui.FolioScreenHeading

/** A peer of Library and Mistakes, not a dialog or a notebook-dependent tool. */
@Composable
fun StudyTimerScreen(notebooks: List<Notebook>, initialExamTimer: ExamTimerState, onAccount: () -> Unit) {
    val feature: StudyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val examTimer by feature.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        FolioScreenHeading("Study timer") { FocalAccountButton(onAccount) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FocalStudyContent(null, examTimer, dedicated = true, notebooks = notebooks, onAccount = onAccount)
        }
    }
}
