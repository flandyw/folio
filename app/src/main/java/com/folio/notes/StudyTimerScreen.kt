package com.folio.notes

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A peer of Library and Mistakes, not a dialog or a notebook-dependent tool. */
@Composable
fun StudyTimerScreen(notebooks: List<Notebook>, examTimer: ExamTimerState, onAccount: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FolioScreenHeading("Study timer") { FocalAccountButton(onAccount) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FocalStudyContent(null, examTimer, dedicated = true, notebooks = notebooks, onAccount = onAccount)
        }
    }
}
