package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier

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
