package com.folio.notes.music

import org.json.JSONObject

fun main() {
    val first = MusicScore("11111111-1111-1111-1111-111111111111", "Étude ♯1", 7,
        composer = "Chopin", part = "Piano", notes = "Hold the final chord\nWatch the conductor", starred = true,
        page = 4, bpm = 132, beats = 3, marks = listOf(MusicMark(0, "A"), MusicMark(5, "Coda")),
        ink = listOf(MusicStroke(4, listOf(MusicPoint(.125f, .25f), MusicPoint(.75f, .875f)))))
    val second = MusicScore("22222222-2222-2222-2222-222222222222", "Encore", 1)
    val library = MusicLibrary(listOf(first, second), listOf(MusicSet("concert", "Concert", listOf(second.id, first.id))))
    check(MusicCodec.decode(MusicCodec.encode(library)) == library) { "Music round trip lost score or performance data" }
    check(MusicCodec.decode(MusicCodec.encode(MusicLibrary())) == MusicLibrary())
    val played = library.copy(scores = listOf(first.copy(opened = 1_760_000_000_000L), second))
    check(MusicCodec.decode(MusicCodec.encode(played)) == played) { "Last-played time lost" }
    val legacy = JSONObject(MusicCodec.encode(played)).also { it.getJSONArray("scores").getJSONObject(0).remove("opened") }
    check(MusicCodec.decode(legacy.toString()).scores.first().opened == 0L) { "Index without last-played times rejected" }
    val edited = library.copy(scores = listOf(first.copy(ink = emptyList(), page = 6), second),
        sets = library.sets.map { it.copy(scores = listOf(first.id, second.id)) })
    check(MusicCodec.decode(MusicCodec.encode(edited)) == edited) { "Undo / reorder did not survive reload" }
    val malformed = JSONObject(MusicCodec.encode(library))
    malformed.getJSONArray("scores").getJSONObject(0).put("page", 999).put("bpm", 0).put("beats", 99)
    val clamped = MusicCodec.decode(malformed.toString()).scores.first()
    check(clamped.page == 6 && clamped.bpm == 30 && clamped.beats == 12)
    malformed.getJSONArray("scores").getJSONObject(0).put("id", "../notebooks/score")
    check(runCatching { MusicCodec.decode(malformed.toString()) }.isFailure) { "Unsafe file ID accepted" }
    check(runCatching { MusicCodec.decode("{broken") }.isFailure)
    check(runCatching { MusicCodec.decode(JSONObject(MusicCodec.encode(library)).put("version", 2).toString()) }.isFailure)
    check(runCatching { MusicCodec.decode("{\"version\":1}") }.isFailure) { "Partial library silently accepted" }
    val detected = MusicParts.suggest(listOf("Concert collection", "Flute 1\nAllegro", "more notation",
        "Clarinet in Bb 2", "Clarinet in Bb 2", "Full Score\nFlute\nOboe", "no heading", "Violin I", "Violin 1"))
    check(detected.map { it.instrument } == listOf("Flute 1", "Clarinet in Bb 2", "Violin 1")) { detected }
    check(detected[0].pages == listOf(1, 2) && detected[0].inferredPages == 1)
    check(detected[1].pages == listOf(3, 4))
    check(detected[2].pages == listOf(7, 8))
    check(MusicParts.suggest(listOf("Flute\nOboe\nClarinet", "", "Allegro")).isEmpty())
    check(MusicParts.suggest(listOf("", "", "")).isEmpty())
    check(MusicParts.suggest(listOf("Bb Clarinet 1")).single().instrument == "Clarinet in Bb 1")
    check(MusicParts.suggest(listOf("B♭ Clarinet 1", "Clarinet in Bb 1")).single().pages == listOf(0, 1))
    check(MusicParts.suggest(listOf("Bass Clarinet", "Alto Saxophone", "French Horn 2")).map { it.instrument } == listOf("Bass clarinet", "Alto saxophone", "Horn 2"))
    check(MusicParts.parsePages("2-4, 7, 3", 7) == listOf(1, 2, 3, 6))
    for (invalid in listOf("", "0", "8", "4-2", "2,", "1-two", "1-999999999999", "1, wrong")) {
        check(runCatching { MusicParts.parsePages(invalid, 7) }.isFailure) { "Accepted invalid range: $invalid" }
    }
    check(MusicParts.pageLabel(listOf(0, 1, 2, 4, 6, 7)) == "1-3, 5, 7-8")
    val extracted = MusicParts.extracted(first, second.id, MusicPartRequest("My piano part", "Piano", listOf(4, 5)))
    check(extracted.pages == 2 && extracted.page == 0 && extracted.ink.single().page == 0 && extracted.marks.single() == MusicMark(1, "Coda"))
    check(MusicParts.extracted(first.copy(opened = 5), second.id, MusicPartRequest("Part", "", listOf(0))).opened == 0L) { "Extracted part inherited last-played time" }
    check(extracted.composer == first.composer && extracted.bpm == first.bpm && extracted.notes == first.notes)
    check(MusicCodec.decode(MusicCodec.encode(MusicLibrary(listOf(extracted)))).scores.single() == extracted)
    check(runCatching { MusicParts.extracted(first, second.id, MusicPartRequest("Bad", "", listOf(7))) }.isFailure)
    println("Music smoke passed: persistence, part detection, ambiguous/scanned pages, strict page ranges and extracted annotation mapping")
}
