package com.folio.notes

import android.content.Context
import android.util.AtomicFile
import com.folio.notes.mistakes.EncryptedExamTrackSession
import com.folio.notes.mistakes.ExamTrackSessionLifecycle
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID

/** Focal's built-in subject IDs are stable sync IDs, not Folio enum names. */
data class FocalSubject(val id: String, val name: String)

object FocalSubjects {
    val builtIn = listOf(
        FocalSubject("eng", "English"), FocalSubject("eng-lang", "English Language"),
        FocalSubject("lit", "Literature"), FocalSubject("mm", "Mathematical Methods"),
        FocalSubject("sm", "Specialist Mathematics"), FocalSubject("gm", "General Mathematics"),
        FocalSubject("csl", "Chinese Second Language"), FocalSubject("pe", "Physical Education"),
        FocalSubject("chem", "Chemistry"), FocalSubject("phys", "Physics"),
        FocalSubject("bio", "Biology"), FocalSubject("psych", "Psychology"),
        FocalSubject("hist", "History"), FocalSubject("geo", "Geography"),
        FocalSubject("econ", "Economics"), FocalSubject("bm", "Business Management")
    )
    private val folioIds = mapOf(
        VceSubject.GENERAL_MATHS to "gm", VceSubject.MATHS_METHODS to "mm",
        VceSubject.SPECIALIST_MATHS to "sm", VceSubject.PHYSICS to "phys",
        VceSubject.CHEMISTRY to "chem", VceSubject.BIOLOGY to "bio",
        VceSubject.PHYSICAL_EDUCATION to "pe", VceSubject.PSYCHOLOGY to "psych",
        VceSubject.ENGLISH to "eng", VceSubject.LITERATURE to "lit",
        VceSubject.ECONOMICS to "econ"
    )

    fun suggest(note: Notebook, subjects: List<FocalSubject> = builtIn): String? {
        folioIds[note.exam.subject]?.let { return it }
        val source = note.exam.subjectText.ifBlank { note.title }.trim()
        if (source.isBlank()) return null
        val normalized = source.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val exact = subjects.firstOrNull { it.name.equals(source, true) }
        if (exact != null) return exact.id
        // Longest names win: English Language before English, Specialist before Mathematics.
        return subjects.sortedByDescending { it.name.length }.firstOrNull { subject ->
            val name = subject.name.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            name.length >= 5 && " $normalized ".contains(" $name ")
        }?.id ?: folioIds[VceSubject.match(source)]
    }
}

data class FocalStudyEntry(
    val id: String = UUID.randomUUID().toString(),
    val changeId: String = UUID.randomUUID().toString(),
    val notebookId: String?,
    val title: String,
    val subjectId: String?,
    val kind: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeMillis: Long,
    val notes: String = "",
    val confidence: Int? = null,
    val userId: String? = null,
    val synced: Boolean = false
) {
    val minutes: Int get() = (activeMillis / 60_000L).toInt().coerceAtLeast(1)
}

data class FocalFocus(
    val notebookId: String,
    val title: String,
    val subjectId: String?,
    val startedAt: Long,
    val resumedAt: Long?,
    val accumulatedMillis: Long = 0L
) {
    fun elapsed(now: Long) = accumulatedMillis + (resumedAt?.let { (now - it).coerceAtLeast(0) } ?: 0L)
    fun pause(now: Long) = copy(resumedAt = null, accumulatedMillis = elapsed(now))
    fun resume(now: Long) = if (resumedAt == null) copy(resumedAt = now) else this
}

data class FocalStudyState(
    val entries: List<FocalStudyEntry> = emptyList(),
    val focus: FocalFocus? = null,
    val subjects: List<FocalSubject> = FocalSubjects.builtIn,
    val userId: String? = null,
    val email: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val authMessage: String? = null,
    val configured: Boolean = !BuildConfig.FOCAL_SUPABASE_URL.contains("example.supabase.co") &&
        !BuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY.contains("example_placeholder")
) {
    val visibleEntries get() = entries.filter { it.userId == null || it.userId == userId }
    val pendingCount get() = visibleEntries.count { !it.synced }
}

