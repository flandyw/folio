package com.folio.notes.progress

import android.content.Context
import android.util.AtomicFile
import com.folio.notes.mistakes.ExamTrackMistake
import com.folio.notes.mistakes.isoTime
import com.folio.notes.mistakes.sync.ExamTrackAuthRepository
import com.folio.notes.mistakes.sync.ExamTrackMistakeCodec
import com.folio.notes.mistakes.sync.FocalAccountStatus
import com.folio.notes.mistakes.sync.mistakeSyncError
import com.folio.notes.sync.ApplyResult
import com.folio.notes.sync.FocalSupabaseConnection
import com.folio.notes.sync.ReadResult
import com.folio.notes.sync.SupabaseSyncRemote
import com.folio.notes.sync.SyncProtocol
import com.folio.notes.sync.focalChangeRefused
import com.folio.notes.sync.focalSyncDeviceId
import com.folio.notes.sync.watchFocalFeed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal val SYNCED_ENTITIES = listOf("attempts", "mistakes", "user_state", "events", "timetable_config")
/** Bumped when [SYNCED_ENTITIES] grows: an older cache re-reads the feed once so rows it skipped arrive. */
private const val FEED_VERSION = 2

data class ProgressCache(val rows: Map<String, SyncProtocol.RowState> = emptyMap(),
    val outbox: Map<String, SyncProtocol.QueuedChange> = emptyMap(),
    val conflicts: Map<String, SyncProtocol.RowState> = emptyMap(), val cursor: Long = 0,
    val lamport: Long = 0, val lastSynced: String? = null) {
    val exams get() = rows.values.filter { it.entity == "attempts" && it.operation == "put" }
        .mapNotNull { row -> row.payload?.toString()?.let(LoggedExam::decode)?.takeIf { it.id == row.rowId } }
    val mistakes: List<ExamTrackMistake> get() = rows.values.filter { it.entity == "mistakes" && it.operation == "put" }
        .mapNotNull { it.payload?.toString()?.let { raw -> ExamTrackMistakeCodec.decode(raw, it.rowId) } }
    fun value(id: String): Any? {
        val row = rows["user_state:$id"]
        if (row != null) return if (row.operation == "put") row.payload?.opt("value") else null
        return rows["user_state:user_state"]?.takeIf { it.operation == "put" }?.payload?.opt(id)
    }
    /** Calendar events and the class timetable, shared with Focal through the same change log. */
    val events get() = rows.values.filter { it.entity == "events" && it.operation == "put" }.mapNotNull { it.payload }
    val timetable get() = rows["timetable_config:timetable_config"]?.takeIf { it.operation == "put" }?.payload
    val difficulty get() = value("examDifficulty") as? JSONObject
    val progression get() = value("examProgression") as? JSONObject
}
data class ProgressState(val userId: String? = null, val cache: ProgressCache = ProgressCache(),
    val loading: Boolean = true, val syncing: Boolean = false, val error: String? = null,
    val readable: Boolean = true, val catalog: ExamCatalog = ExamCatalog(), val catalogError: String? = null)

