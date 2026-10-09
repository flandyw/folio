package com.folio.notes.music

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Shelf order; a set list always keeps its own running order instead. Kept Android-free beside the
 * models so the smoke test can exercise ordering and search without a device.
 */
internal enum class MusicSort(val label: String) {
    ADDED("Recently added"),
    TITLE("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    COMPOSER("Composer A–Z"),
    PART("Instrument A–Z"),
    PAGES("Most pages"),
    SHORTEST("Fewest pages"),
    MARKS("Most rehearsal marks"),
    RECENT("Recently played"),
}

/** Restores the saved shelf order; an unknown or missing value falls back to title order. */
internal fun musicSort(raw: String?): MusicSort =
    runCatching { MusicSort.valueOf(raw ?: "") }.getOrDefault(MusicSort.TITLE)

/** A tempo marking offered as a one-tap metronome preset. */
internal data class MusicTempo(val label: String, val bpm: Int)

internal val MusicTempos = listOf(
    MusicTempo("Largo", 50), MusicTempo("Adagio", 66), MusicTempo("Andante", 80),
    MusicTempo("Moderato", 100), MusicTempo("Allegro", 132), MusicTempo("Presto", 176),
)

/** The marking nearest [bpm], so the metronome can name whatever tempo the user dials in. */
internal fun nearestTempo(bpm: Int): MusicTempo =
    MusicTempos.minByOrNull { abs(it.bpm - bpm) } ?: MusicTempos.first()

/** The tempo name shown beside the BPM readout; ties fall to the slower marking. */
internal fun tempoName(bpm: Int): String = nearestTempo(bpm).label

/**
 * Everything the shelf can narrow to besides the search text. Kept a value type so the held state,
 * the saved preference and the pure ranking function all agree on one shape.
 */
internal data class MusicFilter(
    val favoritesOnly: Boolean = false,
    val annotatedOnly: Boolean = false,
    val unfinishedOnly: Boolean = false,
    val composer: String = "",
) {
    val active: Boolean get() = favoritesOnly || annotatedOnly || unfinishedOnly || composer.isNotBlank()
    /** The same scope with one composer picked (or cleared when [name] is blank). */
    fun withComposer(name: String) = copy(composer = if (composer.equals(name, true)) "" else name)
}

/**
 * Every whitespace-separated term must match somewhere — title, composer, part, rehearsal notes or
 * a rehearsal-mark name — so "shostakovich cello" narrows the shelf rather than widening it.
 */
internal fun MusicScore.matchesQuery(query: String): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (terms.isEmpty()) return true
    return terms.all { term ->
        title.contains(term, true) || composer.contains(term, true) || part.contains(term, true) ||
            notes.contains(term, true) || marks.any { it.name.contains(term, true) }
    }
}

/** 0 until a score has been opened; otherwise how far through it the saved page sits. */
internal fun MusicScore.readingProgress(): Float =
    if (opened <= 0 || pages < 2) 0f else ((page + 1).toFloat() / pages).coerceIn(0f, 1f)

/** Opened at least once, so it has a place to continue from. */
internal fun MusicScore.isStarted(): Boolean = opened > 0

/** Started and left on its final page: the score is done rather than merely opened. */
internal fun MusicScore.isFinished(): Boolean = isStarted() && pages > 0 && page >= pages - 1

/** Started but not yet finished, the shelf's "come back to this" set. */
internal fun MusicScore.isUnfinished(): Boolean = isStarted() && !isFinished()

/** A short percentage for a progress bar's label; blank for a score never opened. */
internal fun MusicScore.readingProgressLabel(): String =
    if (!isStarted() || pages < 2) "" else "${(readingProgress() * 100).roundToInt()}%"

/** The title as shown anywhere it must never be empty. */
internal fun MusicScore.displayTitle(): String = title.ifBlank { "Untitled score" }

/** Pencil strokes plus typed labels, so the shelf can say how marked up a score is. */
internal fun MusicScore.annotationCount(): Int = ink.size + texts.size + pencil.sum()

/** The nearest rehearsal mark strictly after [page]; navigation never returns the current page. */
internal fun nextMark(page: Int, marks: List<MusicMark>): MusicMark? =
    marks.filter { it.page > page }.minByOrNull { it.page }

internal fun previousMark(page: Int, marks: List<MusicMark>): MusicMark? =
    marks.filter { it.page < page }.maxByOrNull { it.page }

/** The score list a set list points at, in running order and dropping ids that no longer exist. */
internal fun MusicSet.scoresIn(library: MusicLibrary): List<MusicScore> =
    scores.mapNotNull { id -> library.scores.find { it.id == id } }

/** Total printed pages a set list holds, the number that decides how heavy the folder is. */
internal fun MusicSet.totalPages(library: MusicLibrary): Int = scoresIn(library).sumOf { it.pages }

/** Roughly how long the set runs, at half a minute a page; 0 for an empty set. */
internal fun MusicSet.estimatedMinutes(library: MusicLibrary): Int {
    val pages = totalPages(library)
    return if (pages == 0) 0 else (pages * 30f / 60f).roundToInt().coerceAtLeast(1)
}

/** The running order reversed, for the set list's "Reverse" action. */
internal fun MusicSet.reversedOrder(): MusicSet = copy(scores = scores.reversed())

/** A shuffled running order; [random] is passed in so the shelf can seed a reproducible shuffle. */
internal fun shuffledOrder(ids: List<String>, random: Random = Random.Default): List<String> = ids.shuffled(random)

/**
 * Shelf order for a plain score list. "Recently added" reads the index order: imported scores are
 * always appended, so reversing the stored order lists the newest first without another field.
 */
internal fun organizeScores(
    scores: List<MusicScore>,
    query: String,
    sort: MusicSort,
    filter: MusicFilter = MusicFilter(),
    folder: String? = null,
): List<MusicScore> {
    val composer = filter.composer.trim()
    val filtered = scores
        .filter { folder == null || it.folder == folder }
        .filter { !filter.favoritesOnly || it.starred }
        .filter { !filter.annotatedOnly || it.annotationCount() > 0 }
        .filter { !filter.unfinishedOnly || it.isUnfinished() }
        .filter { composer.isEmpty() || it.composer.equals(composer, true) }
        .filter { it.matchesQuery(query) }
    return when (sort) {
        MusicSort.ADDED -> filtered.reversed()
        MusicSort.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.TITLE_DESC -> filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.COMPOSER -> filtered.sortedWith(compareBy<MusicScore, String>(String.CASE_INSENSITIVE_ORDER) { it.composer.ifBlank { "\uFFFF" } }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.PART -> filtered.sortedWith(compareBy<MusicScore, String>(String.CASE_INSENSITIVE_ORDER) { it.part.ifBlank { "\uFFFF" } }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.PAGES -> filtered.sortedWith(compareByDescending<MusicScore> { it.pages }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.SHORTEST -> filtered.sortedWith(compareBy<MusicScore> { it.pages }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.MARKS -> filtered.sortedWith(compareByDescending<MusicScore> { it.marks.size }
            .thenByDescending { it.annotationCount() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.RECENT -> filtered.sortedWith(compareByDescending<MusicScore> { it.opened }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}

/** The distinct composers on the shelf, sorted and blank-free, for the composer filter row. */
internal fun composerNames(scores: List<MusicScore>): List<String> =
    scores.map { it.composer.trim() }.filter { it.isNotEmpty() }.distinct()
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
