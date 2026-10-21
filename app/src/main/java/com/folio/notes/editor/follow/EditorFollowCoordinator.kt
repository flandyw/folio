package com.folio.notes.editor.follow

import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.folio.notes.ink.InkView
import com.folio.notes.model.PeekAnchor
import com.folio.notes.workspace.WorkspaceViewport

/** Page-visit follow state, deliberately independent of global follow preferences on canvases. */
internal class EditorFollowState(
    val canvasResponse: MutableState<CanvasWritingSession?>,
    val overviewReturn: MutableState<WorkspaceViewport?>,
    val paused: MutableState<Boolean>,
) {
    val status = mutableStateOf(WritingFollowStatus())
    val zoomTarget = mutableStateOf<PeekAnchor?>(null)
    val zoomInitialState = mutableStateOf<WritingFollowState?>(null)
    val zoomGesture = mutableStateOf(false)
}

@Composable internal fun rememberEditorFollowState(noteId: String, pageId: String): EditorFollowState {
    val response = rememberSaveable(noteId, pageId, stateSaver = Saver<CanvasWritingSession?, String>(
        save = { it?.encode().orEmpty() }, restore = { CanvasWritingSession.decode(it) },
    )) { mutableStateOf<CanvasWritingSession?>(null) }
    val overview = rememberSaveable(noteId, pageId, stateSaver = Saver<WorkspaceViewport?, String>(
        save = { it?.let { v -> "${v.canvasX},${v.canvasY},${v.canvasZoom}" }.orEmpty() },
        restore = { raw -> raw.split(",").mapNotNull(String::toFloatOrNull).takeIf { it.size == 3 }
            ?.let { (x, y, zoom) -> WorkspaceViewport(canvasX = x, canvasY = y, canvasZoom = zoom) } },
    )) { mutableStateOf<WorkspaceViewport?>(null) }
    val paused = rememberSaveable(noteId) { mutableStateOf(false) }
    return remember(noteId, pageId, response, overview, paused) { EditorFollowState(response, overview, paused) }
}

/** Persisted answer-area preference adapter and native follow binding, never a content owner. */
internal class EditorFollowCoordinator(private val prefs: SharedPreferences, private val key: String) {
    private fun decode(raw: String?): WritingLane? = runCatching {
        val v = raw?.split(",")?.map(String::toFloat) ?: return@runCatching null
        if (v.size != 4 || !v.all(Float::isFinite)) return@runCatching null
        WritingLane(v[0], v[1], v[2], v[3]).takeIf { it.right > it.left && it.bottom > it.top }
    }.getOrNull()
    val region = mutableStateOf(decode(prefs.getString(key, null)))
    val regions = mutableStateOf(prefs.getString("$key.areas", null)?.split(";")?.mapNotNull(::decode) ?: listOfNotNull(region.value))
    fun configure(view: InkView, infinite: Boolean, response: CanvasWritingSession?, preferences: FollowPreferences,
        paused: Boolean, onResponse: (CanvasWritingSession) -> Unit, onStatus: (WritingFollowStatus) -> Unit) {
        view.canvasWritingSession = if (infinite) response else null
        view.onCanvasWritingSession = onResponse
        view.followPreferences = preferences
        view.writingRegions = if (infinite) emptyList() else regions.value
        view.onWritingRegions = { lanes ->
            regions.value = lanes
            prefs.edit().putString("$key.areas", lanes.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }).apply()
        }
        view.writingRegion = if (infinite) null else region.value
        view.onWritingRegion = { lane ->
            region.value = lane
            prefs.edit().apply { if (lane == null) remove(key) else putString(key, "${lane.left},${lane.top},${lane.right},${lane.bottom}") }.apply()
        }
        if (view.isWritingFollowManuallyPaused != paused) {
            if (paused) view.pauseWritingFollow() else view.resumeWritingFollow()
        }
        view.onFollowStatus = onStatus
    }
}
