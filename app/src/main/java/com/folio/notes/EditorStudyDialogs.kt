@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, kotlinx.coroutines.FlowPreview::class)
package com.folio.notes

import com.folio.notes.progress.ExamRecordReview
import com.folio.notes.progress.countsAsExam

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

internal data class EditorMarkingPresentation(val assist: Boolean, val busy: Boolean, val status: String?,
    val zones: List<MarkZone>, val color: Int)

@Composable internal fun EditorStudyDialogs(state: FolioState, model: FolioViewModel, overlays: EditorOverlayState,
    marking: EditorMarkingPresentation, onAssist: (Boolean) -> Unit, onColor: (Int) -> Unit) {
    val note = state.active ?: return
    val page = state.page ?: return
    var timerPanel by overlays.timer
    var studyPanel by overlays.study
    var examPanel by overlays.exam
    var markDialog by overlays.markDialog
    var focalReview by overlays.focalReview
    val markAssist = marking.assist
    val markScanBusy = marking.busy
    val markScanStatus = marking.status
    val markZones = marking.zones
    val markingColor = marking.color
    if (timerPanel) TimingPanel(
        timer = state.timer,
        note = note,
        onOpenStudy = { studyPanel = true },
        onDismiss = { timerPanel = false },
        onStartTimer = model::startTimer,
        onStopTimer = { model.stopTimer() },
        onAdjustTimer = model::adjustTimer,
        onSkipTimer = model::skipTimerPhase,
        onPauseTimer = model::toggleTimerPause
    )
    if (studyPanel) FocalStudyPanel(note, state.timer, onDismiss = { studyPanel = false })
    if (examPanel) ExamDetailsPanel(
        note = note,
        onDismiss = { examPanel = false },
        onSave = { tags -> model.updateExamTags(note.id, tags); examPanel = false },
        onRecordMark = { attempt -> model.recordAttempt(note.id, attempt, note.longResponse?.attemptFor(page.id)?.id) },
        onDeleteAttempt = { attempt -> model.deleteAttempt(note.id, attempt.id) },
        suggestedSeconds = state.lastTimedSeconds,
        onRecordAndSave = if (!note.countsAsExam) null else { attempt ->
            model.recordAttempt(note.id, attempt, note.longResponse?.attemptFor(page.id)?.id)
            focalReview = note to attempt
        }
    )
    if (markDialog) ScoreDialog(
        total = note.exam.marksTotal,
        defaultSeconds = state.lastTimedSeconds,
        showDetection = note.pages.any { it.pdfIndex != null },
        loadPages = model::pagesForMarking,
        markAssist = markAssist,
        markScanBusy = markScanBusy,
        markScanStatus = markScanStatus,
        markZoneCount = markZones.size,
        markZoneTotal = MarkZones.total(markZones),
        onMarkAssist = onAssist,
        markColor = markingColor,
        onMarkColor = onColor,
        onDismiss = { markDialog = false },
        onRecord = { score, total, seconds, timed ->
            model.recordAttempt(note.id, ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed))
            markDialog = false
        },
        // SACs, topic tests and notes never feed the exam log, so only real papers can be saved to Focal.
        onRecordAndSave = if (!note.countsAsExam) null else { score, total, seconds, timed ->
            val attempt = ExamAttempt(score = score, total = total, secondsTaken = seconds, timed = timed)
            model.recordAttempt(note.id, attempt)
            markDialog = false
            focalReview = note to attempt
        }
    )
    focalReview?.let { (reviewed, attempt) ->
        ExamRecordReview(reviewed, attempt) { focalReview = null }
    }

}