/** App-scoped writer: saves and mutation receipts survive Activity recreation. Separate account files. */
class ExamProgressManager(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val auth = ExamTrackAuthRepository(app)
    private val client = FocalSupabaseConnection.of(app).client
    private val remote = SupabaseSyncRemote(client, auth.lifecycle)
    private val device = focalSyncDeviceId(app)
    private val lock = Mutex()
    private val _state = MutableStateFlow(ProgressState())
    val state = _state.asStateFlow()
    private var syncJob: Job? = null

    init {
        scope.launch {
            try {
                val catalog = ExamCatalog.decode(app.assets.open("progress/vcaa-grade-distributions.json").bufferedReader().use { it.readText() },
                    app.assets.open("progress/vcaa-exam-resources.json").bufferedReader().use { it.readText() })
                _state.update { it.copy(catalog = catalog) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(catalogError = "Could not load the offline VCAA catalogue.") } }
        }
        scope.launch {
            auth.account.collect { account ->
                if (account.status != FocalAccountStatus.RESTORING) switchAccount(account.user?.id)
                when (account.status) {
                    FocalAccountStatus.CONNECTED -> sync()
                    FocalAccountStatus.RECONNECTING, FocalAccountStatus.SIGN_IN_REQUIRED ->
                        _state.update { it.copy(error = account.message) }
                    else -> Unit
                }
            }
        }
    }
    private fun file(user: String?) = AtomicFile(File(app.filesDir, "progress/${user ?: "device"}/cache.json"))
    private fun load(user: String?): ProgressCache {
        val file = file(user)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return ProgressCache()
        val o = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(o.getInt("version") == 1) { "Unsupported exam cache" }
        fun rows(key: String) = o.getJSONArray(key).objects().map { requireNotNull(SyncProtocol.parseRowState(it)) }.associateBy { it.key }
        return ProgressCache(rows("rows"), o.getJSONArray("outbox").objects().map(SyncProtocol.QueuedChange::fromJson).associateBy { it.key },
            rows("conflicts"), if (o.optInt("feed", 1) >= FEED_VERSION) o.getLong("cursor") else 0, o.getLong("lamport"), o.opt("lastSynced") as? String)
    }
    private fun persist(user: String?, cache: ProgressCache) {
        val file = file(user); check(file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs())
        val raw = JSONObject().put("version", 1).put("rows", JSONArray(cache.rows.values.map { it.toJson() }))
            .put("outbox", JSONArray(cache.outbox.values.map { it.toJson() }))
            .put("conflicts", JSONArray(cache.conflicts.values.map { it.toJson() }))
            .put("cursor", cache.cursor).put("feed", FEED_VERSION).put("lamport", cache.lamport).put("lastSynced", cache.lastSynced).toString()
        val stream = file.startWrite()
        try { stream.write(raw.toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private suspend fun switchAccount(user: String?) {
        if (!_state.value.loading && _state.value.userId == user) return
        syncJob?.cancel()
        // Hide the previous account before waiting for a writer/network call to release the lock.
        _state.update { it.copy(userId = user, cache = ProgressCache(), loading = true, error = null, syncing = false) }
        lock.withLock {
            try { val cache = load(user); _state.update { it.copy(cache = cache, loading = false, readable = true) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(loading = false, readable = false,
                error = "Could not read saved exams. The saved file has been kept. Retry loading before making changes.") } }
        }
    }
    fun retryLoad() {
        val user = _state.value.userId
        scope.launch { lock.withLock {
            if (_state.value.userId != user || _state.value.loading) return@withLock
            _state.update { it.copy(loading = true) }
            try {
                val cache = load(user)
                _state.update { if (it.userId == user) it.copy(cache = cache, loading = false, readable = true, error = null) else it }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { if (it.userId == user) it.copy(loading = false,
                error = "Could not read saved exams. The saved file has been kept.") else it } }
        } }
    }
    private fun publishCache(user: String?, cache: ProgressCache) {
        persist(user, cache)
        _state.update { if (it.userId == user) it.copy(cache = cache) else it }
    }
    /** A caller only closes its form after the durable write succeeds. */
    suspend fun save(entity: String, id: String, payload: JSONObject?, expectedUser: String?): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val s = _state.value
                check(!s.loading && s.readable && s.userId == expectedUser) { "The account changed. Reopen this form." }
                require(entity in SYNCED_ENTITIES && id.isNotBlank())
                if (entity == "attempts" && payload != null) require(LoggedExam.decode(payload.toString())?.id == id)
                val c = s.cache; val key = "$entity:$id"
                check(key !in c.conflicts) { "Resolve this record's sync conflict before editing it." }
                val lamport = c.lamport + 1
                val operation = if (payload == null) "delete" else "put"
                val change = SyncProtocol.QueuedChange(UUID.randomUUID().toString(), entity, id, operation,
                    payload, lamport, isoTime(), expectedSeq = c.outbox[key]?.expectedSeq ?: c.rows[key]?.seq ?: 0)
                val row = SyncProtocol.RowState(entity, id, operation, payload, lamport, device, c.rows[key]?.seq ?: 0)
                publishCache(expectedUser, c.copy(rows = c.rows + (key to row), outbox = c.outbox + (key to change), lamport = lamport))
                _state.update { it.copy(error = null) }; sync(); true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Could not save the exam. Keep this form open and retry.") }; false }
        }
    }
    suspend fun saveValue(id: String, value: Any, user: String?) = save("user_state", id,
        JSONObject().put("value", value).put("updated_at", isoTime()), user)

    suspend fun resolve(key: String, keepLocal: Boolean, user: String?): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                check(_state.value.userId == user && !_state.value.loading)
                val c = _state.value.cache; val remoteRow = c.conflicts[key] ?: return@withLock true
                val queued = c.outbox[key]
                val next = if (keepLocal && queued != null) c.copy(outbox = c.outbox + (key to queued.copy(
                    changeId = UUID.randomUUID().toString(), expectedSeq = remoteRow.seq)), conflicts = c.conflicts - key)
                else c.copy(rows = c.rows + (key to remoteRow), outbox = c.outbox - key, conflicts = c.conflicts - key)
                publishCache(user, next); sync(); true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(error = "Could not save the conflict decision. Retry.") }; false }
        }
    }
    suspend fun copyDeviceLogs(user: String): Boolean {
        val cache = withContext(Dispatchers.IO) { lock.withLock { load(null) } }
        for (row in cache.rows.values.filter { it.entity in listOf("attempts", "mistakes") && it.operation == "put" }) {
            if (row.key !in _state.value.cache.rows && !save(row.entity, row.rowId, row.payload, user)) return false
        }
        return true
    }

    @Volatile private var rerun = false

    /** Pulls Focal's changes, then publishes local ones. A request made while a pass is running is not dropped: it runs another pass after it. */
    @Synchronized fun sync() {
        if (_state.value.userId == null || _state.value.loading) return
        if (syncJob?.isActive == true) { rerun = true; return }
        rerun = false
        syncJob = scope.launch { runSync() }.also { job -> job.invokeOnCompletion { if (it == null && rerun) sync() } }
    }

    /** For a visible screen: Focal's changes arrive as they are made, with a slow poll in case a socket event is missed. */
    suspend fun keepFresh(): Unit = coroutineScope {
        _state.value.userId?.let { user -> launch { watchFocalFeed(client, "folio-progress-$user", SYNCED_ENTITIES, ::sync) } }
        while (isActive) { sync(); delay(30_000) }
    }

    private suspend fun runSync() {
        val user = _state.value.userId ?: return
        if (!_state.value.readable) return
        _state.update { if (it.userId == user) it.copy(syncing = true, error = null) else it }
        // The lock is held only while the cache changes, never across a request, so saving a form does not wait for the network.
        suspend fun edit(change: (ProgressCache) -> ProgressCache) = lock.withLock {
            val s = _state.value
            if (s.userId != user || s.loading || !s.readable) throw CancellationException("The account changed")
            val next = change(s.cache)
            // A poll that changed nothing only moves lastSynced: show it, but don't rewrite the whole cache file every 30 s.
            if (next.copy(lastSynced = s.cache.lastSynced) != s.cache) publishCache(user, next)
            else _state.update { if (it.userId == user) it.copy(cache = next) else it }
        }
        try {
            var cursor = 0L
            edit { cursor = it.cursor; it }
            var pages = 0
            while (pages++ < 100) {
                val page = remote.read(user, cursor, 500)
                edit { cache -> cache.absorb(page).also { cursor = it.cursor } }
                if (page.mode == "snapshot" || cursor >= page.head || page.changes.size < 500) break
            }
            var refused: Exception? = null
            suspend fun publish(batch: List<SyncProtocol.QueuedChange>) {
                val result = try { remote.apply(user, batch, device) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // Offline, signed out or server trouble: stop and retry later. A change Focal refuses must not hold back the rest.
                    if (!focalChangeRefused(e)) throw e
                    if (batch.size > 1) batch.forEach { publish(listOf(it)) } else refused = refused ?: e
                    return
                }
                edit { it.settle(batch, result) }
                if (batch.any { sent -> result.receipts.none { it.changeId == sent.changeId } && result.stale.none { it.changeId == sent.changeId } })
                    error("Focal did not acknowledge the saved change")
            }
            var outbox = emptyList<SyncProtocol.QueuedChange>()
            edit { cache -> outbox = cache.outbox.values.filter { it.key !in cache.conflicts }; cache }
            // One request for the whole outbox instead of one per change.
            outbox.chunked(50).forEach { publish(it) }
            refused?.let { throw it }
            edit { it.copy(lastSynced = isoTime()) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { if (it.userId == user) it.copy(error = mistakeSyncError(e)) else it } }
        finally { _state.update { if (it.userId == user) it.copy(syncing = false) else it } }
    }
}

