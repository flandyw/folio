package com.folio.notes.study

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.folio.notes.exam.ExamTimerState
import com.folio.notes.mistakes.sync.FocalAccountStatus
import com.folio.notes.mistakes.sync.focalSyncError
import com.folio.notes.model.Notebook
import com.folio.notes.sync.FocalSupabaseConnection
import com.folio.notes.sync.SupabaseSyncRemote
import com.folio.notes.sync.focalSyncDeviceId
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Durable local sessions. The server is reached directly; nothing waits in a local queue. */
class FocalStudyManager(context: Context) {
    @Volatile private var serverClockAnchor: FocalServerClockAnchor? = null
    private val localClockAnchor = FocalServerClockAnchor(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    private val timingPreferences = context.getSharedPreferences("focal-study-timing", Context.MODE_PRIVATE)
    private val timingProcessId = UUID.randomUUID().toString()
    private val timingBootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun now(): Long = focalEstimatedServerNow(
        serverClockAnchor ?: localClockAnchor, SystemClock.elapsedRealtime(), System.currentTimeMillis()
    )

    private fun observeServerNow(value: String?) {
        val serverMillis = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        serverClockAnchor = focalObserveServerClock(serverClockAnchor, serverMillis, SystemClock.elapsedRealtime())
    }
    private val file = AtomicFile(File(context.filesDir, "focal-study.json"))
    private val gate = Mutex()
    // Study sessions and mistake review share one Supabase client and one account.
    private val connection = FocalSupabaseConnection.of(context)
    private val client = connection.client
    private val lifecycle = connection.lifecycle
    private val syncRemote = SupabaseSyncRemote(client, lifecycle)
    private val deviceId = focalSyncDeviceId(context)
    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()
    private val foreground = MutableStateFlow(false)
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private fun requestSync() { syncRequests.trySend(Unit) }
    private var remoteNoticeReadyUser: String? = null
    // Change ids this device authored, so their echo in the feed is never read as another device.
    private val ownChangeIds = LinkedHashSet<String>()
    // Session id to the change id already reported, so a loop that re-reads the same row cannot
    // raise the same notice over and over.
    private val noticedExternalChanges = HashMap<String, String>()

    private fun rememberOwnChange(changeId: String) {
        ownChangeIds += changeId
        // Insertion order, so the oldest id is the one to drop once the window is full.
        while (ownChangeIds.size > MAX_OWN_CHANGE_IDS) {
            val oldest = ownChangeIds.firstOrNull() ?: return
            ownChangeIds.remove(oldest)
        }
    }

    private fun clearSyncMemory() {
        ownChangeIds.clear()
        noticedExternalChanges.clear()
    }

    fun setForeground(value: Boolean) {
        foreground.value = value
        if (value) lifecycle.retry()
        // Android may kill a stopped process before the next periodic heartbeat. Background
        // study keeps running in memory, but its recoverable copy is saved right now.
        if (!value) persist()
    }

    /** Never carry one account's local timer into another account's controls. */
    private fun detachFocusForAccount(userId: String?) {
        val focus = _state.value.focus ?: return
        val owner = _state.value.entries.firstOrNull { it.id == focus.sessionId }?.userId ?: return
        if (owner == userId) return
        val now = now()
        saveFocusEntry(focus.pause(now), now, forceUpload = true, detach = true)
    }

    init {
        // Auth refresh and foreground changes may cancel their collectors, never this
        // worker's RPC or receipt handling. Bursts of realtime wakeups share one pull.
        scope.launch { for (request in syncRequests) sync() }
        scope.launch {
            lifecycle.state.collect { account ->
                account.user?.let { user ->
                    detachFocusForAccount(user.id)
                    if (_state.value.userId != user.id) { remoteNoticeReadyUser = null; clearSyncMemory() }
                    _state.update { it.copy(userId = user.id, email = user.email,
                        needsSignIn = account.needsSignIn, reconnecting = account.status == FocalAccountStatus.RECONNECTING,
                        error = if (it.userId == user.id) it.error else null,
                        subjects = if (it.remoteSubjectsUser == user.id) it.subjects else FocalSubjects.builtIn) }
                    if (account.status == FocalAccountStatus.CONNECTED) requestSync()
                }
                if (account.status == FocalAccountStatus.SIGNED_OUT) {
                    detachFocusForAccount(null)
                    remoteNoticeReadyUser = null
                    clearSyncMemory()
                    _state.update { it.copy(userId = null, email = null, needsSignIn = false, reconnecting = false) }
                }
            }
        }
        scope.launch {
            combine(_state.map { it.userId }.distinctUntilChanged(), foreground) { user, visible -> user to visible }
                .collectLatest { (user, visible) ->
                    if (visible && user != null) { requestSync(); watchRemoteSessions(user) }
                }
        }
        scope.launch {
            while (true) {
                delay(FOCUS_CHECKPOINT_INTERVAL_MS)
                if (_state.value.focus?.resumedAt != null || _state.value.entries.any {
                    it.active && !it.paused && it.metadata != null
                }) persist()
            }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                if (_state.value.userId != null && (foreground.value ||
                    _state.value.entries.any { !it.synced && (it.userId == null || it.userId == _state.value.userId) })) requestSync()
            }
        }
    }

