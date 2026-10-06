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

    // Shelf: adding order, sorting, the search scope, progress and rehearsal-mark navigation.
    val aria = MusicScore("33333333-3333-3333-3333-333333333333", "Aria", 30, composer = "Handel", part = "Violin",
        notes = "watch the conductor", marks = listOf(MusicMark(2, "Coda")), page = 9, opened = 99)
    val shelf = listOf(second, aria)
    check(organizeScores(shelf, "", MusicSort.ADDED) == shelf.reversed()) { "Recently added did not follow import order" }
    check(organizeScores(shelf, "", MusicSort.TITLE).map { it.title } == listOf("Aria", "Encore")) { "Title sort wrong" }
    check(organizeScores(shelf, "", MusicSort.TITLE_DESC).map { it.title } == listOf("Encore", "Aria")) { "Reverse title sort wrong" }
    check(organizeScores(shelf, "", MusicSort.PAGES).first().id == aria.id) { "Page sort wrong" }
    check(organizeScores(shelf, "", MusicSort.COMPOSER).first().id == aria.id) { "Composer sort wrong" }
    check(organizeScores(shelf, "", MusicSort.RECENT).first().id == aria.id) { "Recent sort wrong" }
    check(organizeScores(shelf, "handel", MusicSort.TITLE).single().id == aria.id) { "Search ignored the composer" }
    check(organizeScores(shelf, "conductor", MusicSort.TITLE).single().id == aria.id) { "Search ignored rehearsal notes" }
    check(organizeScores(shelf, "coda", MusicSort.TITLE).single().id == aria.id) { "Search ignored rehearsal marks" }
    check(organizeScores(shelf, "Encore", MusicSort.TITLE).single().id == second.id) { "Search ignored the title" }
    check(organizeScores(shelf, "nothing here", MusicSort.TITLE).isEmpty()) { "Search matched nothing but returned scores" }
    check(organizeScores(listOf(second.copy(starred = true), aria), "", MusicSort.TITLE, MusicFilter(favoritesOnly = true)).single().id == second.id) { "Favourites filter ignored" }
    check(aria.readingProgress() > 0f && second.readingProgress() == 0f) { "Reading progress wrong" }
    check(MusicScore("44444444-4444-4444-4444-444444444444", "Fresh", 9).readingProgress() == 0f) { "Unopened score claimed progress" }
    check(nextMark(0, listOf(MusicMark(0, "A"), MusicMark(5, "Coda"))) == MusicMark(5, "Coda")) { "Next mark wrong" }
    check(nextMark(5, listOf(MusicMark(0, "A"), MusicMark(5, "Coda"))) == null) { "Next mark ran past the last one" }
    check(previousMark(5, listOf(MusicMark(0, "A"), MusicMark(5, "Coda"))) == MusicMark(0, "A")) { "Previous mark wrong" }
    check(previousMark(0, listOf(MusicMark(0, "A"), MusicMark(5, "Coda"))) == null) { "Previous mark ran before the first one" }
    check(MusicTempos.map { it.label }.distinct().size == MusicTempos.size && MusicTempos.all { it.bpm in 30..240 }) { "Bad metronome presets" }
    check(musicSort(null) == MusicSort.TITLE && musicSort("PAGES") == MusicSort.PAGES && musicSort("bogus") == MusicSort.TITLE) { "Shelf order preference parsing wrong" }

    // Annotations: the editor's tools on a score, and the model that carries them.
    val pen = MusicStroke(0, listOf(MusicPoint(.1f, .1f), MusicPoint(.4f, .5f)))
    val highlight = MusicStroke(0, listOf(MusicPoint(.2f, .2f), MusicPoint(.8f, .3f)), tool = MUSIC_HIGHLIGHTER, color = 0xFFFFE24D.toInt(), width = 18f, opacity = 72f / 255f)
    val line = MusicStroke(1, listOf(MusicPoint(.2f, .2f), MusicPoint(.6f, .7f)), tool = "LINE", width = 2f)
    val star = MusicStroke(1, listOf(MusicPoint(.3f, .3f), MusicPoint(.7f, .8f)), tool = "STAR", style = "DASHED")
    val label = MusicText(0, .5f, .05f, "mf")
    val annotated = MusicScore("55555555-5555-5555-5555-555555555555", "Fugue", 3,
        ink = listOf(pen, highlight, line, star), texts = listOf(label))
    check(MusicCodec.decode(MusicCodec.encode(MusicLibrary(listOf(annotated)))).scores.single() == annotated) { "Styled annotations lost on reload" }
    val styled = JSONObject(MusicCodec.encode(MusicLibrary(listOf(annotated)))).getJSONArray("scores").getJSONObject(0)
    check(styled.getJSONArray("ink").getJSONObject(1).getString("tool") == MUSIC_HIGHLIGHTER)
    check(styled.getJSONArray("ink").getJSONObject(3).getString("style") == "DASHED")
    check(styled.getJSONArray("texts").getJSONObject(0).getString("text") == "mf")
    // A default stroke writes no style keys and a score without labels writes no labels array, so an
    // index that an older Folio wrote stays byte-identical and one it reads stays readable.
    val plain = JSONObject(MusicCodec.encode(MusicLibrary(listOf(first)))).getJSONArray("scores").getJSONObject(0)
    check(plain.getJSONArray("ink").getJSONObject(0).keys().asSequence().toSet() == setOf("page", "points")) { "Default stroke grew style keys" }
    check(!plain.has("texts")) { "Score without labels wrote a labels array" }
    val legacyStyled = JSONObject(MusicCodec.encode(MusicLibrary(listOf(annotated))))
    val legacyStrokes = legacyStyled.getJSONArray("scores").getJSONObject(0).getJSONArray("ink")
    legacyStrokes.getJSONObject(0).put("tool", "NONSENSE").put("width", 9999)
    check(MusicCodec.decode(legacyStyled.toString()).scores.single().ink.first().width == 96f) { "Annotated width not clamped" }

    check(MusicInk.isShape("STAR") && !MusicInk.isShape(MUSIC_PEN) && MusicInk.isFreehand(MUSIC_HIGHLIGHTER))
    check(MusicInk.shapePoints("LINE", line.points) == line.points) { "A line should keep its two corners" }
    val rectangle = MusicInk.shapePoints("RECTANGLE", line.points)
    check(rectangle.size == 5 && rectangle.first() == rectangle.last()) { "Rectangle should close its outline" }
    check(MusicInk.shapePoints("HEXAGON", line.points).size == 7 && MusicInk.shapePoints("STAR", line.points).size == 11)
    check(MusicInk.shapePoints("ELLIPSE", line.points).size == 65) { "Ellipse outline wrong" }
    // The drag box is normalized, so a shape dragged up and left is the same shape.
    val upLeft = MusicInk.shapePoints("RECTANGLE", listOf(MusicPoint(.6f, .7f), MusicPoint(.2f, .2f)))
    check(upLeft.all { it.x in .2f..0.6f && it.y in .2f..0.7f }) { "Reversed drag drew outside its box" }
    check(MusicInk.shapePoints("PENTAGON", listOf(MusicPoint(.6f, .7f), MusicPoint(.2f, .2f)))
        .all { it.x in .2f..0.6f && it.y in .2f..0.7f }) { "Reversed polygon drag drew outside its box" }

    // Lasso: a stroke is held only when every sample is enclosed.
    val loop = listOf(MusicPoint(.05f, .05f), MusicPoint(.95f, .05f), MusicPoint(.95f, .95f), MusicPoint(.05f, .95f))
    check(MusicInk.selects(loop, pen) && MusicInk.selects(loop, line) && MusicInk.selects(loop, label))
    val half = listOf(MusicPoint(.05f, .05f), MusicPoint(.3f, .05f), MusicPoint(.3f, .95f), MusicPoint(.05f, .95f))
    check(!MusicInk.selects(half, pen)) { "A half-crossed stroke was selected" }
    check(!MusicInk.selects(listOf(MusicPoint(.1f, .1f)), pen)) { "A two-point tap selected a stroke" }
    check(MusicInk.textAt(listOf(label), 0, MusicPoint(.51f, .06f)) == 0 && MusicInk.textAt(listOf(label), 1, MusicPoint(.51f, .06f)) == null)
    check(MusicInk.textAt(listOf(label), 0, MusicPoint(.9f, .9f)) == null) { "A tap far from a label edited it" }

    check(MusicInk.translated(pen, .1f, .2f).points.first() == MusicPoint(.2f, .3f))
    check(MusicInk.translated(label, .1f, .2f).x == .6f)
    check(MusicInk.recolored(highlight, 0xFF000000.toInt()).opacity >= .35f) { "A recoloured highlight lost its translucency" }
    check(MusicInk.recolored(pen, 0xFF000000.toInt()).color == 0xFF000000.toInt())
    check(MusicInk.duplicate(pen).points != pen.points) { "A duplicate sat on top of its original" }

    // Erasing: a touched line is shortened, a touched shape or whole-stroke erase removes it.
    val long = MusicStroke(0, (0..20).map { MusicPoint(it / 20f, .5f) })
    val trimmed = MusicInk.erased(listOf(long), MusicPoint(.5f, .5f), .08f, whole = false)
    check(trimmed.size == 2 && trimmed.all { it.points.isNotEmpty() }) { "Rubber eraser did not split the line" }
    check(trimmed[0].points.last().x < .42f && trimmed[1].points.first().x > .58f) { "Rubber eraser left a hole of the wrong size" }
    check(trimmed.all { it.tool == MUSIC_PEN && it.page == 0 }) { "Trimmed runs lost their style" }
    check(MusicInk.erased(listOf(long), MusicPoint(0f, 0f), .05f, whole = false).single().points.size == 21) { "An untouched line was edited" }
    check(MusicInk.erased(listOf(long), MusicPoint(.5f, .5f), .08f, whole = true).isEmpty()) { "Whole-stroke eraser kept a touched line" }
    check(MusicInk.erased(listOf(star), MusicPoint(.31f, .31f), .05f, whole = false).isEmpty()) { "A touched shape survived the eraser" }

    val extractedWithLabels = MusicParts.extracted(annotated, second.id, MusicPartRequest("Part", "", listOf(0, 1)))
    check(extractedWithLabels.texts.single().page == 0 && extractedWithLabels.ink.size == 4) { "Extracted part lost its annotations" }
    check(MusicCodec.decode(MusicCodec.encode(MusicLibrary(listOf(extractedWithLabels)))).scores.single() == extractedWithLabels)

    // ---- Shelf scope added with the filters: new orders, multi-term search and score state. ----
    check(MusicSort.entries.filter { it in setOf(MusicSort.PART, MusicSort.SHORTEST, MusicSort.MARKS) }.size == 3) { "New shelf orders missing" }
    check(MusicSort.entries.map { it.label }.distinct().size == MusicSort.entries.size) { "Two shelf orders share a label" }
    check(!MusicFilter().active && MusicFilter(favoritesOnly = true).active && MusicFilter(composer = "Bach").active) { "Filter activity wrong" }
    check(MusicFilter(composer = "Handel").withComposer("Handel") == MusicFilter()) { "Re-picking a composer did not clear the filter" }
    check(MusicFilter().withComposer("Bach") == MusicFilter(composer = "Bach")) { "Picking a composer did not set the filter" }
    check(organizeScores(shelf, "", MusicSort.PART).last().id == second.id) { "Instrument sort did not put a blank part last" }
    check(organizeScores(shelf, "", MusicSort.SHORTEST).first().id == second.id) { "Shortest sort wrong" }
    check(organizeScores(shelf, "", MusicSort.MARKS).first().id == aria.id) { "Rehearsal-mark sort wrong" }
    check(organizeScores(listOf(annotated, second), "", MusicSort.TITLE, MusicFilter(annotatedOnly = true)).single().id == annotated.id) { "Annotations filter ignored" }
    check(organizeScores(listOf(aria, aria.copy(page = 29), second), "", MusicSort.TITLE, MusicFilter(unfinishedOnly = true)).single().id == aria.id) { "Unfinished filter wrong" }
    check(organizeScores(shelf, "", MusicSort.TITLE, MusicFilter(composer = "Handel")).single().id == aria.id) { "Composer filter wrong" }
    check(composerNames(listOf(aria, aria.copy(composer = "Bach"), second, first.copy(composer = "Bach"))) == listOf("Bach", "Handel")) { "Composer names wrong" }
    check(aria.matchesQuery("handel violin") && !aria.matchesQuery("handel trumpet")) { "Multi-term search wrong" }
    check(second.matchesQuery("   ")) { "A blank search must keep every score" }
    check(!second.isStarted() && aria.isStarted() && aria.isUnfinished() && !aria.isFinished() && aria.copy(page = 29).isFinished()) { "Started / finished flags wrong" }
    check(second.readingProgressLabel().isEmpty() && aria.readingProgressLabel().endsWith("%")) { "Progress label wrong" }
    check(second.copy(title = "").displayTitle() == "Untitled score") { "Blank title not replaced" }
    check(annotated.annotationCount() == annotated.ink.size + annotated.texts.size) { "Annotation count wrong" }

    // ---- Tempo names and set-list totals. ----
    check(tempoName(130) == "Allegro" && tempoName(40) == "Largo" && tempoName(132) == "Allegro") { "Tempo name wrong" }
    check((30..240).all { tempoName(it) in MusicTempos.map { tempo -> tempo.label } }) { "Tempo name outside the presets" }
    val bigLibrary = MusicLibrary(listOf(second, aria))
    val bigSet = MusicSet("set-1", "Big", listOf(second.id, aria.id, "99999999-9999-9999-9999-999999999999"))
    check(bigSet.scoresIn(bigLibrary) == listOf(second, aria)) { "Set list kept an id with no score" }
    check(bigSet.totalPages(bigLibrary) == second.pages + aria.pages) { "Set list page total wrong" }
    check(bigSet.estimatedMinutes(bigLibrary) > 0 && MusicSet("s2", "Empty", emptyList()).estimatedMinutes(bigLibrary) == 0) { "Set list duration wrong" }
    check(bigSet.reversedOrder().scores == bigSet.scores.reversed()) { "Set list reversal wrong" }
    val shuffled = shuffledOrder(listOf("a", "b", "c", "d", "e"), kotlin.random.Random(1))
    check(shuffled == shuffledOrder(listOf("a", "b", "c", "d", "e"), kotlin.random.Random(1)) && shuffled.toSet() == setOf("a", "b", "c", "d", "e")) { "Shuffle was not reproducible" }

    // ---- The selection helpers: bounds, tap-to-select, restyle and clamping. ----
    val box = MusicInk.bounds(listOf(pen), emptyList())
    check(box != null && box[0] == .1f && box[1] == .1f && box[2] == .4f && box[3] == .5f) { "Annotation bounds wrong" }
    check(MusicInk.bounds(emptyList(), emptyList()) == null) { "Empty bounds should be null" }
    check(MusicInk.strokeAt(listOf(pen, line), MusicPoint(.1f, .1f)) == 0 && MusicInk.strokeAt(listOf(pen), MusicPoint(.99f, .99f)) == null) { "Tap-to-select wrong" }
    check(MusicInk.restyled(pen, width = 9999f).width == 96f && MusicInk.restyled(pen, opacity = 0f).opacity == 0.05f) { "Restyle clamp wrong" }
    check(MusicInk.restyled(label, size = 1f).size == 8f) { "Label size clamp wrong" }
    check(MusicInk.normalized(MusicStroke(0, listOf(MusicPoint(-1f, 2f)))).points.single() == MusicPoint(0f, 1f)) { "Normalize did not clamp onto the page" }

    // ---- Import review helpers: filmstrip toggling and merging ticked parts. ----
    check(MusicParts.togglePage(listOf(1, 3, 5), 3) == listOf(1, 5) && MusicParts.togglePage(listOf(1, 3, 5), 4) == listOf(1, 3, 4, 5) && MusicParts.togglePage(emptyList(), 2) == listOf(2)) { "Filmstrip page toggle wrong" }
    check(MusicParts.merged(emptyList()) == null && MusicParts.merged(listOf(MusicPartRequest("A", "", emptyList()))) == null) { "Merging nothing should be null" }
    val mergedPart = MusicParts.merged(listOf(MusicPartRequest("A", "Flute", listOf(2, 3)), MusicPartRequest("B", "Oboe", listOf(1, 3))))
    check(mergedPart != null && mergedPart.title == "A" && mergedPart.pages == listOf(1, 2, 3) && mergedPart.instrument == "Flute, Oboe") { "Merged part wrong: $mergedPart" }
    println("Music smoke passed: persistence, part detection, ambiguous/scanned pages, strict page ranges, extracted annotation mapping, shelf ordering, shelf sorts and filters, set-list totals, tempo names and the new selection and merge helpers")
}