/** Folds a page of Focal's log (or a whole snapshot, when the cursor was too old to tail) into the cache. */
internal fun ProgressCache.absorb(page: ReadResult): ProgressCache {
    var c = this
    fun apply(row: SyncProtocol.RowState, changeId: String? = null) {
        if (row.entity !in SYNCED_ENTITIES) return
        val queued = c.outbox[row.key]
        val acknowledged = queued != null && queued.changeId == changeId
        val collision = queued != null && !acknowledged && row.seq > (queued.expectedSeq ?: 0)
        c = c.copy(lamport = maxOf(c.lamport, row.lamport),
            // A row with an unsent edit keeps it: the log's copy is either a collision or a version this edit was made over.
            rows = if (queued != null && !acknowledged) c.rows else c.rows + (row.key to row),
            outbox = if (acknowledged) c.outbox - row.key else c.outbox,
            conflicts = when { acknowledged -> c.conflicts - row.key; collision -> c.conflicts + (row.key to row); else -> c.conflicts })
    }
    if (page.mode == "snapshot") {
        c = c.copy(rows = c.rows.filterKeys { it in c.outbox })
        page.rows.forEach { apply(it) }
        // A compacted-away pending row is a deletion, never an invitation to recreate it.
        val present = page.rows.mapTo(HashSet()) { it.key }
        for ((key, queued) in c.outbox) if (key !in present && (queued.expectedSeq ?: 0) > 0)
            c = c.copy(conflicts = c.conflicts + (key to SyncProtocol.RowState(queued.entity, queued.rowId, "delete", null, 0, "", 0)))
        return c.copy(cursor = page.head)
    }
    page.changes.sortedBy { it.seq }.forEach { change -> apply(SyncProtocol.RowState(
        change.entity, change.rowId, change.operation, change.payload, change.lamport, change.clientId, change.seq), change.changeId) }
    return c.copy(cursor = maxOf(c.cursor, page.changes.maxOfOrNull { it.seq } ?: c.cursor))
}

/** Folds Focal's answer to a published batch into the cache: a receipt settles its row, a stale answer becomes a conflict. */
internal fun ProgressCache.settle(batch: List<SyncProtocol.QueuedChange>, result: ApplyResult): ProgressCache {
    var c = this
    for (sent in batch) {
        val key = sent.key
        val receipt = result.receipts.firstOrNull { it.changeId == sent.changeId }
        if (receipt != null) {
            // An edit saved while this one was on the wire now follows it instead of colliding with it.
            val newer = c.outbox[key]?.takeIf { it.changeId != sent.changeId }
            c = c.copy(rows = c.rows[key]?.let { c.rows + (key to it.copy(seq = receipt.seq)) } ?: c.rows,
                outbox = if (newer != null) c.outbox + (key to newer.copy(expectedSeq = receipt.seq)) else c.outbox - key)
        } else result.stale.firstOrNull { it.changeId == sent.changeId }?.let { stale ->
            c = c.copy(conflicts = c.conflicts + (key to (stale.current
                ?: SyncProtocol.RowState(sent.entity, sent.rowId, "delete", null, 0, "", 0))))
        }
    }
    return c
}