    private suspend fun watchRemoteSessions(user: String) {
        while (foreground.value && _state.value.userId == user) {
            val channel = client.realtime.channel("folio-study-$user")
            try {
                coroutineScope {
                    for (entity in listOf("study_sessions", "custom_subjects")) {
                        channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                            table = "sync_log"
                            filter("entity", FilterOperator.EQ, entity)
                        }.onEach { requestSync() }.launchIn(this)
                    }
                    // A reconnect can miss messages while the socket is down. Joining also
                    // closes the gap between the initial pull and this subscription.
                    channel.status.onEach { status ->
                        if (status == RealtimeChannel.Status.SUBSCRIBED) requestSync()
                    }.launchIn(this)
                    channel.subscribe(blockUntilSubscribed = true)
                    awaitCancellation()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { delay(7_000) }
            finally { withContext(NonCancellable) { runCatching { client.realtime.removeChannel(channel) } } }
        }
    }

    private fun load(): FocalStudyState = runCatching {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@runCatching FocalStudyState()
        val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        val rows = data.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until rows.length()).mapNotNull { index -> runCatching {
            val row = rows.getJSONObject(index)
            FocalStudyEntry(
                id = row.getString("id"), changeId = row.getString("changeId"),
                notebookId = row.optString("notebookId").ifBlank { null }, title = row.getString("title"),
                subjectId = row.optString("subjectId").ifBlank { null }, kind = row.getString("kind"),
                startedAt = row.getLong("startedAt"), endedAt = row.getLong("endedAt"),
                activeMillis = row.getLong("activeMillis"), notes = row.optString("notes"),
                confidence = row.optInt("confidence").takeIf { it in 1..5 },
                userId = row.optString("userId").ifBlank { null }, synced = row.optBoolean("synced"),
                revision = row.optLong("revision"),
                completed = row.optBoolean("completed", true), planned = row.optBoolean("planned") ||
                    row.optString("remotePayload").let { raw -> runCatching {
                        JSONObject(raw).optJSONObject("execution")?.optString("state") == "planned"
                    }.getOrDefault(false) }, deleted = row.optBoolean("deleted"),
                paused = row.optBoolean("paused"), examPhase = row.optString("examPhase").lowercase().ifBlank { null },
                examPhaseBeforePause = row.optString("examPhaseBeforePause").lowercase().ifBlank { null },
                remotePayload = row.optString("remotePayload").ifBlank { null },
                planDraft = row.optString("planDraft").ifBlank { null },
                metadata = row.optString("metadata").ifBlank { null },
                notebookTitle = row.optString("notebookTitle").ifBlank { null },
                examLastBoundaryAt = row.optLong("examLastBoundaryAt").takeIf { it > 0L },
                examBoundaries = (row.optJSONArray("examBoundaries") ?: JSONArray()).let { saved ->
                    (0 until saved.length()).mapNotNull { position -> runCatching {
                        val event = saved.getJSONObject(position)
                        FocalExamBoundary(id = event.getString("id"), action = event.getString("action"),
                            phase = event.getString("phase"), at = event.getLong("at"),
                            elapsedMs = event.getLong("elapsedMs"),
                            monotonicAt = event.optLong("monotonicAt").takeIf { it > 0L },
                            bootCount = event.optInt("bootCount", -1),
                            request = event.optString("request").takeIf { it.isNotBlank() && it != "null" })
                    }.getOrNull() }
                },
                intervals = focalDecodeIntervals(row.optJSONArray("intervals")),
                pendingBlocks = row.optJSONArray("pendingBlocks")?.let(::focalDecodeIntervals)
            )
        }.getOrNull() }
        val focus = data.optJSONObject("focus")?.let { row -> runCatching {
            FocalFocus(sessionId = row.optString("sessionId").ifBlank { UUID.randomUUID().toString() },
                notebookId = row.optString("notebookId").takeIf { it.isNotBlank() && it != "null" }, title = row.getString("title"),
                subjectId = row.optString("subjectId").ifBlank { null }, startedAt = row.getLong("startedAt"),
                notebookTitle = row.optString("notebookTitle").ifBlank { null },
                resumedAt = row.optLong("resumedAt").takeIf { it > 0 }, accumulatedMillis = row.optLong("accumulatedMillis"),
                attended = row.optBoolean("attended"),
                parkReason = row.optString("parkReason").takeIf { it.isNotBlank() && it != "null" },
                intervals = focalDecodeIntervals(row.optJSONArray("intervals")))
        }.getOrNull() }
        // Recovery closes at the last durable checkpoint. A new process cannot reuse the
        // prior process's monotonic delta, and a reboot cannot reuse elapsedRealtime at all.
        // A session saved while running (resumedAt set) was cut off by the process ending.
        val recoveredFocus = focus?.let(::focalRecoveredFocus)?.takeUnless { current ->
            entries.any { it.id == current.sessionId && (it.deleted || it.completed) }
        }
        val builtInIds = FocalSubjects.builtIn.mapTo(mutableSetOf()) { it.id }
        val savedSubjects = (data.optJSONArray("subjects") ?: JSONArray()).let { array ->
            (0 until array.length()).mapNotNull { index -> runCatching {
                val subject = array.getJSONObject(index)
                FocalSubject(subject.getString("id"), subject.getString("name"))
            }.getOrNull() }.filterNot { it.id in builtInIds }
        }
        FocalStudyState(entries = entries.map { entry ->
            if (recoveredFocus?.sessionId == entry.id && (!entry.paused || entry.intervals != recoveredFocus.intervals))
                focalRecoverFocus(entry, recoveredFocus) else entry
        }, focus = recoveredFocus, subjects = FocalSubjects.builtIn + savedSubjects,
            remoteRevision = data.optLong("remoteRevision"), remoteRevisionUser = data.optString("remoteRevisionUser").ifBlank { null },
            remoteLamport = data.optLong("remoteLamport"),
            remoteSubjectsRevision = data.optLong("remoteSubjectsRevision").coerceAtLeast(0L),
            remoteSubjectsUser = data.optString("remoteSubjectsUser").ifBlank { null },
            )
    }.getOrDefault(FocalStudyState())

    @Synchronized private fun persist(): Boolean {
        // Every write is a heartbeat, including sync receipts and unrelated entry changes.
        // Store the focus and its entry at the same cutoff without pausing the live timer.
        // Otherwise an ordinary sync write can replace a good heartbeat with the old start.
        val snapshot = focalStudyCheckpoint(_state.value, now())
        val rows = JSONArray()
        snapshot.entries.forEach { entry -> rows.put(JSONObject()
            .put("id", entry.id).put("changeId", entry.changeId).put("notebookId", entry.notebookId)
            .put("title", entry.title).put("subjectId", entry.subjectId).put("kind", entry.kind)
            .put("startedAt", entry.startedAt).put("endedAt", entry.endedAt)
            .put("activeMillis", entry.activeMillis).put("notes", entry.notes)
            .put("confidence", entry.confidence).put("userId", entry.userId).put("synced", entry.synced)
            .put("revision", entry.revision)
            .put("completed", entry.completed).put("planned", entry.planned).put("deleted", entry.deleted)
            .put("paused", entry.paused).put("examPhase", entry.examPhase)
            .put("examPhaseBeforePause", entry.examPhaseBeforePause)
            .put("remotePayload", entry.remotePayload).put("planDraft", entry.planDraft).put("notebookTitle", entry.notebookTitle)
            .put("metadata", entry.metadata)
            .put("examLastBoundaryAt", entry.examLastBoundaryAt)
            .put("pendingBlocks", entry.pendingBlocks?.let(::focalEncodeIntervals))
            .put("examBoundaries", JSONArray().also { array -> entry.examBoundaries.forEach { event ->
                array.put(JSONObject().put("id", event.id).put("action", event.action)
                    .put("phase", event.phase).put("at", event.at).put("elapsedMs", event.elapsedMs)
                    .put("monotonicAt", event.monotonicAt).put("bootCount", event.bootCount)
                    .put("request", event.request))
            } })
            .put("intervals", focalEncodeIntervals(entry.intervals))) }
        val focus = snapshot.focus?.let { JSONObject().put("sessionId", it.sessionId).put("notebookId", it.notebookId)
            .put("title", it.title).put("subjectId", it.subjectId).put("notebookTitle", it.notebookTitle)
            .put("startedAt", it.startedAt).put("attended", it.attended).put("parkReason", it.parkReason)
            .put("resumedAt", it.resumedAt).put("accumulatedMillis", it.accumulatedMillis)
            .put("intervals", focalEncodeIntervals(it.intervals)) }
        val bytes = JSONObject().put("entries", rows).put("focus", focus)
            .put("remoteRevision", snapshot.remoteRevision).put("remoteRevisionUser", snapshot.remoteRevisionUser)
            .put("remoteLamport", snapshot.remoteLamport)
            .put("subjects", JSONArray(snapshot.subjects.filterNot { subject ->
                FocalSubjects.builtIn.any { it.id == subject.id }
            }.map { JSONObject().put("id", it.id).put("name", it.name) }))
            .put("remoteSubjectsRevision", snapshot.remoteSubjectsRevision).put("remoteSubjectsUser", snapshot.remoteSubjectsUser)
            .toString().toByteArray()
        return try {
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            _state.update { it.copy(localSaveFailed = false,
                error = if (it.localSaveFailed) null else it.error) }
            true
        }
        catch (_: Exception) {
            _state.update { it.copy(localSaveFailed = true,
                error = "Could not save study sessions on this device. Check free storage, then retry while Folio is still open.") }
            false
        }
    }

    /** Starts the stopwatch; false when another session is already running or this one is open. */
    fun startFocus(note: Notebook?, subjectId: String?, now: Long = this.now(), attended: Boolean = false): Boolean {
        if (!_state.value.canStartFocus || _state.value.visibleEntries.any { it.active && !it.paused }) return false
        val focus = FocalFocus(notebookId = note?.id,
            title = focalSessionTitle(subjectId, _state.value.subjects), subjectId = subjectId,
            startedAt = now, resumedAt = now, intervals = listOf(FocalStudyInterval(now, null)), notebookTitle = note?.title,
            attended = attended)
        saveFocusEntry(focus, now)
        return _state.value.focus?.sessionId == focus.sessionId
    }

