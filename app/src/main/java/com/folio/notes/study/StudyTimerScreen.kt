package com.folio.notes.study

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.notes.FolioApplication
import com.folio.notes.app.StudyViewModel
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.model.Notebook
import com.folio.notes.progress.StatTileData
import com.folio.notes.progress.StatTiles
import com.folio.notes.ui.FolioIcons
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/** A peer of Library and Mistakes, not a dialog or a notebook-dependent tool. */
@Composable
fun StudyTimerScreen(notebooks: List<Notebook>, initialExamTimer: ExamTimerState, onAccount: () -> Unit) {
    val feature: StudyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val examTimer by feature.state.collectAsStateWithLifecycle()
    val manager = (LocalContext.current.applicationContext as FolioApplication).focalStudy
    val state by manager.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(manager.now()) }
    LaunchedEffect(manager) {
        while (true) {
            now = manager.now()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val zone = ZoneId.systemDefault()
    val entries = remember(state.entries, state.userId) { state.visibleEntries }
    val longest = remember(entries, now / 60_000L, zone) { focalLongestStudyStreak(entries, now, zone) }
    val average = remember(entries, now / 60_000L, zone) { focalLastSevenDaysDailyAverageMinutes(entries, now, zone) }
    Column(Modifier.fillMaxSize()) {
        FocalScreenHeading("Study timer", studySyncIcon(state), state.syncStatus, state.syncing,
            canSync = state.userId != null && state.configured && !state.needsSignIn, onSync = manager::retry,
            trouble = state.error != null && !state.syncing)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            StatTiles(listOf(
                StatTileData(FolioIcons.Flame, if (longest == 1) "1 day" else "$longest days", "Longest streak"),
                StatTileData(FolioIcons.Calendar, "${formatStudyMinutes(average)}/day", "Weekly average · last 7 days"),
            ))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FocalStudyContent(null, examTimer, dedicated = true, notebooks = notebooks, onAccount = onAccount)
        }
    }
}
