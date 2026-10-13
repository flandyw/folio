package com.folio.notes

import org.json.JSONObject
import kotlin.math.abs

fun documentEditingSmoke() {
    fun close(a: Float, b: Float) { check(abs(a - b) < .001f) { "$a != $b" } }
    fun frame(id: String, top: Float, width: Float = 100f, height: Float = 100f, writable: Boolean = true) =
        DocumentInkPage(DocumentPageGeometry.Frame(id, width, height,
            DocumentPageGeometry.Bounds(0f, top, 100f, top + 100f), DocumentPageGeometry.Bounds(0f, top, 100f, top + 100f)), 0, writable)
    val frames = listOf(frame("a", 0f), frame("b", 106f, 200f, 200f), frame("c", 212f))
    val stroke = Stroke(Tool.PEN, 0xFF000000.toInt(), 4f, listOf(InkPoint(50f, 80f, .5f)))
    val capture = DocumentInkCapture("a", stroke, frames)
    capture.add(stroke.points, false)
    capture.add(listOf(InkPoint(50f, 150f, 1.2f), InkPoint(50f, 250f, 1.8f)), false)
    check(capture.spanning && !capture.blocked)
    val parts = capture.strokes()
    check(parts.keys == setOf("a", "b", "c"))
    close(parts.getValue("a").single().points.last().y, 100f)
    close(parts.getValue("b").single().points.first().y, 0f)
    close(parts.getValue("b").single().points.last().y, 200f)
    close(parts.getValue("b").single().width, 8f)
    close(parts.getValue("c").single().points.first().y, 0f)
    close(parts.getValue("a").single().points.last().pressure, .7f)
    check(parts.values.flatten().all { s -> s.points.all { it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite() } })
    capture.add(listOf(InkPoint(50f, 50f, 1f)), false)
    check(capture.strokes().getValue("a").size == 2) // Re-entry is a new segment, in the same logical action.
    val skipped = DocumentInkCapture("a", stroke, frames)
    skipped.add(stroke.points + InkPoint(50f, 290f, 1f), false)
    check(skipped.strokes().keys == setOf("a", "b", "c")) // One coalesced history step can jump two boundaries.
    val blocked = DocumentInkCapture("a", stroke, listOf(frames[0], frame("b", 106f, writable = false)))
    blocked.add(stroke.points + InkPoint(50f, 150f), false)
    check(blocked.blocked && blocked.spanning)
    val gap = DocumentInkCapture("a", stroke, frames)
    gap.add(stroke.points + InkPoint(50f, 103f), false)
    check(!gap.spanning && gap.strokes().keys == setOf("a"))
    val diagonal = DocumentInkCapture.clip(InkPoint(-50f, 50f, .5f), InkPoint(150f, 50f, 1.5f), frames[0].frame.paper)!!
    close(diagonal.first.x, 0f); close(diagonal.second.x, 100f); close(diagonal.first.pressure, .75f)
    check(DocumentInkCapture.clip(InkPoint(-10f, -10f), InkPoint(-5f, -5f), frames[0].frame.paper) == null)
    val lasso = DocumentInkCapture("a", stroke, frames)
    lasso.add(listOf(InkPoint(10f, 50f), InkPoint(90f, 50f), InkPoint(90f, 150f), InkPoint(10f, 150f)), true)
    check(lasso.spanning)
    val loopB = lasso.loopFor(frames[1].frame)
    val inside = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(50f, 30f), InkPoint(60f, 35f)))
    check(InkGeometry.lassoSelects(loopB, InkGeometry.lassoBounds(loopB), inside))

    // Opaque sticky paper receives its own local ink; neither underpainting nor clipped tails.
    val sticky = TextBox(id = "sticky", x = 20f, y = 60f, width = 60f, stickyHeight = 30f)
    val surfaces = listOf(frames[0].copy(notes = listOf(sticky)), frames[1])
    val mixed = DocumentInkCapture("a", stroke, surfaces)
    mixed.add(listOf(InkPoint(50f, 40f, .5f), InkPoint(50f, 150f, 1.5f)), false)
    check(mixed.spanning && !mixed.blocked)
    val pagePieces = mixed.strokes().getValue("a")
    check(pagePieces.size == 2)
    close(pagePieces[0].points.last().y, 60f)
    close(pagePieces[1].points.first().y, 90f)
    val stickyPiece = mixed.noteStrokes().getValue("a").getValue("sticky").single()
    close(stickyPiece.points.first().x, 30f); close(stickyPiece.points.first().y, 0f)
    close(stickyPiece.points.last().y, 30f)
    close(stickyPiece.points.first().pressure, .5f + 20f / 110f)
    check(mixed.strokes().containsKey("b"))
    // A stroke can start on an off-paper note and leave it for another page.
    val outside = sticky.copy(x = -40f, y = 70f, width = 60f)
    val offPaper = DocumentInkCapture("a", stroke, listOf(frames[0].copy(notes = listOf(outside)), frames[1]))
    offPaper.add(listOf(InkPoint(-20f, 80f), InkPoint(50f, 150f)), false)
    check(offPaper.noteStrokes().getValue("a").containsKey("sticky") && offPaper.strokes().containsKey("b"))
    // Shapes split their outline, never re-interpret each fragment as a shape's two corners.
    val rectangle = Stroke(Tool.RECTANGLE, 42, 3f, listOf(InkPoint(10f, 40f), InkPoint(90f, 160f)), style = StrokeStyle.DASHED)
    val shape = DocumentInkCapture("a", rectangle, surfaces).generated(listOf(rectangle))
    check(shape.spanning && shape.strokes().keys == setOf("a", "b"))
    check(shape.strokes().values.flatten().all { it.tool == Tool.LINE && it.style == StrokeStyle.DASHED })
    check(shape.strokes().getValue("b").all { it.points.all { p -> p.y in 0f..200f } })
    val graph = rectangle.copy(tool = Tool.GRAPH)
    val graphLines = GraphAxes.strokes(graph, GraphStyle.DEFAULT.copy(grid = true))
    val splitGraph = DocumentInkCapture("a", graph, surfaces).generated(graphLines)
    check(splitGraph.strokes().values.flatten().map { it.width }.distinct().size > 1)
    check(splitGraph.strokes().values.flatten().map { it.opacity }.distinct().size > 1)
    // Hidden notes are excluded by the caller; locked notes remain opaque, and writing is rejected.
    val locked = PageLayer(0, "Base", locked = true)
    val lockedCapture = DocumentInkCapture("a", stroke, listOf(frames[0].copy(notes = listOf(sticky), layers = listOf(locked))))
    lockedCapture.add(listOf(InkPoint(50f, 40f), InkPoint(50f, 80f)), false)
    check(lockedCapture.blocked)
    val eraser = DocumentInkCapture("a", stroke.copy(tool = Tool.ERASER), listOf(frames[0].copy(notes = listOf(sticky), layers = listOf(locked)), frames[1]))
    eraser.add(listOf(InkPoint(50f, 40f), InkPoint(50f, 150f)), false)
    check(eraser.spanning && !eraser.blocked && eraser.noteStrokes().isEmpty())
    check(eraser.strokes().containsKey("b"))

    val inkOnNote = Stroke(Tool.PEN, 0, 2f, listOf(InkPoint(30f, 15f)))
    val hiddenInk = inkOnNote.copy(points = listOf(InkPoint(50f, 75f)))
    val exposedInk = inkOnNote.copy(points = listOf(InkPoint(50f, 45f)))
    val inkPage = NotePage(id = "a", width = 100f, height = 100f, strokes = listOf(hiddenInk, exposedInk), texts = listOf(sticky.copy(stickyInk = listOf(inkOnNote))))
    val erasePath = DocumentInkCapture("a", stroke.copy(tool = Tool.ERASER), surfaces)
    erasePath.add(listOf(InkPoint(50f, 40f), InkPoint(50f, 150f)), false)
    val erased = DocumentToolEdits.content(inkPage, erasePath.strokes().getValue("a"), erasePath.noteStrokes().getValue("a"), DocumentToolEdits.Options(erasing = true))
    check(erased.strokes == listOf(hiddenInk)) // Opaque note protects the page ink beneath it.
    check(erased.texts.single().stickyInk.isEmpty())
    val mixedEdit = PageJournal.diff(inkPage.content(), erased)!!
    check(PageJournal.apply(erased, PageJournal.invert(mixedEdit, inkPage.content())) == inkPage.content())
    val restyled = DocumentToolEdits.restyle(inkPage, CanvasSelection(strokes = listOf(exposedInk), texts = inkPage.texts), 123, 2f, .5f, null)
    check(restyled.strokes.first() === hiddenInk)
    check(restyled.strokes.last().color == 123 && restyled.texts.single().stickyInk.single().color == 123)
    check(restyled.texts.single().stickyInk.single().width == 4f)
    val styleEdit = PageJournal.diff(inkPage.content(), restyled)!!
    check(PageJournal.apply(restyled, PageJournal.invert(styleEdit, inkPage.content())) == inkPage.content())
    // Incremental erase previews and a batch commit produce exactly the same content.
    val incrementalPath = DocumentInkCapture("a", stroke.copy(tool = Tool.ERASER), surfaces)
    val session = DocumentToolEdits.EraserSession()
    val options = DocumentToolEdits.Options(erasing = true)
    incrementalPath.add(listOf(InkPoint(50f, 40f), InkPoint(50f, 65f)), false)
    session.content(inkPage, incrementalPath.strokes()["a"].orEmpty(), incrementalPath.noteStrokes()["a"].orEmpty(), options)
    incrementalPath.add(listOf(InkPoint(50f, 150f)), false)
    val incremental = session.content(inkPage, incrementalPath.strokes()["a"].orEmpty(), incrementalPath.noteStrokes()["a"].orEmpty(), options)
    check(incremental == erased)
    check(session.content(inkPage, incrementalPath.strokes()["a"].orEmpty(), incrementalPath.noteStrokes()["a"].orEmpty(), options) == erased)
    // Local canvas capture preserves negative coordinates rather than clipping them to a finite page.
    val canvas = DocumentInkCapture("a", stroke, listOf(frames[0].copy(infinite = true, notes = listOf(sticky))))
    canvas.add(listOf(InkPoint(-30f, -50f), InkPoint(50f, 75f), InkPoint(50f, 180f)), false)
    close(canvas.strokes().getValue("a").first().points.first().x, -30f)
    close(canvas.strokes().getValue("a").first().points.first().y, -50f)
    check(canvas.noteStrokes().getValue("a").getValue("sticky").isNotEmpty())

    val group = DocumentHistoryStep("group", listOf("a", "b", "c"), 100)
    val ledger = DocumentHistory().push(group)
    val initial = frames.associate { it.frame.pageId to NotePage(id = it.frame.pageId) }
    val records = parts.mapValues { (id, strokes) ->
        val forward = PageEdit(StrokesEdit.Add(strokes), groupId = group.id, actionSequence = group.sequence)
        val inverse = PageJournal.invert(forward, initial.getValue(id).content())
        check(inverse.groupId == group.id && inverse.actionSequence == 100L)
        PageTransaction(1, 1, forward, undoPush = inverse, clearRedo = true)
    }
    val commit = DocumentCommit(group.id, ledger, records)
    check(DocumentCommitCodec.decode(DocumentCommitCodec.encode(commit)) == commit)
    val damaged = JSONObject(DocumentCommitCodec.encode(commit)).put("crc", 0).toString()
    check(runCatching { DocumentCommitCodec.decode(damaged) }.isFailure)
    records.values.forEach { tx ->
        check(PageJournal.decode(PageJournal.encode(tx)) == tx)
        check(JournalBinary.readAll(JournalBinary.frame(tx)) == listOf(tx))
    }
    // Fault before/after every member append, ledger checkpoint and completion; every retry is idempotent.
    for (fault in 0..10) {
        val disk = mutableMapOf<String, ByteArray>()
        var metadata = DocumentHistory(); var pending = true; var operation = 0
        var injected = false
        fun trip() { if (!injected && operation++ == fault) { injected = true; error("Power loss") } }
        fun replay() = DocumentCommitRecovery.replay(commit,
            applied = { id, tx -> JournalBinary.readAll(disk[id] ?: byteArrayOf()).any { it.seq >= tx.seq } },
            append = { id, tx -> trip(); disk[id] = (disk[id] ?: byteArrayOf()) + JournalBinary.frame(tx); trip() },
            checkpoint = { history -> trip(); metadata = history; trip() },
            complete = { trip(); pending = false; trip() })
        runCatching { replay() }
        injected = true // Later replays model recovery after this one interruption.
        replay(); replay()
        check(!pending && metadata == ledger)
        initial.forEach { (id, page) ->
            val transactions = JournalBinary.readAll(disk.getValue(id))
            check(transactions.size == 1)
            val content = PageJournal.replay(page.content(), 0, transactions)
            check(content.strokes == parts.getValue(id))
            val history = PageJournal.foldHistory(PageJournal.History.EMPTY, transactions, 0)
            val snapshotBytes = java.io.ByteArrayOutputStream().also { PageSnapshotBinary.write(it,
                page.copy(strokes = content.strokes, revision = 1), 1, history) }.toByteArray()
            check(snapshotBytes[4].toInt() == 3)
            val snapshot = PageSnapshotBinary.read(snapshotBytes)
            check(snapshot.history == history && snapshot.strokes == content.strokes)
            check(PageJournal.foldHistory(snapshot.history, transactions, snapshot.journalSeq) == history)
        }
    }
    // Grouped undo and redo preserve content, stack identities and membership through snapshots and archives.
    val after = records.mapValues { (id, tx) -> PageJournal.apply(initial.getValue(id).content(), tx.forward) }
    val undoRecords = records.mapValues { (id, tx) -> val inverse = tx.undoPush!!
        PageTransaction(2, 2, inverse, undoPop = true,
            redoPush = PageJournal.invert(inverse, after.getValue(id)).copy(actionSequence = 101)) }
    val undoneLedger = ledger.moved(group, true, 101).copy(lastCommit = "undo-operation")
    val undoCommit = DocumentCommit("undo-operation", undoneLedger, undoRecords)
    check(DocumentCommitCodec.decode(DocumentCommitCodec.encode(undoCommit)) == undoCommit)
    undoRecords.forEach { (id, tx) ->
        val log = listOf(records.getValue(id), tx)
        check(PageJournal.replay(initial.getValue(id).content(), 0, log) == initial.getValue(id).content())
        val history = PageJournal.foldHistory(PageJournal.History.EMPTY, log, 0)
        check(history.undo.isEmpty() && history.redo.single().groupId == group.id)
        val redoEdit = history.redo.single()
        check(PageJournal.apply(initial.getValue(id).content(), redoEdit) == after.getValue(id))
    }
    val note = Notebook(id = "note", title = "Grouped", pages = initial.values.toList(), documentHistory = ledger)
    check(NoteMetaCodec.versionOf(NoteMetaCodec.encode(note)) == 7)
    check(NoteMetaCodec.decode(NoteMetaCodec.encode(note)).documentHistory == ledger)
    check(NoteCodec.decode(NoteCodec.encode(note)).documentHistory == ledger)
    check(note.duplicatedAsCopy("Copy").documentHistory == DocumentHistory())
    // A real portable v3 ZIP preserves both the notebook ledger and every member's persisted stack.
    val temporary = java.nio.file.Files.createTempDirectory("folio-document-backup-").toFile()
    try {
        val sources = linkedMapOf<String, NativeBackup.Source>()
        val paths = linkedMapOf<String, String>()
        fun add(path: String, file: java.io.File) {
            val (hash, source) = NativeBackup.inspect(file, true); sources[hash] = source; paths[path] = hash
        }
        val committedNote = note.copy(pages = initial.map { (id, page) -> page.copy(strokes = after.getValue(id).strokes, revision = 1) })
        add("note.json", java.io.File(temporary, "note.json").apply { writeText(NoteMetaCodec.encode(committedNote)) })
        records.forEach { (id, tx) ->
            val page = committedNote.pages.first { it.id == id }
            val history = PageJournal.foldHistory(PageJournal.History.EMPTY, listOf(tx), 0)
            val snapshotFile = java.io.File(temporary, "$id.fps").apply { outputStream().use { PageSnapshotBinary.write(it, page, 1, history) } }
            add("pages/$id.fps", snapshotFile)
        }
        val manifest = NativeBackup.Manifest(emptyList(), listOf(NativeBackup.Note(note.id, paths)), sources.mapValues { it.value.info })
        val zipBytes = java.io.ByteArrayOutputStream().also { NativeBackup.writePortable(it, NativeBackup.Prepared(manifest, sources)) }.toByteArray()
        val extracted = java.io.File(temporary, "restored").apply { mkdirs() }
        val restored = java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
            check(zip.nextEntry.name == LibraryBackup.MANIFEST)
            val manifest = NativeBackup.decode(zip.readBytes().toString(Charsets.UTF_8)); zip.closeEntry()
            NativeBackup.readEntries(zip, manifest, extracted)
        }
        check(NoteMetaCodec.decode(restored.getValue(paths.getValue("note.json")).readText()).documentHistory == ledger)
        records.keys.forEach { id ->
            val page = PageSnapshotBinary.read(restored.getValue(paths.getValue("pages/$id.fps")).readBytes())
            check(page.history.undo.single().groupId == group.id)
            check(PageJournal.apply(PageContent(page.strokes, page.texts, page.images, page.layers), page.history.undo.single()) == initial.getValue(id).content())
        }
        val summaries = committedNote.pages.associate { it.id to NoteMetaCodec.pageSummary(it).toString() }
        val withSummaries = commit.copy(pageSummaries = summaries)
        check(DocumentCommitCodec.decode(DocumentCommitCodec.encode(withSummaries)) == withSummaries)
    } finally { temporary.deleteRecursively() }
    val plain = note.copy(documentHistory = DocumentHistory())
    check(NoteMetaCodec.versionOf(NoteMetaCodec.encode(plain)) == 6)
    val plainBytes = java.io.ByteArrayOutputStream().also { PageSnapshotBinary.write(it, initial.getValue("a"), 0, PageJournal.History.EMPTY) }.toByteArray()
    check(plainBytes[4].toInt() == 1)
    var bounded = ledger
    repeat(100) { bounded = bounded.push(DocumentHistoryStep("g$it", listOf("a"), 102L + it)) }
    check(bounded.undo.size == PageJournal.HISTORY_LIMIT)
    println("Document editing smoke passed: page/note split geometry, shapes/graphs, swept and incremental erasing, lasso mapping, WAL fault recovery, grouped undo/redo, snapshots and archive compatibility.")
}
