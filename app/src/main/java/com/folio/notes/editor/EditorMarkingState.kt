package com.folio.notes.editor

import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import com.folio.notes.editor.dialogs.EditorMarkingPresentation
import com.folio.notes.app.FolioViewModel
import com.folio.notes.marking.MarkZone
import com.folio.notes.marking.MarkZones
import com.folio.notes.marking.Marking
import com.folio.notes.model.Notebook

/**
 * The marker's pen colour and assist switch (app-wide preferences) and one notebook's mark
 * allocations: printed "[n marks]" labels found by the PDF scan plus the boxes drawn by hand
 * where the scan missed one.
 */
@Stable internal class EditorMarkingState(private val prefs: SharedPreferences, notebookId: String) {
    private val zonesKey = "marking.zones.$notebookId"
    var color by mutableIntStateOf(prefs.getInt(Marking.PREF_COLOR, Marking.DEFAULT_COLOR))
        private set
    var assist by mutableStateOf(prefs.getBoolean(Marking.PREF_ASSIST, true))
        private set
    var scanStatus by mutableStateOf<String?>(null)
        private set
    var scanBusy by mutableStateOf(false)
        private set
    private var scanned by mutableStateOf(emptyList<MarkZone>())
    // Allocations the marker boxed by hand because the scan missed them; kept per notebook.
    private var manual by mutableStateOf(MarkZones.decode(prefs.getString(zonesKey, null)))
    val zones: List<MarkZone> get() = scanned + manual
    val presentation get() = EditorMarkingPresentation(assist, scanBusy, scanStatus, zones, color)

    fun changeAssist(value: Boolean) { assist = value; prefs.edit().putBoolean(Marking.PREF_ASSIST, value).apply() }
    fun changeColor(value: Int) { color = value; prefs.edit().putInt(Marking.PREF_COLOR, value).apply() }

    /** Replaces a hand-drawn box [old] with [new]: a null [old] adds one, a null [new] removes it. */
    fun replaceZone(old: MarkZone?, new: MarkZone?) {
        manual = manual.filterNot { it == old } + listOfNotNull(new)
        prefs.edit().putString(zonesKey, MarkZones.encode(manual)).apply()
    }

    /** Reads the printed allocations of [note]'s PDF pages while assist is on, reporting progress as it goes. */
    suspend fun scan(note: Notebook, model: FolioViewModel) {
        scanned = emptyList()
        scanStatus = null
        if (!assist || note.pages.none { it.pdfIndex != null }) return
        scanBusy = true
        scanStatus = "Looking for printed marks…"
        try {
            scanned = model.repository.pdfs.pdfMarkZones(note.id, note.pages) { zones, status ->
                scanned = zones
                scanStatus = status
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            scanStatus = "Couldn’t read printed marks. Turn the switch off and on to retry."
        } finally { scanBusy = false }
    }
}

/** Marking state for [note], rescanned when assist is switched or the PDF pages change shape. */
@Composable internal fun rememberEditorMarking(prefs: SharedPreferences, note: Notebook, model: FolioViewModel): EditorMarkingState {
    val marking = remember(note.id) { EditorMarkingState(prefs, note.id) }
    val geometry = note.pages.map { Triple(it.pdfIndex, it.width, it.height) }
    LaunchedEffect(note.id, marking.assist, geometry) { marking.scan(note, model) }
    return marking
}