    /**
     * Changes the subject of the open session, for a timer started under the wrong one. Its title
     * follows only while it is still the generated "<subject> Focus" one; a plan keeps its title.
     * Published as a save_progress on the same row, so the time recorded so far is untouched.
     */
    fun changeFocusSubject(subjectId: String?, now: Long = this.now()): Boolean {
        val state = _state.value
        val focus = state.visibleFocus ?: return false
        if (focus.subjectId == subjectId) return true
        val generated = focus.title == focalSessionTitle(focus.subjectId, state.subjects)
        val next = focus.copy(subjectId = subjectId,
            title = if (generated) focalSessionTitle(subjectId, state.subjects) else focus.title)
        saveFocusEntry(next, now, forceUpload = true)
        return _state.value.focus?.subjectId == subjectId
    }
    fun savePlan(original: FocalStudyEntry, title: String, subjectId: String?, description: String,
                 topics: List<String>, slots: List<FocalStudyInterval>): Boolean {
        var saved: FocalStudyEntry? = null
        _state.update { state ->
            val current = state.entries.firstOrNull { it.id == original.id }
            require(current != null || original.revision == 0L) { "This plan is no longer available." }
            require(current?.userId == null || current.userId == state.userId) { "Connect the account that owns this plan." }
            require(current == null || current.revision == original.revision) { "This plan changed. Close and reopen it to edit the latest version." }
            val edited = focalPlanEdit(current ?: original, title, subjectId, description, topics, slots)
                .copy(userId = current?.userId ?: state.userId)
            saved = edited
            state.copy(entries = listOf(edited) + state.entries.filterNot { it.id == edited.id }, error = null)
        }
        val entry = saved ?: return false
        val durable = persist()
        if (durable) scope.launch { publishEntry(entry) }
        return durable
    }

    fun deletePlan(id: String) {
        var saved: FocalStudyEntry? = null
        _state.update { state ->
            saved = null
            val current = state.visibleEntries.firstOrNull { it.id == id && it.planned } ?: return@update state
            val deleted = current.copy(deleted = true, planned = false, synced = false, changeId = UUID.randomUUID().toString())
            saved = deleted
            state.copy(entries = state.entries.map { if (it.id == id) deleted else it }, error = null)
        }
        val entry = saved ?: return
        if (persist()) scope.launch { publishEntry(entry) }
    }

    /** Starts a plan as the stopwatch; false when it can't start (already running, conflicted draft, gone). */
    fun startPlannedFocus(id: String, note: Notebook?, now: Long = this.now(), attended: Boolean = false): Boolean {
        val state = _state.value
        if (!state.canStartFocus || state.visibleEntries.any { it.active && !it.paused }) return false
        val entry = state.visibleEntries.firstOrNull { it.id == id } ?: return false
        val focus = focalPlannedFocus(entry, note, now, attended) ?: return false
        saveFocusEntry(focus, now, forceUpload = true, startingPlanned = true)
        return _state.value.focus?.sessionId == focus.sessionId
    }

    /** Picks finished study from today back up as the stopwatch: the same session, its time kept. */
    fun continueFocus(id: String, now: Long = this.now(), attended: Boolean = false): Boolean {
        val entry = _state.value.visibleEntries.firstOrNull { it.id == id }?.takeIf { focalCanContinue(it, now) } ?: return false
        val focus = FocalFocus(notebookId = entry.notebookId, title = entry.title, subjectId = entry.subjectId,
            startedAt = entry.startedAt, resumedAt = now, accumulatedMillis = entry.activeMillis, sessionId = entry.id,
            intervals = entry.intervals + FocalStudyInterval(now, null), notebookTitle = entry.notebookTitle, attended = attended)
        saveFocusEntry(focus, now, forceUpload = true, continuing = true)
        return _state.value.focus?.sessionId == id
    }

    fun toggleFocus(now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        if (focus.resumedAt == null && _state.value.visibleEntries.any {
            it.active && !it.paused && it.id != focus.sessionId
        }) return
        val next = if (focus.resumedAt == null) focus.resume(now) else focus.pause(now)
        // Publish pause/resume boundaries. While running, the macOS client can derive elapsed
        // time from the open interval's start and does not need a checkpoint every few seconds.
        saveFocusEntry(next, now, forceUpload = true)
    }
    /**
     * Parks a running session because the app stopped looking at it. Like a manual pause it is
     * published as a boundary, so other Focal clients stop counting too. With [attendedOnly] a
     * session started from the Study page, which is meant to run in the background, is left alone.
     */
    fun parkFocus(reason: String, attendedOnly: Boolean = false, now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        if (focus.resumedAt == null || (attendedOnly && !focus.attended)) return
        val next = focus.pause(now, reason)
        saveFocusEntry(next, now, forceUpload = true)
    }
    fun discardFocus(now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        saveFocusEntry(focus.pause(now), now, deleted = true)
    }
    fun finishFocus(notes: String, confidence: Int?, now: Long = this.now()) {
        val focus = _state.value.focus ?: return
        val active = focus.elapsed(now)
        if (active < 1_000L) { discardFocus(now); return }
        saveFocusEntry(focus.pause(now), now, completed = true, notes = notes.trim(), confidence = confidence)
    }

    private fun saveFocusEntry(focus: FocalFocus, now: Long, completed: Boolean = false, deleted: Boolean = false,
                               notes: String? = null, confidence: Int? = null, forceUpload: Boolean = false,
                               detach: Boolean = false, startingPlanned: Boolean = false, continuing: Boolean = false) {
        val changeId = UUID.randomUUID().toString()
        var saved: FocalStudyEntry? = null
        _state.update { state ->
            saved = null
            val previous = state.entries.firstOrNull { it.id == focus.sessionId }
            if (previous?.deleted == true || (previous?.completed == true && !continuing)) return@update state
            if (continuing && (previous == null || !focalCanContinue(previous, now) ||
                    !state.canStartFocus || state.visibleEntries.any { it.active && !it.paused })) return@update state
            if (startingPlanned && (previous?.planned != true || previous.kind != "study" ||
                    (previous.userId != null && previous.userId != state.userId) ||
                    !state.canStartFocus || state.visibleEntries.any { it.active && !it.paused })) return@update state
            val entry = FocalStudyEntry(id = focus.sessionId, notebookId = focus.notebookId, title = focus.title,
                revision = previous?.revision ?: 0L, remotePayload = previous?.remotePayload,
                planDraft = previous?.planDraft ?: previous?.takeIf { startingPlanned }?.let(::focalPlanStartPayload),
                subjectId = focus.subjectId, kind = "study", startedAt = focus.startedAt,
                endedAt = now.coerceAtLeast(focus.startedAt + 1_000L), activeMillis = focus.elapsed(now),
                notes = (notes ?: previous?.notes.orEmpty()).take(500),
                confidence = (if (completed) confidence else previous?.confidence)?.takeIf { it in 1..5 },
                userId = previous?.userId ?: state.userId,
                completed = completed, deleted = deleted, paused = focus.resumedAt == null || completed || deleted,
                intervals = focus.intervals, changeId = changeId, notebookTitle = focus.notebookTitle,
                synced = if (!completed && !deleted && !forceUpload) previous?.synced ?: false else false)
            saved = entry
            // A heartbeat or receipt must never see new focus state with the old entry, or a
            // removed focus before its terminal entry has been recorded.
            state.copy(focus = focus.takeUnless { completed || deleted || detach },
                entries = listOf(entry) + state.entries.filterNot { it.id == entry.id }, error = null)
        }
        val entry = saved ?: return
        if (persist()) scope.launch { publishEntry(entry) }
    }

