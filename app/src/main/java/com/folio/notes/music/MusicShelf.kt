package com.folio.notes.music

/**
 * Shelf order; a set list always keeps its own running order instead. Kept Android-free beside the
 * models so the smoke test can exercise ordering and search without a device.
 */
internal enum class MusicSort(val label: String) {
    ADDED("Recently added"),
    TITLE("Title A–Z"),
    TITLE_DESC("Title Z–A"),
    COMPOSER("Composer A–Z"),
    PAGES("Most pages"),
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

/** Title, composer, part, rehearsal notes and rehearsal-mark names all answer a shelf search. */
internal fun MusicScore.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return title.contains(needle, true) || composer.contains(needle, true) || part.contains(needle, true) ||
        notes.contains(needle, true) || marks.any { it.name.contains(needle, true) }
}

/** 0 until a score has been opened; otherwise how far through it the saved page sits. */
internal fun MusicScore.readingProgress(): Float =
    if (opened <= 0 || pages < 2) 0f else ((page + 1).toFloat() / pages).coerceIn(0f, 1f)

/** The nearest rehearsal mark strictly after [page]; navigation never returns the current page. */
internal fun nextMark(page: Int, marks: List<MusicMark>): MusicMark? =
    marks.filter { it.page > page }.minByOrNull { it.page }

internal fun previousMark(page: Int, marks: List<MusicMark>): MusicMark? =
    marks.filter { it.page < page }.maxByOrNull { it.page }

/**
 * Shelf order for a plain score list. "Recently added" reads the index order: imported scores are
 * always appended, so reversing the stored order lists the newest first without another field.
 */
internal fun organizeScores(scores: List<MusicScore>, query: String, sort: MusicSort, favoritesOnly: Boolean = false): List<MusicScore> {
    val filtered = scores.filter { !favoritesOnly || it.starred }.filter { it.matchesQuery(query) }
    return when (sort) {
        MusicSort.ADDED -> filtered.reversed()
        MusicSort.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.TITLE_DESC -> filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.COMPOSER -> filtered.sortedWith(compareBy<MusicScore, String>(String.CASE_INSENSITIVE_ORDER) { it.composer.ifBlank { "\uFFFF" } }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.PAGES -> filtered.sortedWith(compareByDescending<MusicScore> { it.pages }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        MusicSort.RECENT -> filtered.sortedWith(compareByDescending<MusicScore> { it.opened }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