/** Durable local sessions and an idempotent Focal sync_changes outbox. */
class FocalStudyManager(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = AtomicFile(File(context.filesDir, "focal-study.json"))
    private val gate = Mutex()
    private val sessions = EncryptedExamTrackSession(context, "focal-session", "folio-focal")
    private val client = createSupabaseClient(BuildConfig.FOCAL_SUPABASE_URL, BuildConfig.FOCAL_SUPABASE_PUBLISHABLE_KEY) {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = sessions
            autoLoadFromStorage = false
            alwaysAutoRefresh = true
            enableLifecycleCallbacks = false
        }
        install(Postgrest)
    }
    private val lifecycle = ExamTrackSessionLifecycle(client, sessions)
    private val _state = MutableStateFlow(load())
    val state = _state.asStateFlow()

    init {
        scope.launch {
            lifecycle.awaitRestoration()
            lifecycle.restoredUser?.let { user -> _state.update { it.copy(userId = user.id, email = user.email) } }
            client.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        status.session.user?.let { user ->
                            _state.update { it.copy(userId = user.id, email = user.email, error = null) }
                            sync()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> _state.update { it.copy(userId = null, email = null) }
                    else -> Unit
                }
            }
        }
        scope.launch {
            while (true) {
                delay(15_000)
                if (_state.value.focus?.resumedAt != null) persist(parkRunningAt = System.currentTimeMillis())
                if (_state.value.userId != null && (_state.value.pendingCount > 0 || _state.value.error != null)) sync()
            }
        }
    }

    private fun load(): FocalStudyState = runCatching {
        if (!file.baseFile.exists()) return@runCatching FocalStudyState()
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
                userId = row.optString("userId").ifBlank { null }, synced = row.optBoolean("synced")
            )
        }.getOrNull() }
        val focus = data.optJSONObject("focus")?.let { row -> runCatching {
            FocalFocus(row.getString("notebookId"), row.getString("title"),
                row.optString("subjectId").ifBlank { null }, row.getLong("startedAt"),
                row.optLong("resumedAt").takeIf { it > 0 }, row.optLong("accumulatedMillis"))
        }.getOrNull() }
        // A process death must not turn an unseen gap into study time.
        FocalStudyState(entries = entries, focus = focus?.copy(resumedAt = null))
    }.getOrDefault(FocalStudyState())

    @Synchronized private fun persist(parkRunningAt: Long? = null): Boolean {
        val snapshot = _state.value
        val rows = JSONArray()
        snapshot.entries.forEach { entry -> rows.put(JSONObject()
            .put("id", entry.id).put("changeId", entry.changeId).put("notebookId", entry.notebookId)
            .put("title", entry.title).put("subjectId", entry.subjectId).put("kind", entry.kind)
            .put("startedAt", entry.startedAt).put("endedAt", entry.endedAt)
            .put("activeMillis", entry.activeMillis).put("notes", entry.notes)
            .put("confidence", entry.confidence).put("userId", entry.userId).put("synced", entry.synced)) }
        val focus = snapshot.focus?.let { if (parkRunningAt == null) it else it.pause(parkRunningAt) }?.let { JSONObject().put("notebookId", it.notebookId)
            .put("title", it.title).put("subjectId", it.subjectId).put("startedAt", it.startedAt)
            .put("resumedAt", it.resumedAt).put("accumulatedMillis", it.accumulatedMillis) }
        val bytes = JSONObject().put("entries", rows).put("focus", focus).toString().toByteArray()
        return try {
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
            true
        }
        catch (_: Exception) {
            _state.update { it.copy(error = "Could not save study sessions on this device. Free storage and try again.") }
            false
        }
    }

    fun startFocus(note: Notebook, subjectId: String?, now: Long = System.currentTimeMillis()) {
        if (_state.value.focus != null) return
        _state.update { it.copy(focus = FocalFocus(note.id, note.title, subjectId, now, now), error = null) }
        persist()
    }
    fun toggleFocus(now: Long = System.currentTimeMillis()) {
        _state.update { state -> state.copy(focus = state.focus?.let { if (it.resumedAt == null) it.resume(now) else it.pause(now) }) }
        persist()
    }
    fun discardFocus() { _state.update { it.copy(focus = null) }; persist() }
    fun finishFocus(notes: String, confidence: Int?, now: Long = System.currentTimeMillis()) {
        val focus = _state.value.focus ?: return
        val active = focus.elapsed(now)
        if (active < 1_000L) { discardFocus(); return }
        val entry = FocalStudyEntry(notebookId = focus.notebookId, title = focus.title,
            subjectId = focus.subjectId, kind = "study", startedAt = focus.startedAt,
            endedAt = now, activeMillis = active, notes = notes.trim(), confidence = confidence,
            userId = _state.value.userId)
        _state.update { it.copy(entries = listOf(entry) + it.entries, focus = null, error = null) }
        if (persist()) scope.launch { sync() }
    }
    fun recordExam(note: Notebook, seconds: Int, startedAt: Long, endedAt: Long) {
        if (seconds <= 0) return
        add(FocalStudyEntry(notebookId = note.id, title = note.title,
            subjectId = FocalSubjects.suggest(note, _state.value.subjects), kind = "exam",
            startedAt = (endedAt - seconds * 1000L).coerceAtLeast(startedAt), endedAt = endedAt,
            activeMillis = seconds * 1000L, userId = _state.value.userId))
    }
    fun logManual(note: Notebook, subjectId: String?, minutes: Int, notes: String,
                  confidence: Int?, now: Long = System.currentTimeMillis()) {
        if (minutes !in 1..1_440) return
        add(FocalStudyEntry(notebookId = note.id, title = note.title,
            subjectId = subjectId, kind = "study", startedAt = now - minutes * 60_000L,
            endedAt = now, activeMillis = minutes * 60_000L,
            notes = notes.trim(), confidence = confidence?.takeIf { it in 1..5 }, userId = _state.value.userId))
    }
    private fun add(entry: FocalStudyEntry) {
        _state.update { it.copy(entries = listOf(entry) + it.entries, error = null) }
        if (persist()) scope.launch { sync() }
    }
    fun retry() { scope.launch { sync() } }

    fun signIn(email: String, password: String) {
        if (_state.value.busy || !_state.value.configured) return
        _state.update { it.copy(busy = true, error = null, authMessage = null) }
        scope.launch {
            try {
                lifecycle.awaitRestoration()
                client.auth.signInWith(Email) { this.email = email.trim(); this.password = password }
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
                lifecycle.awaitRestoration()
                client.auth.signUpWith(Email) { this.email = email.trim(); this.password = password }
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
                client.auth.clearSession()
                sessions.deleteSession()
                _state.update { it.copy(userId = null, email = null, subjects = FocalSubjects.builtIn) }
            }
        }
    }

    private suspend fun sync() = gate.withLock {
        val user = _state.value.userId ?: return@withLock
        if (!_state.value.configured || client.auth.currentUserOrNull()?.id != user) return@withLock
        _state.update { it.copy(syncing = true, error = null) }
        try {
            // Focal v2 stores immutable changes. A fixed change ID makes retries idempotent.
            val pending = _state.value.entries.filter { !it.synced && (it.userId == null || it.userId == user) }.asReversed()
            for (entry in pending) {
                if (client.auth.currentUserOrNull()?.id != user) break
                val owned = entry.copy(userId = user)
                _state.update { state -> state.copy(entries = state.entries.map { if (it.id == entry.id) owned else it }) }
                persist()
                val payload = focalPayload(owned)
                val change = JSONObject().put("user_id", user).put("change_id", owned.changeId)
                    .put("device_id", "folio-android").put("entity", "study_sessions")
                    .put("row_id", owned.id).put("operation", "put").put("payload", payload)
                client.from("sync_changes").insert(Json.parseToJsonElement(change.toString()).jsonObject)
                _state.update { state -> state.copy(entries = state.entries.map { if (it.id == entry.id) it.copy(synced = true) else it }) }
                persist()
            }
            loadCustomSubjects(user)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { _state.update { it.copy(error = "Sessions are saved here. Focal sync will retry when connected.") } }
        finally { _state.update { it.copy(syncing = false) } }
    }

    private suspend fun loadCustomSubjects(user: String) {
        val raw = client.from("sync_changes").select {
            filter { eq("user_id", user); eq("entity", "custom_subjects") }
        }.data
        val rows = JSONArray(raw)
        val latest = mutableMapOf<String, JSONObject>()
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.optString("row_id")
            if (id.isNotBlank() && (latest[id]?.optLong("revision") ?: -1L) < row.optLong("revision")) latest[id] = row
        }
        val custom = latest.values.mapNotNull { row ->
            if (row.optString("operation") == "delete") return@mapNotNull null
            val payload = row.optJSONObject("payload") ?: return@mapNotNull null
            val id = payload.optString("id").ifBlank { row.optString("row_id") }
            val name = payload.optString("name")
            if (id.isBlank() || name.isBlank()) null else FocalSubject(id, name)
        }
        if (_state.value.userId == user) _state.update { it.copy(subjects = FocalSubjects.builtIn + custom) }
    }
}

