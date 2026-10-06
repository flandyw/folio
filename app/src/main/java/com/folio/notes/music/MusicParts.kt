package com.folio.notes.music

/** Suggestions are never imported without review: unlabelled continuation pages are uncertain. */
internal data class MusicPartSuggestion(val instrument: String, val pages: List<Int>, val inferredPages: Int = 0)
internal data class MusicPartRequest(val title: String, val instrument: String, val pages: List<Int>)
internal data class MusicImportReview(val source: MusicScore, val suggestions: List<MusicPartSuggestion>,
    val notice: String, val existing: Boolean = false)

internal object MusicParts {
    private val names = listOf(
        "Bass clarinet" to "bass\\s+clarinet", "English horn" to "english\\s+horn|cor\\s+anglais",
        "Contrabassoon" to "contrabassoon|contra\\s*bassoon", "Piccolo" to "piccolo|picc\\.?",
        "Flute" to "flutes?|fl\\.", "Oboe" to "oboes?|ob\\.", "Clarinet" to "clarinets?|cl\\.",
        "Bassoon" to "bassoons?|bsn\\.", "Soprano saxophone" to "soprano\\s+sax(?:ophone)?",
        "Alto saxophone" to "alto\\s+sax(?:ophone)?", "Tenor saxophone" to "tenor\\s+sax(?:ophone)?",
        "Baritone saxophone" to "baritone\\s+sax(?:ophone)?", "Saxophone" to "saxophones?",
        "Trumpet" to "trumpets?|tpt\\.", "Cornet" to "cornets?", "Flugelhorn" to "flugelhorns?",
        "Horn" to "(?:french\\s+)?horns?|hn\\.", "Bass trombone" to "bass\\s+trombone",
        "Trombone" to "trombones?|tbn\\.", "Euphonium" to "euphoniums?", "Tuba" to "tubas?",
        "Timpani" to "timpani", "Percussion" to "percussion|perc\\.", "Drum kit" to "drum\\s*(?:kit|set)|drums",
        "Marimba" to "marimba", "Vibraphone" to "vibraphone", "Xylophone" to "xylophone",
        "Glockenspiel" to "glockenspiel", "Harp" to "harp", "Piano" to "piano|pno\\.",
        "Organ" to "organ", "Guitar" to "(?:electric\\s+|acoustic\\s+)?guitar",
        "Bass guitar" to "(?:electric\\s+)?bass\\s+guitar|electric\\s+bass",
        "Violin" to "violins?|vln\\.", "Viola" to "violas?|vla\\.", "Cello" to "(?:violon)?cellos?|vc\\.",
        "Double bass" to "double\\s+bass(?:es)?|contrabass(?:es)?|string\\s+bass(?:es)?",
        "Soprano" to "soprano", "Alto" to "alto", "Tenor" to "tenor", "Bass" to "bass",
    ).map { (name, pattern) -> name to Regex(
        """^(?:solo\s+)?(?:([A-G](?:b|#|♭|♯)?)\s+)?(?:$pattern)(\s+(?:in\s+)?[A-G](?:b|#|♭|♯)?(?:[-\s]+flat|[-\s]+sharp)?)?(?:\s*[-:]?\s*(1|2|3|4|5|6|I|II|III|IV|V|VI))?\s*$""", RegexOption.IGNORE_CASE) }
    private val barrier = Regex("\\b(full\\s+score|conductor|table\\s+of\\s+contents|instrumentation)\\b", RegexOption.IGNORE_CASE)

    fun suggest(pageTexts: List<String>): List<MusicPartSuggestion> {
        val pages = linkedMapOf<String, MutableList<Int>>()
        val inferred = mutableMapOf<String, Int>()
        var current: String? = null
        pageTexts.forEachIndexed { index, text ->
            val lines = text.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }.take(18).toList()
            val labels = lines.mapNotNull { line ->
                names.firstNotNullOfOrNull { (name, regex) -> regex.matchEntire(line)?.let { match ->
                    val rawPitch = (match.groupValues[2].ifBlank { match.groupValues[1] }).trim().replace(Regex("^in\\s+", RegexOption.IGNORE_CASE), "")
                    val pitch = rawPitch.replace("♭", "b").replace("♯", "#")
                        .replace(Regex("[-\\s]+flat", RegexOption.IGNORE_CASE), "b")
                        .replace(Regex("[-\\s]+sharp", RegexOption.IGNORE_CASE), "#")
                        .let { it.take(1).uppercase() + it.drop(1).lowercase() }
                    val number = match.groupValues[3].uppercase().let { when (it) { "I" -> "1"; "II" -> "2"; "III" -> "3"; "IV" -> "4"; "V" -> "5"; "VI" -> "6"; else -> it } }
                    listOf(name, pitch.takeIf { it.isNotEmpty() }?.let { "in $it" }.orEmpty(), number).filter { it.isNotEmpty() }.joinToString(" ")
                } }
            }.distinct()
            if (lines.any { barrier.containsMatchIn(it) } || labels.size > 1) {
                current = null // Full-score or ambiguous page: never carry a preceding part through it.
            } else {
                val labelled = labels.singleOrNull()
                if (labelled != null) current = labelled
                current?.let { name ->
                    pages.getOrPut(name) { mutableListOf() }.add(index)
                    if (labelled == null) inferred[name] = (inferred[name] ?: 0) + 1
                }
            }
        }
        return pages.map { (name, indices) -> MusicPartSuggestion(name, indices, inferred[name] ?: 0) }
    }

    /** Strict page entry: a typo must not silently import a different instrument. */
    fun parsePages(text: String, pageCount: Int): List<Int> {
        require(text.isNotBlank()) { "Enter pages, for example 3-6, 9" }
        val pages = sortedSetOf<Int>()
        text.split(',', ';').forEach { token ->
            val match = Regex("^\\s*(\\d+)\\s*(?:[-–—]\\s*(\\d+)\\s*)?$").matchEntire(token)
                ?: error("Use page numbers or ranges, for example 3-6, 9")
            val first = match.groupValues[1].toIntOrNull() ?: error("Page number is too large")
            val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: if (match.groupValues[2].isEmpty()) first else error("Page number is too large")
            require(first in 1..pageCount && last in first..pageCount) { "Use ascending pages between 1 and $pageCount" }
            for (p in first..last) pages += p - 1
        }
        return pages.toList()
    }

    fun pageLabel(pages: List<Int>): String {
        val sorted = pages.distinct().sorted()
        val ranges = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            val start = sorted[i]; var end = start
            while (i + 1 < sorted.size && sorted[i + 1] == end + 1) { i++; end++ }
            ranges += if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"
            i++
        }
        return ranges.joinToString(", ")
    }

    fun extracted(source: MusicScore, id: String, request: MusicPartRequest): MusicScore {
        require(request.title.isNotBlank() && request.pages.isNotEmpty()) { "Give the part a title and select pages" }
        require(request.pages == request.pages.distinct().sorted() && request.pages.all { it in 0 until source.pages }) { "Invalid part pages" }
        val mapping = request.pages.withIndex().associate { it.value to it.index }
        return source.copy(id = id, title = request.title.trim(), part = request.instrument.trim(), pages = request.pages.size,
            page = 0, opened = 0, marks = source.marks.mapNotNull { mark -> mapping[mark.page]?.let { mark.copy(page = it) } },
            ink = source.ink.mapNotNull { stroke -> mapping[stroke.page]?.let { stroke.copy(page = it) } })
    }
}
