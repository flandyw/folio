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
    println("Document editing smoke passed: native split geometry/pressure/re-entry, lasso mapping, WAL fault recovery, grouped undo/redo, snapshots and archive compatibility.")
}