internal fun focalPayload(entry: FocalStudyEntry): JSONObject {
    fun iso(time: Long) = Instant.ofEpochMilli(time).toString()
    val start = iso(entry.startedAt)
    val end = iso(entry.endedAt)
    val interval = JSONObject().put("start", start).put("end", end).put("source", "manual")
    val reflection = JSONObject()
    if (entry.notes.isNotBlank()) reflection.put("notes", entry.notes)
    entry.confidence?.let { reflection.put("confidence", it) }
    return JSONObject().put("schemaVersion", 2).put("id", entry.id)
        .put("subjectIds", JSONArray().also { if (entry.subjectId != null) it.put(entry.subjectId) })
        .put("title", entry.title).put("description", if (entry.kind == "exam") "Exam practice in Folio" else "Study in Folio")
        .put("schedule", JSONObject().put("blocks", JSONArray().put(JSONObject().put("start", start).put("end", end))))
        .put("execution", JSONObject().put("state", "completed").put("intervals", JSONArray().put(interval))
            .put("completedAt", end).put("reportedMinutes", entry.minutes))
        .put("reflection", reflection).put("createdVia", "manual")
        .put("created_at", end).put("updated_at", end).put("deleted_at", JSONObject.NULL)
        .put("last_modified_device_id", "folio-android")
}