    /** Practice owns its intervals; keep the server receipt when adding the next question. */
    internal fun saveMistakeStudy(entry: FocalStudyEntry): Boolean {
        val previous = _state.value.entries.firstOrNull { it.id == entry.id }
        if (entry.userId == null || previous?.deleted == true ||
            previous != null && previous.userId != entry.userId ||
            previous?.completed == true && !entry.completed) return false
        if (entry.active && !entry.paused) {
            parkFocus(FocalFocus.PARK_PRACTICE)
            _state.value.visibleEntries.filter { it.id != entry.id && it.kind == "study" && it.active && !it.paused }
                .forEach { controlEntry(it.id, "pause") }
        }
        return saveEntry(entry.copy(revision = previous?.revision ?: 0L,
            remotePayload = previous?.remotePayload))
    }

    private fun saveEntry(entry: FocalStudyEntry): Boolean {
        _state.update { state ->
            state.copy(entries = listOf(entry) + state.entries.filterNot { it.id == entry.id }, error = null)
        }
        // Local first, then straight at the server. An offline write stays unsynced;
        // the durable intervals reconstruct every missing boundary on the next sync pass.
        val saved = persist()
        if (saved) scope.launch { publishEntry(entry) }
        return saved
    }

    private fun commandsFor(entry: FocalStudyEntry): List<JSONObject> {
        val user = entry.userId ?: _state.value.userId
        val pending = user?.let { timingPreferences.getString("commands:$it:${entry.id}", null) }
        if (pending != null) {
            val frozen = JSONObject(pending).getJSONArray("commands")
            return (0 until frozen.length()).map { frozen.getJSONObject(it) }
        }
        val desired = focalDesiredState(entry)
        val monoNow = SystemClock.elapsedRealtime()
        var previousMono = timingPreferences.getLong("elapsed:${entry.id}", -1L).takeIf { it > 0L }
        var previousBoot = timingPreferences.getInt("boot:${entry.id}", -1)
        val previousProcess = timingPreferences.getString("process:${entry.id}", null)
        val serverNow = serverClockAnchor?.let { estimate ->
            estimate.serverMillis + (monoNow - estimate.elapsedMillis).coerceAtLeast(0L)
        }
        val canonical = entry.remotePayload?.let { runCatching { JSONObject(it) }.getOrNull() }
        val remoteBoundary = canonical?.optString("timing_at")?.takeIf { it.isNotBlank() }
            ?: when (canonical?.let(::focalStateOf)) {
                "running" -> canonical.optString("segment_started_at").takeIf { it.isNotBlank() }
                "paused" -> canonical.optString("paused_at").takeIf { it.isNotBlank() }
                else -> null
            }
        val recoveredRunningFocus = _state.value.focus?.let { it.sessionId == entry.id && it.resumedAt == null } == true &&
            entry.paused && canonical?.let(::focalStateOf) == "running"
        val serverElapsed = if (recoveredRunningFocus) null
            else focalElapsedFromServerBoundary(remoteBoundary, serverNow)
        val monotonicContinuous = previousMono != null && previousBoot >= 0 && previousBoot == timingBootCount &&
            previousProcess == timingProcessId
        var synthesizedEnd = false
        return focalCommandsFor(entry, deviceId) { action, boundaryAt, previousAt ->
            if (boundaryAt != null) {
                // An offline boundary is durable in the intervals, not inferred from the
                // final running/paused state. The server advances from its last boundary
                // by exactly this gap; a fresh start can be placed using the server clock.
                if (previousAt != null) {
                    val gap = boundaryAt - previousAt
                    if (gap !in 0L..604_800_000L)
                        error("This offline timer boundary is outside the server's seven-day timing window")
                    return@focalCommandsFor FocalSessionCommandTiming(null, gap)
                }
                val anchored = serverClockAnchor
                val age = serverNow?.minus(boundaryAt)
                if (anchored != null && age != null && age !in -5_000L..(30L * 24 * 60 * 60 * 1_000))
                    error("This offline timer is outside the server's 30-day timing window; it cannot be replayed accurately")
                return@focalCommandsFor FocalSessionCommandTiming(
                    if (anchored != null && age != null && age >= -5_000L) Instant.ofEpochMilli(boundaryAt).toString() else null, 0L)
            }
            if (action !in setOf("start", "pause", "resume", "phase_change", "complete", "cancel")) return@focalCommandsFor null
            val fallbackElapsed = when {
                recoveredRunningFocus -> focalRecoveryElapsed(entry.activeMillis, canonical?.optLong("accumulated_active_ms") ?: 0L)
                serverElapsed != null -> serverElapsed
                action in setOf("pause", "complete") && entry.remotePayload == null -> entry.activeMillis
                else -> 0L
            }
            val elapsed = if (synthesizedEnd && entry.activeMillis > 0L)
                entry.activeMillis.coerceAtMost(604_800_000L)
            else focalElapsedSince(
                previousMono.takeIf { monotonicContinuous },
                if (monotonicContinuous) previousBoot else -1,
                monoNow, timingBootCount, fallbackElapsed
            )
            val synthesized = action == "start" && desired != "running"
            if (synthesized) synthesizedEnd = true
            val occurrenceIsSafe = !synthesized && serverNow != null &&
                (monotonicContinuous || serverElapsed != null ||
                    (previousMono == null && entry.remotePayload == null))
            val occurredAt = if (occurrenceIsSafe) Instant.ofEpochMilli(serverNow!!).toString() else null
            // Only the in-process chain advances here; the durable anchor is committed by
            // commitTimingAnchor once the server has accepted the command. A command that never
            // left the device must not move it, or a retry would report the gap since the retry.
            if (previousMono != null) { previousMono = monoNow; previousBoot = timingBootCount }
            FocalSessionCommandTiming(occurredAt, elapsed)
        }
    }

    /**
     * Move the durable monotonic anchor for a session. Called only after study_session_mutate
     * accepted the command, so the next boundary measures from a boundary the server agreed to.
     */
    private fun commitTimingAnchor(entry: FocalStudyEntry, commands: List<JSONObject>) {
        if (commands.isEmpty()) return
        val terminal = commands.last().optString("action") in setOf("complete", "cancel", "log")
        if (terminal) {
            timingPreferences.edit().remove("elapsed:${entry.id}").remove("boot:${entry.id}")
                .remove("process:${entry.id}").commit()
        } else {
            timingPreferences.edit().putLong("elapsed:${entry.id}", SystemClock.elapsedRealtime())
                .putInt("boot:${entry.id}", timingBootCount)
                .putString("process:${entry.id}", timingProcessId).commit()
        }
    }

    fun controlEntry(id: String, action: String, now: Long = this.now()) {
        val current = _state.value.visibleEntries.firstOrNull { it.id == id && it.active } ?: return
        val focus = _state.value.focus
        if (focus?.sessionId == id) {
            when (action) {
                "pause", "resume" -> {
                    if ((action == "pause") == (focus.resumedAt != null)) toggleFocus(now)
                    return
                }
                "finish" -> { finishFocus(current.notes, current.confidence, now); return }
                "discard" -> { discardFocus(now); return }
            }
        }
        if (action == "resume" && _state.value.visibleEntries.any { it.active && !it.paused && it.id != id }) return
        val updated = focalControlledEntry(current, action, now) ?: return
        saveEntry(updated)
    }

    /** Resolve multiple old paused rows in one durable write and one sync pass. */
    fun controlPausedEntries(ids: Collection<String>, action: String, now: Long = this.now()) {
        if (action != "finish" && action != "discard") return
        val focusId = _state.value.focus?.sessionId
        val targets = _state.value.visibleEntries.filter { it.id in ids && it.active && it.paused && it.id != focusId }
        if (targets.isEmpty()) return
        val updated = targets.mapNotNull { focalControlledEntry(it, action, now) }.associateBy { it.id }
        _state.update { state -> state.copy(entries = state.entries.map { updated[it.id] ?: it }, error = null) }
        if (persist()) scope.launch { updated.values.forEach { publishEntry(it) } }
    }

