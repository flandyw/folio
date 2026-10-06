package com.folio.notes.music

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class MusicState(val library: MusicLibrary = MusicLibrary(), val loading: Boolean = true,
    val failed: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val reviews: List<MusicImportReview> = emptyList())

/** One ordered writer; edits and import results are only published after their atomic save. */
internal class MusicViewModel(application: Application) : AndroidViewModel(application) {
    val store = MusicStore(application)
    val pageCache = MusicPageCache()
    private val mutable = MutableStateFlow(MusicState())
    val state = mutable.asStateFlow()
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        viewModelScope.launch {
            for (action in work) {
                try { action() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, message = "Music: ${e.message ?: "operation failed"}") }
            }
        }
        reload()
    }
    fun reload() { work.trySend {
        mutable.value = mutable.value.copy(loading = true, failed = false)
        try {
            val library = withContext(Dispatchers.IO) { store.load() }
            mutable.value = MusicState(library, loading = false)
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(loading = false, failed = true, message = "Could not open Music: ${e.message}")
        }
    } }
    private suspend fun commit(library: MusicLibrary) {
        withContext(Dispatchers.IO) { store.save(library) }
        mutable.value = mutable.value.copy(library = library)
    }
    private fun edit(change: (MusicLibrary) -> MusicLibrary) { work.trySend {
        check(!mutable.value.failed && !mutable.value.loading) { "Open the music library before editing" }
        commit(change(mutable.value.library))
    } }
    fun score(id: String, change: (MusicScore) -> MusicScore) = edit { library ->
        library.copy(scores = library.scores.map { if (it.id == id) change(it) else it })
    }
    fun import(uris: List<Uri>) { if (uris.isEmpty()) return; work.trySend {
        check(!mutable.value.failed) { "Open the music library before importing" }
        mutable.value = mutable.value.copy(busy = true)
        var failed = 0
        val reviews = mutableListOf<MusicImportReview>()
        for (uri in uris) {
            var added: MusicScore? = null
            try {
                added = withContext(Dispatchers.IO) { store.import(uri) }
                reviews += withContext(Dispatchers.IO) { store.review(added) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { added?.let { withContext(Dispatchers.IO) { store.delete(it.id) } }; failed++ }
        }
        mutable.value = mutable.value.copy(busy = false, reviews = mutable.value.reviews + reviews,
            message = if (failed > 0) "$failed PDF(s) could not be read. Check that they are unlocked." else null)
    } }
    fun reviewExisting(id: String) { work.trySend {
        val source = mutable.value.library.scores.find { it.id == id } ?: return@trySend
        mutable.value = mutable.value.copy(busy = true)
        val review = withContext(Dispatchers.IO) { store.review(source, existing = true) }
        mutable.value = mutable.value.copy(busy = false, reviews = mutable.value.reviews + review)
    } }
    fun dismissReview(id: String) { work.trySend {
        val review = mutable.value.reviews.find { it.source.id == id } ?: return@trySend
        if (!review.existing) withContext(Dispatchers.IO) { store.delete(id) }
        mutable.value = mutable.value.copy(reviews = mutable.value.reviews.filterNot { it.source.id == id })
    } }
    fun keepWhole(id: String) { work.trySend {
        val review = mutable.value.reviews.find { it.source.id == id } ?: return@trySend
        if (!review.existing) {
            withContext(Dispatchers.IO) { store.keepWhole(id) }
            commit(mutable.value.library.copy(scores = mutable.value.library.scores + review.source))
        }
        mutable.value = mutable.value.copy(reviews = mutable.value.reviews.filterNot { it.source.id == id }, message = "Complete PDF kept in Music")
    } }
    fun extractParts(id: String, requests: List<MusicPartRequest>) { work.trySend {
        val review = mutable.value.reviews.find { it.source.id == id } ?: return@trySend
        mutable.value = mutable.value.copy(busy = true)
        val source = mutable.value.library.scores.find { it.id == id } ?: review.source
        val parts = withContext(Dispatchers.IO) { store.extract(source, requests) }
        try { commit(mutable.value.library.copy(scores = mutable.value.library.scores + parts)) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { withContext(Dispatchers.IO) { parts.forEach { store.delete(it.id) } }; throw e }
        // Never remove an existing score or alter its set lists while extracting parts.
        if (!review.existing) withContext(Dispatchers.IO) { store.delete(source.id) }
        mutable.value = mutable.value.copy(busy = false, reviews = mutable.value.reviews.filterNot { it.source.id == id },
            message = "${parts.size} part(s) added to Music")
    } }
    fun delete(id: String) { work.trySend {
        commit(mutable.value.library.let { library -> library.copy(scores = library.scores.filterNot { it.id == id },
            sets = library.sets.map { it.copy(scores = it.scores.filterNot { score -> score == id }) }) })
        withContext(Dispatchers.IO) { store.delete(id) }
    } }
    /**
     * A second copy of a score inside Music: the PDF is copied and its annotations, notes and marks
     * come with it. The original and every set list that points at it are left exactly as they were.
     */
    fun duplicate(id: String) { work.trySend {
        val score = mutable.value.library.scores.find { it.id == id } ?: return@trySend
        mutable.value = mutable.value.copy(busy = true)
        val copy = withContext(Dispatchers.IO) { store.duplicate(score) }
        commit(mutable.value.library.copy(scores = mutable.value.library.scores + copy))
        mutable.value = mutable.value.copy(busy = false, message = "Copied “${score.title}”")
    } }
    /** Stamped when the reader opens a score; drives "Continue playing" and the recent sort. */
    fun opened(id: String) = score(id) { it.copy(opened = System.currentTimeMillis()) }
    fun move(setId: String, scoreId: String, delta: Int) = set(setId) { s ->
        val ids = s.scores.toMutableList(); val from = ids.indexOf(scoreId); val to = from + delta
        if (from >= 0 && to in ids.indices) { ids.removeAt(from); ids.add(to, scoreId) }
        s.copy(scores = ids)
    }
    fun newSet(name: String, scores: List<String> = emptyList()) = edit { it.copy(sets = it.sets + MusicSet(UUID.randomUUID().toString(), name.trim(), scores)) }
    fun set(id: String, change: (MusicSet) -> MusicSet) = edit { it.copy(sets = it.sets.map { s -> if (s.id == id) change(s) else s }) }
    fun deleteSet(id: String) = edit { it.copy(sets = it.sets.filterNot { s -> s.id == id }) }
    fun export(id: String, uri: Uri) { work.trySend {
        withContext(Dispatchers.IO) { store.export(id, uri) }
        mutable.value = mutable.value.copy(message = "Original PDF exported (pencil annotations stay in Music)")
    } }
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }
}