    fun recordExamProgress(note: Notebook, timer: ExamTimerState, now: Long = this.now(), force: Boolean = false,
                           phaseAt: Long? = null) {
        val started = timer.startedAt ?: return
        if (!timer.active) return
        val existing = _state.value.entries.firstOrNull { it.notebookId == note.id && it.startedAt == started }
        if (existing?.completed == true || existing?.deleted == true) return
        // A heartbeat is not a resume/pause button. Let the shared-state collector
        // reconcile a remote pause before another checkpoint can undo it.
        if (!force && existing?.synced == true && existing.remotePayload != null && existing.paused != timer.paused) return
        if (!force && existing != null && existing.examPhase == timer.phase.name.lowercase() && existing.paused == timer.paused &&
            now - existing.endedAt < EXAM_SYNC_INTERVAL_MS) return
        val phaseChanged = existing != null && (existing.examPhase != timer.phase.name.lowercase() || existing.paused != timer.paused)
        val entry = examStudyEntry(note, timer, now, existing, forceUpload = phaseChanged, subjects = _state.value.subjects)
        updateExam(focalAppendExamBoundaries(entry, focalExamBoundariesFor(existing, timer, now, phaseAt = phaseAt),
            now, SystemClock.elapsedRealtime(), timingBootCount))
    }

    fun finishExam(note: Notebook, timer: ExamTimerState, now: Long = this.now(), phaseAt: Long? = null) {
        val started = timer.startedAt ?: return
        val existing = _state.value.entries.firstOrNull { it.notebookId == note.id && it.startedAt == started }
        if (existing?.completed == true || existing?.deleted == true || (existing == null && timer.elapsedActiveMillis(now) <= 0L)) return
        val entry = examStudyEntry(note, timer, now, existing, completed = true, subjects = _state.value.subjects)
        updateExam(focalAppendExamBoundaries(entry, focalExamBoundariesFor(existing, timer, now,
            terminal = if (entry.deleted) "cancel" else "complete", phaseAt = phaseAt),
            now, SystemClock.elapsedRealtime(), timingBootCount))
    }

    private fun updateExam(entry: FocalStudyEntry) {
        _state.update { state ->
            val present = state.entries.any { it.id == entry.id }
            state.copy(entries = if (present) state.entries.map { if (it.id == entry.id) entry else it }
                else listOf(entry) + state.entries, error = null)
        }
        if (persist()) scope.launch { publishEntry(entry) }
    }
    fun logManual(note: Notebook?, subjectId: String?, minutes: Int, notes: String,
                  confidence: Int?, now: Long = this.now()): Boolean {
        if (minutes !in 1..1_440) return false
        return add(FocalStudyEntry(notebookId = note?.id, title = focalSessionTitle(subjectId, _state.value.subjects),
            subjectId = subjectId, kind = "study", startedAt = now - minutes * 60_000L,
            endedAt = now, activeMillis = minutes * 60_000L, notebookTitle = note?.title,
            notes = notes.trim().take(500), confidence = confidence?.takeIf { it in 1..5 }, userId = _state.value.userId))
    }
    /** Study that already happened, drawn on the timeline as one block. */
    fun logBlock(subjectId: String?, title: String, startAt: Long, endAt: Long, now: Long = this.now()): Boolean {
        if (endAt <= startAt || endAt > now || endAt - startAt > MAX_REPORTED_SESSION_MILLIS) return false
        return add(FocalStudyEntry(notebookId = null, title = title.trim().take(512).ifBlank { focalSessionTitle(subjectId, _state.value.subjects) },
            subjectId = subjectId, kind = "study", startedAt = startAt, endedAt = endAt,
            activeMillis = endAt - startAt, userId = _state.value.userId))
    }

    /**
     * Moves, resizes or ticks off a study session that is not being timed. A session the server
     * has is rewritten with one save_progress; one that never left this device is simply changed.
     */
    fun editBlocks(id: String, blocks: List<FocalStudyInterval>, completed: Boolean, now: Long = this.now()): Boolean {
        val state = _state.value
        val current = state.visibleEntries.firstOrNull { it.id == id } ?: return false
        if (current.id == state.visibleFocus?.sessionId) return false
        val edited = focalBlockEdit(current, blocks, completed, now) ?: return false
        return saveEntry(edited)
    }

    /** Deletes a plan or a finished session everywhere; a running or paused one is discarded instead. */
    fun deleteSession(id: String): Boolean {
        val state = _state.value
        val current = state.visibleEntries.firstOrNull { it.id == id } ?: return false
        if (current.planned) { deletePlan(id); return true }
        if (current.active || current.id == state.focus?.sessionId) return false
        return saveEntry(current.copy(deleted = true, synced = false, pendingBlocks = null, changeId = UUID.randomUUID().toString()))
    }

    /**
     * Merges whole sessions the way Focal does. Signed in, one `study_session_merge` transaction
     * saves the first session with every block (shared time once, gaps kept as breaks) and cancels
     * the rest, or changes nothing; it needs a connection and every session synced. On this device
     * only, the first session takes the blocks and the others are removed. Returns why it failed, or null.
     */
    suspend fun mergeSessions(ids: List<String>): String? {
        val user = _state.value.userId
        val chosen = ids.map { id -> _state.value.visibleEntries.firstOrNull { it.id == id } ?: return "A selected session changed. Select your sessions again." }
        focalMergeRefusal(chosen)?.let { return it }
        if (chosen.any { it.id == _state.value.focus?.sessionId }) return "Stop the timer before merging its session."
        val blocks = focalMergedBlocks(chosen)
        if (blocks.isEmpty() || blocks.size > 100 || blocks.any { requireNotNull(it.endAt) - it.startAt > MAX_REPORTED_SESSION_MILLIS })
            return "Those sessions have too many or too long blocks to merge."
        if (user == null) {
            if (chosen.any { it.remotePayload != null || it.intervals.isNotEmpty() }) return "Sign in to Focal to merge these sessions."
            if (chosen.first().completed && blocks.size > 1) return "Sign in to Focal to merge study with breaks between it."
            val first = chosen.first()
            val notes = chosen.map { it.notes.trim() }.filter(String::isNotBlank).distinct().joinToString("\n\n")
            val ok = editBlocks(first.id, blocks, first.completed)
            if (!ok) return "Could not merge those sessions."
            _state.update { state -> state.copy(entries = state.entries.map { if (it.id == first.id) it.copy(notes = notes) else it }) }
            chosen.drop(1).forEach { deleteSession(it.id) }
            persist()
            return null
        }
        if (!_state.value.configured) return "Connect to Focal to merge sessions."
        if (chosen.any { !it.synced || it.remotePayload == null || it.userId != user })
            return "Wait for the selected sessions to sync to Focal before merging."
        return withContext(Dispatchers.IO) { gate.withLock {
            if (_state.value.userId != user) return@withLock "The Focal account changed. Select your sessions again."
            // Re-read under the gate: a feed pull may have moved a revision since the selection.
            val latest = chosen.map { picked -> _state.value.visibleEntries.firstOrNull { it.id == picked.id }
                ?.takeIf { it.revision == picked.revision && it.synced } ?: return@withLock "A selected session changed. Select your sessions again." }
            val mutationId = UUID.randomUUID().toString()
            val (command, sources) = focalMergeRequest(latest, deviceId, mutationId)
            if (command.getJSONObject("metadata").toString().toByteArray(Charsets.UTF_8).size > 60_000)
                return@withLock "Those sessions have too many notes to merge."
            val result = try { syncRemote.mergeStudySessions(user, command, sources) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val refusal = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<io.github.jan.supabase.exceptions.RestException>()
                        .firstOrNull()?.takeIf { it.statusCode == 400 }?.error
                    return@withLock refusal ?: focalSyncError(e)
                }
            observeServerNow(result.optString("server_now").takeIf { it.isNotBlank() })
            rememberOwnChange(mutationId)
            val rows = result.optJSONArray("sessions") ?: JSONArray()
            val merged = (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let { row ->
                val id = row.optString("id")
                val local = latest.firstOrNull { it.id == id } ?: return@let null
                focalEntryFromCanonical(id, row, if (id == latest.first().id) mutationId else local.changeId, user)
                    ?.copy(notebookId = local.notebookId, notebookTitle = local.notebookTitle)
            } }.associateBy { it.id }
            if (merged.size != latest.size) return@withLock "Could not confirm the merge. Pull to refresh before trying again."
            _state.update { state -> state.copy(entries = state.entries.map { merged[it.id] ?: it }) }
            if (!persist()) return@withLock "Merged on Focal, but this device could not save it. It will catch up on the next sync."
            null
        } }
    }

    /** Adds a new session; false if it could not be saved on this device. */
    private fun add(entry: FocalStudyEntry): Boolean {
        _state.update { state -> state.copy(entries = listOf(entry) + state.entries, error = null) }
        val saved = persist()
        if (saved) scope.launch { publishEntry(entry) }
        return saved
    }
    fun retry() {
        if (_state.value.needsSignIn) return
        lifecycle.retry()
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true, syncDetail = if (it.localSaveFailed)
            "Saving sessions on this device…" else "Waiting to connect to Focal…") }
        scope.launch {
            if (_state.value.localSaveFailed) {
                if (!persist()) {
                    _state.update { it.copy(syncing = false, syncDetail = null) }
                    return@launch
                }
                _state.update { it.copy(error = null) }
            }
            requestSync()
        }
    }

    companion object {
        private const val FOCUS_CHECKPOINT_INTERVAL_MS = 5_000L
        private const val EXAM_SYNC_INTERVAL_MS = 30_000L
        private const val MAX_OWN_CHANGE_IDS = 200
    }

    fun signIn(email: String, password: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.signIn(email.trim(), password)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not sign in. Check your email, password and connection.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun signUp(email: String, password: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.signUp(email.trim(), password)
                _state.update { it.copy(authMessage = "Account created. If asked, confirm your email, then sign in.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not create the account. Check your details and connection.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun resetPassword(email: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.awaitRestoration()
                client.auth.resetPasswordForEmail(email.trim())
                _state.update { it.copy(authMessage = "If this email has an account, a password reset link is on its way.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not request a reset. Check your connection and try again.") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    fun signOut() {
        scope.launch {
            gate.withLock {
                detachFocusForAccount(null)
                lifecycle.signOut()
                remoteNoticeReadyUser = null
                clearSyncMemory()
                _state.update { it.copy(userId = null, email = null, subjects = FocalSubjects.builtIn,
                    remoteSubjectsRevision = 0L, remoteSubjectsUser = null) }
                persist()
            }
        }
    }

    private suspend fun sync() = gate.withLock {
        if (_state.value.needsSignIn) return@withLock
        val user = _state.value.userId ?: run {
            _state.update { it.copy(syncing = false, syncDetail = null) }
            return@withLock
        }
        if (!_state.value.configured) {
            _state.update { it.copy(syncing = false, syncDetail = null,
                error = "Focal sync is not configured in this build. Sessions remain on this device.") }
            return@withLock
        }
        _state.update { state -> state.copy(entries = state.entries.map { if (it.userId == null) it.copy(userId = user) else it }) }
        // Keep the last error visible while retrying. Clearing it at the start makes the chip
        // flash "synced" between every failed attempt, which is misleading and distracting.
        _state.update { it.copy(syncing = true, syncDetail = "Checking Focal for session changes…") }
        try {
            if (!persist()) error("Could not save the local account ownership")
            loadRemoteSessions(user)
            val publishing = _state.value.entries.any { !it.synced && (it.userId == null || it.userId == user) }
            publishUnsyncedEntries(user)
            if (publishing) {
                _state.update { it.copy(syncDetail = "Checking that Focal received the changes…") }
                loadRemoteSessions(user)
            }
            _state.update { it.copy(error = if (it.localSaveFailed) it.error else null) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (_state.value.userId == user)
                reportPublishFailure(_state.value.syncDetail?.removeSuffix("…") ?: "Syncing with Focal", e)
        }
        finally { _state.update { it.copy(syncing = false, syncDetail = null) } }
    }

    /** Send only the outstanding exam boundaries. Freezing each request before its RPC means
     * a timeout after server acceptance is retried against the exact same mutation receipt.
     * The mutex belongs to the caller, so button presses cannot overtake each other. */
    private suspend fun publishExamBoundaries(user: String, sessionId: String) {
        while (_state.value.userId == user) {
            val entry = _state.value.entries.firstOrNull {
                it.id == sessionId && (it.userId == null || it.userId == user)
            } ?: return
            val boundary = entry.examBoundaries.firstOrNull() ?: return
            val command = boundary.request?.let(::JSONObject) ?: run {
                val serverNow = serverClockAnchor?.let { focalEstimatedServerNow(it,
                    SystemClock.elapsedRealtime(), System.currentTimeMillis()) }
                val created = focalStudyCommand(entry, boundary.action, entry.revision, deviceId,
                    boundary.id, FocalSessionCommandTiming(
                        if (boundary.action == "start" && serverNow != null) {
                            val monoNow = SystemClock.elapsedRealtime()
                            val eventAt = if (boundary.bootCount >= 0 && boundary.bootCount == timingBootCount &&
                                boundary.monotonicAt != null && boundary.monotonicAt <= monoNow)
                                serverNow - (monoNow - boundary.monotonicAt)
                            else boundary.at
                            val age = serverNow - eventAt
                            if (age !in -5_000L..(30L * 24 * 60 * 60 * 1_000))
                                error("This offline timer is outside the server's 30-day timing window; it cannot be replayed accurately")
                            Instant.ofEpochMilli(eventAt).toString()
                        } else null, boundary.elapsedMs.also { elapsed ->
                            if (elapsed !in 0L..604_800_000L)
                                error("This offline timer boundary is outside the server's seven-day timing window")
                        }))
                    .put("phase", boundary.phase)
                _state.update { state -> state.copy(entries = state.entries.map { row ->
                    if (row.id != sessionId || row.examBoundaries.firstOrNull()?.id != boundary.id) row
                    else row.copy(examBoundaries = listOf(boundary.copy(request = created.toString())) +
                        row.examBoundaries.drop(1))
                }) }
                if (!persist()) error("Could not save the exam mutation before sending it")
                created
            }
            val result = syncRemote.mutateStudySession(user, command)
            observeServerNow(result.optString("server_now"))
            val canonical = result.optJSONObject("session") ?: error("Focal sent no canonical exam session")
            if (!result.optBoolean("applied"))
                error("This exam session changed on another device (${result.optString("reason")}); its local boundaries were kept")
            rememberOwnChange(boundary.id)
            _state.update { state -> state.copy(entries = state.entries.map { row ->
                if (row.id != sessionId || row.examBoundaries.firstOrNull()?.id != boundary.id) row
                else {
                    val remaining = row.examBoundaries.drop(1)
                    val cleaned = if (remaining.isEmpty() && canonical.optJSONObject("metadata")?.has("auto_pruned_intervals") == true)
                        focalEntryFromCanonical(sessionId, canonical, boundary.id, user)?.copy(
                            notebookId = row.notebookId, notebookTitle = row.notebookTitle, startedAt = row.startedAt,
                            examLastBoundaryAt = row.examLastBoundaryAt)
                    else null
                    (cleaned ?: row).copy(examBoundaries = remaining, revision = canonical.optLong("revision"),
                        remotePayload = canonical.toString(), synced = remaining.isEmpty(),
                        changeId = if (remaining.isEmpty()) boundary.id else row.changeId)
                }
            }) }
            if (!persist()) error("Could not save the exam mutation receipt")
        }
    }

    /**
     * Publish the latest entry: derive the missing boundaries from its durable intervals, send
     * them, and retain the canonical answer. A failed request leaves the entry unsynced.
     */
    private suspend fun publishEntry(entry: FocalStudyEntry) {
        val user = _state.value.userId ?: return
        if (!_state.value.configured) return
        gate.withLock {
            if (_state.value.userId != user) return@withLock
            try {
                if (_state.value.entries.any { it.id == entry.id && it.examBoundaries.isNotEmpty() }) {
                    publishExamBoundaries(user, entry.id)
                    return@withLock
                }
                // Derive under the gate from the latest local entry. A start, pause and resume
                // can each schedule a publish before the first RPC returns; an earlier snapshot
                // must not overtake the later one or reuse its stale revision.
                val latest = _state.value.entries.firstOrNull { it.id == entry.id && !it.synced &&
                    (it.userId == null || it.userId == user) } ?: return@withLock
                val commands = commandsFor(latest)
                if (commands.isEmpty()) return@withLock
                val ack = sendCommands(user, commands)
                if (ack.sent) { commitTimingAnchor(latest, commands); acknowledge(latest.id, ack) }
                else if (latest.planned && latest.planDraft != null) retainRefusedPlan(latest, ack)
                else if (latest.pendingBlocks != null) dropRefusedEdit(latest, ack)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportPublishFailure("Sending this session to Focal", e) }
        }
    }

    /** Every entry the server has not acknowledged. This is also how an offline session recovers. */
    private suspend fun publishUnsyncedEntries(user: String) {
        val pending = _state.value.entries.filter { !it.synced && (it.userId == null || it.userId == user) }
        for ((index, entry) in pending.withIndex()) {
            if (_state.value.userId != user) return
            _state.update { it.copy(syncDetail = "Sending session ${index + 1} of ${pending.size} to Focal\u2026") }
            try {
                val latest = _state.value.entries.firstOrNull { it.id == entry.id && !it.synced } ?: continue
                if (latest.examBoundaries.isNotEmpty()) {
                    publishExamBoundaries(user, latest.id)
                    continue
                }
                val commands = commandsFor(latest)
                if (commands.isEmpty()) { markSynced(latest.id); continue }
                val ack = sendCommands(user, commands)
                if (ack.sent) { commitTimingAnchor(latest, commands); acknowledge(latest.id, ack) }
                else if (latest.planned && latest.planDraft != null) retainRefusedPlan(latest, ack)
                else if (latest.pendingBlocks != null) dropRefusedEdit(latest, ack)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw e }
        }
    }

    private fun retainRefusedPlan(entry: FocalStudyEntry, ack: FocalPublishAck) {
        val canonical = ack.canonical ?: return
        val user = entry.userId ?: _state.value.userId ?: return
        val remote = focalEntryFromCanonical(entry.id, canonical, ack.mutationIds.lastOrNull() ?: entry.changeId, user) ?: return
        _state.update { state -> state.copy(entries = state.entries.map { current ->
            if (current.id != entry.id || current.changeId != entry.changeId) current
            else if (remote.planned) current.copy(synced = true, revision = remote.revision, remotePayload = remote.remotePayload)
            else remote.copy(planDraft = current.planDraft, notebookId = current.notebookId, notebookTitle = current.notebookTitle)
        }, error = "This plan changed on Focal. Your draft is retained; reopen the plan to review it before saving again.") }
        if (!persist()) error("Could not save the refused plan draft")
        // The server definitively refused this edit. An explicit Save creates the next command.
        if (!timingPreferences.edit().remove("commands:$user:${entry.id}").commit())
            error("Could not clear the refused plan command")
    }

    /** The server refused a timeline edit (the session is being timed or is gone): show what it has instead. */
    private fun dropRefusedEdit(entry: FocalStudyEntry, ack: FocalPublishAck) {
        val user = entry.userId ?: _state.value.userId ?: return
        val remote = ack.canonical?.let { focalEntryFromCanonical(entry.id, it, ack.mutationIds.lastOrNull() ?: entry.changeId, user) }
        _state.update { state -> state.copy(entries = state.entries.map { current ->
            if (current.id != entry.id || current.changeId != entry.changeId) current
            else remote?.copy(notebookId = current.notebookId, notebookTitle = current.notebookTitle)
                ?: current.copy(synced = true, pendingBlocks = null)
        }, error = "This session changed on Focal, so your timeline change was not applied.") }
        if (!persist()) error("Could not save the refused timeline change")
        if (!timingPreferences.edit().remove("commands:$user:${entry.id}").commit())
            error("Could not clear the refused timeline change")
    }

    private fun markSynced(sessionId: String) {
        _state.update { state -> state.copy(entries = state.entries.map {
            if (it.id == sessionId) it.copy(synced = true) else it
        }) }
        if (!persist()) error("Could not save the session receipt")
        val user = _state.value.userId ?: return
        if (!timingPreferences.edit().remove("commands:$user:$sessionId").commit())
            error("Could not clear the acknowledged study commands")
    }

    /**
     * Take the server's own word for a published session: its canonical payload, its revision and
     * the change id the feed will echo. Without this the entry keeps the revision and change id it
     * had before the publish, so the very next pull reads the device's own write back as somebody
     * else's change and the "changed on another device" notice repeats.
     */
    private fun acknowledge(sessionId: String, ack: FocalPublishAck) {
        if (!ack.sent) return
        val mutationId = ack.mutationIds.lastOrNull()
        val revision = ack.canonical?.optLong("revision")?.takeIf { it >= 0L }
        val payload = ack.canonical?.toString()
        ack.mutationIds.forEach { rememberOwnChange(it) }
        _state.update { state -> state.copy(entries = state.entries.map { entry ->
            when {
                entry.id != sessionId -> entry
                // A newer button press replaced the snapshot while this RPC was in flight.
                // Keep its intervals pending, but retain the accepted server revision so its
                // next publish starts at the right canonical boundary, not revision zero.
                entry.changeId !in ack.mutationIds -> entry.copy(
                    revision = revision ?: entry.revision, remotePayload = payload ?: entry.remotePayload)
                else -> ack.canonical?.takeIf {
                    it.optJSONObject("metadata")?.let { metadata ->
                        metadata.has("auto_merge_sources") || metadata.has("auto_pruned_intervals")
                    } == true
                }?.let { canonical ->
                    focalEntryFromCanonical(sessionId, canonical, mutationId ?: entry.changeId,
                        entry.userId ?: state.userId ?: return@let null)
                }?.copy(notebookId = entry.notebookId, notebookTitle = entry.notebookTitle)
                    ?: entry.copy(synced = true, changeId = mutationId ?: entry.changeId,
                        revision = revision ?: entry.revision, remotePayload = payload ?: entry.remotePayload, planDraft = null,
                        pendingBlocks = null)
            }
        }) }
        if (!persist()) error("Could not save the session receipt")
        val user = _state.value.userId ?: return
        if (!timingPreferences.edit().remove("commands:$user:$sessionId").commit())
            error("Could not clear the acknowledged study commands")
    }

    /** Returns false when the server refused the change, true once the session is acknowledged. */
    private suspend fun sendCommands(user: String, commands: List<JSONObject>): FocalPublishAck {
        var command = commands.first()
        var rest = commands.drop(1)
        val key = "commands:$user:${command.getString("session_id")}"
        val snapshotId = timingPreferences.getString(key, null)?.let { JSONObject(it).getString("changeId") }
            ?: command.getString("mutation_id")
        var rebases = 0
        // Every mutation id this device puts on the wire, including the ones a stale rebase
        // mints. The feed echoes them back as change ids, so they are how a later pull knows
        // that a row moved because of this device and not another one.
        val mutationIds = mutableListOf(snapshotId)
        var canonical: JSONObject? = null
        while (true) {
            // Freeze every request before sending, including rebases. A lost response must
            // retry the same receipt instead of synthesizing another pause/resume interval.
            // ponytail: rewriting the remaining batch costs O(n²) bytes for large offline batches; use a journal queue if needed.
            val frozen = JSONObject().put("changeId", snapshotId)
                .put("commands", JSONArray().also { array -> (listOf(command) + rest).forEach { array.put(it) } })
            if (!timingPreferences.edit().putString(key, frozen.toString()).commit())
                error("Could not save study commands before sending")
            val result = syncRemote.mutateStudySession(user, command)
            command.optString("mutation_id").takeIf { it.isNotBlank() }?.let { mutationIds += it }
            observeServerNow(result.optString("server_now").takeIf { it.isNotBlank() })
            val reason = result.optString("reason").takeIf { it.isNotBlank() }
            canonical = result.optJSONObject("session") ?: canonical
            if (reason == "stale_revision" && canonical != null) {
                val state = focalStateOf(canonical)
                val phase = canonical.optString("phase").takeIf { it.isNotBlank() }
                val action = command.optString("action")
                val satisfied = focalMutationSatisfied(action, state, phase, command.optString("phase").takeIf { it.isNotBlank() })
                val terminal = state == "cancelled"
                if (satisfied) {
                    if (rest.isEmpty()) return FocalPublishAck(true, canonical, mutationIds)
                    command = JSONObject(rest.first().toString()).put("expected_revision", canonical.optLong("revision"))
                    rest = rest.drop(1)
                    continue
                }
                // A timeline edit of a finished or scheduled session is last-writer-wins, as on Focal.
                val blockEdit = action == "save_progress" && command.has("blocks") && state in setOf("planned", "completed")
                if (terminal || !(focalMutationCanRebase(action, state) || blockEdit)) {
                    return FocalPublishAck(false, canonical, mutationIds)
                }
                if (focalRebaseTimingConflict(command, state))
                    error("This session's timed boundary conflicts with a newer server timeline")
                if (rebases++ >= 2) error("Study session changed repeatedly; retry sync to rebase the command")
                command = JSONObject(command.toString())
                    .put("mutation_id", UUID.randomUUID().toString())
                    .put("expected_revision", canonical.optLong("revision"))
                continue
            }
            if (reason == "session_terminal") return FocalPublishAck(false, canonical, mutationIds)
            if (reason == "not_found" || reason == "invalid_transition") {
                return FocalPublishAck(false, canonical, mutationIds)
            }
            val satisfied = canonical?.let { row -> focalMutationSatisfied(command.optString("action"),
                focalStateOf(row), row.optString("phase"), command.optString("phase")) } == true
            if (!result.optBoolean("applied") && !satisfied) {
                error("study_session_mutate failed: ${reason ?: "server_unavailable"}")
            }
            if (rest.isEmpty()) return FocalPublishAck(true, canonical, mutationIds)
            command = JSONObject(rest.first().toString()).put("expected_revision", canonical?.optLong("revision"))
            rest = rest.drop(1)
        }
    }

    private fun reportPublishFailure(step: String, e: Exception) {
        val reason = when {
            e.message?.startsWith("This offline timer") == true ||
                e.message?.startsWith("This session") == true ||
                e.message?.startsWith("This exam session") == true -> e.message!!
            else -> focalSyncError(e)
        }
        val advice = if (reason == e.message) "This session remains saved on this device; review it before retrying."
            else "Sessions remain saved on this device and will be sent when you are back online."
        _state.update { it.copy(error = "$step failed. $reason $advice") }
    }

    private suspend fun loadRemoteSessions(user: String) {
        val prior = _state.value
        var cursor = prior.remoteRevision.takeIf { prior.remoteRevisionUser == user } ?: 0L
        // Subjects share this feed. Resume at the older cursor once for existing installs,
        // then checkpoint both projections together instead of downloading the feed twice.
        cursor = minOf(cursor, prior.remoteSubjectsRevision.takeIf { prior.remoteSubjectsUser == user } ?: 0L)
        val startCursor = cursor
        val builtInIds = FocalSubjects.builtIn.mapTo(mutableSetOf()) { it.id }
        val custom = (if (prior.remoteSubjectsUser == user) prior.subjects else emptyList())
            .filterNot { it.id in builtInIds }.associateBy { it.id }.toMutableMap()
        val latest = linkedMapOf<String, Pair<JSONObject, String>>()
        val observedSessionIds = mutableSetOf<String>()
        var latestLamport = if (prior.remoteRevisionUser == user) prior.remoteLamport else 0L
        var pages = 0
        while (pages++ < 100) {
            val page = syncRemote.read(user, cursor, 500)
            observeServerNow(page.serverNow)
            if (page.mode == "snapshot") {
                latest.clear()
                custom.clear()
                page.rows.filter { it.entity == "custom_subjects" }.forEach { row ->
                    focalApplyCustomSubject(custom, row.rowId, row.operation, row.payload)
                }
                page.rows.filter { it.entity == "study_sessions" && it.payload != null }.forEach { row ->
                    latest[row.rowId] = row.payload!! to "snapshot:${row.rowId}:${row.lamport}"
                    latestLamport = maxOf(latestLamport, row.lamport)
                }
                cursor = page.head
                break
            }
            page.changes.forEach { change ->
                cursor = maxOf(cursor, change.seq)
                if (change.entity == "custom_subjects")
                    focalApplyCustomSubject(custom, change.rowId, change.operation, change.payload)
                if (change.entity == "study_sessions" && change.payload != null) {
                    latest[change.rowId] = change.payload to change.changeId
                    observedSessionIds += change.rowId
                    latestLamport = maxOf(latestLamport, change.lamport)
                }
            }
            if (page.changes.size < 500 || cursor >= page.head) break
        }
        if (_state.value.userId != user) return
        val previous = _state.value.entries.associateBy { it.id }
        val remote = latest.mapNotNull { (id, value) -> focalEntryFromCanonical(id, value.first, value.second, user) }
        val externalChanges = focalExternalChanges(
            previous.values.toList(), remote, ownChangeIds, noticedExternalChanges)
        val notice = if (remoteNoticeReadyUser == user && externalChanges.isNotEmpty()) {
            if (externalChanges.any { incoming -> incoming.completed && previous[incoming.id]?.active == true })
                "A Focal session finished on another device"
            else if (externalChanges.size == 1) "A Focal session changed on another device"
            else "${externalChanges.size} Focal sessions changed on another device"
        } else null
        externalChanges.forEach { noticedExternalChanges[it.id] = it.changeId }
        _state.update { state ->
            // A session the server echoed back is acknowledged; anything it did not echo is
            // still local truth and the next publish pass sends the difference.
            val merged = state.entries.filter { it.userId == null || it.userId == user }
                .associateBy { it.id }.toMutableMap()
            remote.forEach { incoming -> merged[incoming.id] = focalMergeSession(merged[incoming.id], incoming) }
            val otherAccounts = state.entries.filter { it.userId != null && it.userId != user }
            val focus = state.focus?.let { current ->
                val session = merged[current.sessionId] ?: return@let current
                if (remote.none { it.id == current.sessionId }) return@let current
                focalReconcileFocus(current, session)
            }
            state.copy(entries = merged.values.toList() + otherAccounts, focus = focus,
                remoteRevision = maxOf(startCursor, cursor), remoteRevisionUser = user,
                subjects = FocalSubjects.builtIn + custom.values,
                remoteSubjectsRevision = maxOf(startCursor, cursor), remoteSubjectsUser = user,
                remoteLamport = latestLamport,
                remoteNotice = notice ?: state.remoteNotice,
                remoteNoticeId = if (notice != null) state.remoteNoticeId + 1 else state.remoteNoticeId)
        }
        if (!persist()) error("Could not save the Folio session and subject sync cursor")
        remoteNoticeReadyUser = user
    }
}
