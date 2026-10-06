package com.folio.notes

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.sin
import kotlin.system.measureNanoTime

private fun rejects(name: String, action: () -> Unit) {
    check(runCatching(action).isFailure) { "$name was accepted" }
}

private fun checkResponseMetadata() {
    val original = NotePage(id = "draft", texts = listOf(TextBox(text = "Explain the evidence", x = 10f, y = 20f)))
    val retry = NotePage(id = "retry")
    val first = ResponseAttempt(id = "first", title = "Draft", pageIds = listOf(original.id), plan = "A contention\nTwo arguments")
    val action = FeedbackAction(id = "action", pageId = original.id, text = "Explain the evidence",
        sourceTextId = original.texts.single().id, practiceAttemptId = "second")
    val second = ResponseAttempt(id = "second", title = "Rewrite", mode = ResponseMode.PARAGRAPH,
        pageIds = listOf(retry.id), parentId = first.id, sourceActionId = action.id, resultId = "mark")
    val note = Notebook(title = "Response", pages = listOf(original, retry),
        attempts = listOf(ExamAttempt(id = "mark", score = 16, total = 20)),
        longResponse = LongResponse("Evaluate this argument", "Justice", 20, 40, listOf(first, second)),
        feedbackActions = listOf(action))
    check(NoteCodec.decode(NoteCodec.encode(note)) == note)
    val index = NoteMetaCodec.decode(NoteMetaCodec.encode(note))
    check(index.longResponse == note.longResponse && index.feedbackActions == note.feedbackActions)
    val legacy = JSONObject(NoteMetaCodec.encode(note)).apply { remove("longResponse"); remove("feedbackActions") }
    val old = NoteMetaCodec.decode(legacy.toString())
    check(old.longResponse == null && old.feedbackActions.isEmpty())
    val continuation = NotePage(id = "continuation")
    val expanded = note.copy(pages = note.pages + continuation).linkResponsePage(continuation.id, original.id)
    check(expanded.longResponse!!.attemptFor(continuation.id)?.id == first.id)
    check(expanded.longResponse!!.attemptFor(retry.id)?.id == second.id)
    check(note.withMovedPage(0, 1).longResponse == note.longResponse)
    val copiedPage = note.withDuplicatedPage(0)
    check(copiedPage.longResponse!!.attemptFor(copiedPage.pages[1].id)?.id == first.id)
    val copy = note.duplicatedAsCopy("Copy")
    check(copy.pages.none { it.id in note.pages.map { page -> page.id } })
    check(copy.longResponse!!.attemptFor(copy.pages[0].id)?.id == first.id)
    check(copy.feedbackActions.single().pageId == copy.pages[0].id)
    check(copy.longResponse!!.attempts.all { it.resultId == null })
    check(copy.attempts.isEmpty())
    val deleted = note.withDeletedPage(0)
    check(deleted.feedbackActions.single().text == action.text)
    check(deleted.pages.none { it.id == deleted.feedbackActions.single().pageId })
    check(matchesQuery(note, "justice evaluate"))
    check(!matchesQuery(note, "nonexistent"))
    println("Response smoke: legacy defaults, portable/index round-trips, continuation, copying, reordering, missing sources and search passed.")
}

private fun checkStickyNotes() {
    val ink = Stroke(Tool.PEN, -16777216, 2f, listOf(InkPoint(10f, 12f, .5f), InkPoint(32f, 28f)))
    val inside = TextBox(id = "sticky-inside", x = 20f, y = 30f, width = 120f,
        text = "Remember this", stickyHeight = 90f, stickyInk = listOf(ink))
    val outside = inside.copy(id = "sticky-outside", x = -140f)
    val ordinary = TextBox(id = "ordinary", x = 4f, y = 5f, text = "Heading")
    val page = NotePage(id = "sticky-page", texts = listOf(inside, outside, ordinary))
    check(StickyNotes.exports(inside, page))
    check(!StickyNotes.exports(outside, page))
    check(!StickyNotes.exports(inside.copy(x = page.width - 10f), page))
    check(StickyNotes.exports(outside, page.copy(infinite = true)))
    check(StickyNotes.workspaceSide(listOf(page)) == 1f)
    check(StickyNotes.workspaceSide(listOf(page, page.copy(texts = listOf(outside.copy(x = -2f * page.width))))) > 2f)
    check(InkCodec.decodeTexts(InkCodec.encodeTexts(page.texts)) == page.texts)
    val legacyText = InkCodec.encodeTexts(listOf(ordinary)).getJSONObject(0)
    check(!legacyText.has("stickyHeight") && !legacyText.has("stickyInk"))
    val changed = page.copy(texts = listOf(inside.moved(-200f, 0f).copy(text = "Changed"), outside, ordinary))
    val before = PageContent(texts = page.texts)
    val edit = PageJournal.diff(before, PageContent(texts = changed.texts))!!
    val undo = PageJournal.invert(edit, before)
    check(PageJournal.apply(PageJournal.apply(before, edit), undo).texts == page.texts)
    val history = PageJournal.History(listOf(undo), listOf(edit))
    val bytes = ByteArrayOutputStream().also { PageSnapshotBinary.write(it, page, 11, history) }.toByteArray()
    val snapshot = PageSnapshotBinary.read(bytes)
    check(snapshot.texts == page.texts && snapshot.journalSeq == 11 && snapshot.history == history)
    check(NoteCodec.decode(NoteCodec.encode(Notebook(title = "Sticky notes", pages = listOf(page)))).pages.single().texts == page.texts)
    val scaled = InkGeometry.scaleTexts(listOf(inside), InkPoint(0f, 0f), 2f).single()
    check(scaled.stickyHeight == 180f && scaled.stickyInk.single().points.first().x == 20f)
    println("Sticky notes: export placement, workspace width, local ink, legacy text, snapshot/history and portable round trips passed")
}

fun main() = runBlocking {
    checkStickyNotes()
    checkResponseMetadata()
    val root = kotlin.io.path.createTempDirectory("folio-backup-check-").toFile()
    try {
        val strokes = (0 until 80).map { line -> Stroke(Tool.PEN, -16777216, 2.5f,
            (0 until 2000).map { n -> InkPoint(n * .371f + line, (sin(n * .032) * 12 + line * 8).toFloat()) }) }
        val page = NotePage(id = "page-1", strokes = strokes, revision = 12)
        val history = PageJournal.History(listOf(PageEdit(strokes = StrokesEdit.Remove(listOf(79)))),
            listOf(PageEdit(strokes = StrokesEdit.Add(listOf(strokes.first())))))
        val transaction = PageTransaction(8, 13, PageEdit(strokes = StrokesEdit.Add(listOf(strokes.first()))),
            undoPush = PageEdit(strokes = StrokesEdit.Remove(listOf(80))), clearRedo = true)
        val snapshotFile = File(root, "page.fps").also { file -> file.outputStream().use { PageSnapshotBinary.write(it, page, 7, history) } }
        // A journal includes an already folded transaction and an incomplete final frame.
        val frame = JournalBinary.frame(transaction)
        rejects("damaged source snapshot") {
            val damaged = File(root, "damaged.fps").apply { writeBytes(snapshotFile.readBytes().apply { this[30] = (this[30].toInt() xor 1).toByte() }) }
            NativeBackup.inspect(damaged, true)
        }
        rejects("empty source snapshot") { NativeBackup.inspect(File(root, "empty.fps").apply { writeBytes(byteArrayOf()) }, true) }
        val journalFile = File(root, "page.fjl").apply { writeBytes(JournalBinary.frame(transaction.copy(seq = 7)) + frame + frame.copyOf(5)) }
        val folder = Folder("folder-1", "Study")
        val note = Notebook(id = "note-1", title = "Handwriting", folderId = folder.id, pages = listOf(page), updated = 100,
            longResponse = LongResponse("Discuss the evidence", "English", 20, 40,
                listOf(ResponseAttempt(id = "response-1", title = "Draft", pageIds = listOf(page.id), plan = "Argument and evidence"))),
            feedbackActions = listOf(FeedbackAction(pageId = page.id, text = "Develop the argument")))
        val meta = File(root, "note.json").apply { writeText(NoteMetaCodec.encode(note)) }
        val pdf = File(root, "source.pdf").apply { writeBytes(ByteArray(256 * 1024).also { java.util.Random(42).nextBytes(it) }) }
        val sources = linkedMapOf<String, NativeBackup.Source>()
        fun source(file: File, compress: Boolean): String = NativeBackup.inspect(file, compress).let { (hash, source) -> sources.putIfAbsent(hash, source); hash }
        val paths = linkedMapOf("note.json" to source(meta, true), "pages/page-1.fps" to source(snapshotFile, true),
            "pages/page-1.fjl" to source(journalFile, true), "source.pdf" to source(pdf, false))
        val note2 = note.copy(id = "note-2", title = "Shared PDF")
        val meta2 = File(root, "note2.json").apply { writeText(NoteMetaCodec.encode(note2)) }
        val paths2 = paths + ("note.json" to source(meta2, true))
        val manifest = NativeBackup.Manifest(listOf(folder), listOf(NativeBackup.Note(note.id, paths), NativeBackup.Note(note2.id, paths2)),
            sources.mapValues { it.value.info }, created = 200)
        val prepared = NativeBackup.Prepared(manifest, sources)
        // The same selection rule runs before staging in both library backup paths.
        val exclusions = AppPrefs.backupExcludedNotebookIds(setOf(note2.id, "../escape", "", "a".repeat(65)))
        check(exclusions == setOf(note2.id))
        check(AppPrefs.backupExcludedNotebookIds(null).isEmpty())
        check(AppPrefs.notebooksForBackup(listOf(note, note2), emptySet()) == listOf(note, note2))
        check(AppPrefs.notebooksForBackup(listOf(note, note2), exclusions) == listOf(note))
        check(AppPrefs.notebooksForBackup(listOf(note2.copy(title = "Renamed textbook", folderId = null)), exclusions).isEmpty())
        check(AppPrefs.notebooksForBackup(listOf(note2), exclusions, includeExcluded = true) == listOf(note2))
        val selectedIds = AppPrefs.notebooksForBackup(listOf(note, note2), exclusions).map { it.id }.toSet()
        val selectedNotes = manifest.notes.filter { it.id in selectedIds }
        val selectedHashes = selectedNotes.flatMap { it.files.values }.toSet()
        val selectedSources = sources.filterKeys { it in selectedHashes }
        val selectedManifest = manifest.copy(notes = selectedNotes, objects = selectedSources.mapValues { it.value.info })
        val selectedZip = ByteArrayOutputStream().also { NativeBackup.writePortable(it, NativeBackup.Prepared(selectedManifest, selectedSources)) }.toByteArray()
        ZipInputStream(selectedZip.inputStream()).use { zip ->
            zip.nextEntry
            val info = NativeBackup.decode(zip.readBytes().toString(Charsets.UTF_8))
            check(info.notes.map { it.id } == listOf(note.id))
            check(paths2.getValue("note.json") !in info.objects) // No excluded notebook metadata/ink payload.
            check(paths.getValue("source.pdf") in info.objects) // Shared assets required by included notes remain.
            val selectedDirectory = File(root, "selected").apply { mkdirs() }
            check(NativeBackup.readEntries(zip, info, selectedDirectory).keys == selectedHashes)
        }
        check(AppPrefs.notebooksForBackup(listOf(note, note2), setOf(note.id, note2.id)).isEmpty())
        val emptyManifest = manifest.copy(notes = emptyList(), objects = emptyMap())
        check(NativeBackup.decode(NativeBackup.encode(emptyManifest)).notes.isEmpty())
        check(sources.size == 5) // Three shared objects, including the source PDF, have one payload each.
        check(NativeBackup.decode(NativeBackup.encode(manifest)) == manifest)
        check(LibraryBackup.parseManifest(NativeBackup.encode(manifest)).version == 3)
        check(manifest.contentKey() == manifest.copy(created = 500, notes = manifest.notes.reversed(),
            objects = manifest.objects.mapValues { it.value.copy(storedBytes = 99, compressed = !it.value.compressed) }).contentKey())
        val portable = ByteArrayOutputStream().also { NativeBackup.writePortable(it, prepared) }.toByteArray()
        val extracted = File(root, "extracted").apply { mkdirs() }
        val restored = ZipInputStream(portable.inputStream()).use { zip ->
            check(zip.nextEntry.name == LibraryBackup.MANIFEST)
            val decoded = NativeBackup.decode(zip.readBytes().toString(Charsets.UTF_8)); zip.closeEntry()
            NativeBackup.readEntries(zip, decoded, extracted)
        }
        check(restored.keys == sources.keys)
        val restoredNote = NoteMetaCodec.decode(restored.getValue(paths.getValue("note.json")).readText())
        check(restoredNote.longResponse == note.longResponse && restoredNote.feedbackActions == note.feedbackActions)
        restored.forEach { (hash, file) -> check(file.readBytes().contentEquals(sources.getValue(hash).file.readBytes())) }
        val snapshot = PageSnapshotBinary.read(restored.getValue(paths.getValue("pages/page-1.fps")).readBytes())
        val records = JournalBinary.readAll(restored.getValue(paths.getValue("pages/page-1.fjl")).readBytes())
        check(snapshot.history == history && snapshot.journalSeq == 7)
        val content = PageJournal.replay(PageContent(snapshot.strokes, snapshot.texts, snapshot.images), snapshot.journalSeq, records)
        check(content.strokes == strokes + strokes.first())
        check(PageJournal.foldHistory(snapshot.history, records, snapshot.journalSeq) == PageJournal.History(history.undo + transaction.undoPush!!, emptyList()))
        check(maxOf(page.revision, snapshot.revision, records.maxOf { it.revision }) == 13)
        val externalInfos = sources.mapValues { (hash, source) ->
            val encoded = ByteArrayOutputStream().also { NativeBackup.writeObject(source, it) }.toByteArray()
            val info = source.info.copy(storedBytes = encoded.size.toLong())
            NativeBackup.readObject(encoded.inputStream(), File(root, "external-$hash"), hash, info, encoded = true)
            check(File(root, "external-$hash").readBytes().contentEquals(source.file.readBytes()))
            rejects("wrong encoded length") {
                NativeBackup.readObject(encoded.inputStream(), File(root, "bad-size"), hash, info.copy(storedBytes = info.storedBytes - 1), encoded = true)
            }
            if (encoded.size > 100) rejects("corrupted external object") {
                val damaged = encoded.copyOf().apply { this[50] = (this[50].toInt() xor 1).toByte() }
                NativeBackup.readObject(damaged.inputStream(), File(root, "bad"), hash, info, encoded = true)
            }
            info
        }
        val external = manifest.copy(external = true, objects = externalInfos)
        val point = ByteArrayOutputStream().also { NativeBackup.writeRestorePoint(it, external) }.toByteArray()
        ZipInputStream(point.inputStream()).use { zip ->
            check(zip.nextEntry.name == LibraryBackup.MANIFEST)
            check(NativeBackup.decode(zip.readBytes().toString(Charsets.UTF_8)) == external)
            check(zip.nextEntry == null)
        }
        check(point.size < 4096 && point.size < portable.size / 10)
        rejects("path traversal") {
            val bad = manifest.notes.first().copy(files = paths + ("../escape" to paths.getValue("note.json")))
            NativeBackup.decode(NativeBackup.encode(manifest.copy(notes = listOf(bad))))
        }
        rejects("future backup") { LibraryBackup.parseManifest(JSONObject(NativeBackup.encode(manifest)).put("version", 99).toString()) }
        rejects("missing index") {
            val bad = manifest.notes.first().copy(files = paths - "note.json")
            NativeBackup.decode(NativeBackup.encode(manifest.copy(notes = listOf(bad))))
        }
        rejects("missing object declaration") { NativeBackup.decode(NativeBackup.encode(manifest.copy(objects = manifest.objects - paths.getValue("note.json")))) }
        rejects("unbounded expansion") { NativeBackup.decode(NativeBackup.encode(manifest.copy(objects = manifest.objects.mapValues { it.value.copy(size = Long.MAX_VALUE) }))) }
        rejects("object hash mismatch") { NativeBackup.readObject(byteArrayOf(1, 2, 3).inputStream(), File(root, "bad"), paths.getValue("note.json"), sources.getValue(paths.getValue("note.json")).info) }
        rejects("missing payload") {
            val zipBytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(LibraryBackup.MANIFEST)); zip.write(NativeBackup.encode(manifest).toByteArray()); zip.closeEntry()
            } }.toByteArray()
            ZipInputStream(zipBytes.inputStream()).use { zip -> zip.nextEntry; zip.readBytes(); NativeBackup.readEntries(zip, manifest, extracted) }
        }
        rejects("duplicate payload") {
            val hash = paths.getValue("note.json")
            val fake = (if (hash.first() == '0') "1" else "0") + hash.drop(1)
            val name = (NativeBackup.OBJECT_PREFIX + hash).toByteArray()
            val fakeName = (NativeBackup.OBJECT_PREFIX + fake).toByteArray()
            val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                sources.forEach { (key, source) ->
                    zip.putNextEntry(ZipEntry(NativeBackup.OBJECT_PREFIX + key)); zip.write(source.file.readBytes()); zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry(NativeBackup.OBJECT_PREFIX + fake)); zip.write(meta.readBytes()); zip.closeEntry()
            } }.toByteArray()
            // Build a valid duplicate-name ZIP even though ZipOutputStream refuses to write one.
            for (offset in 0..bytes.size - fakeName.size) {
                if (fakeName.indices.all { bytes[offset + it] == fakeName[it] }) name.copyInto(bytes, offset)
            }
            ZipInputStream(bytes.inputStream()).use { NativeBackup.readEntries(it, manifest, extracted) }
        }
        // The legacy reader/writers continue to accept both nested v1 and deduplicated JSON v2.
        val legacy = ByteArrayOutputStream().also { LibraryBackup.write(it, listOf(folder), listOf(note)) { n, out -> NotebookArchive.write(n, null, out) } }.toByteArray()
        ZipInputStream(legacy.inputStream()).use { zip ->
            zip.nextEntry; check(LibraryBackup.parseManifest(zip.readBytes().toString(Charsets.UTF_8)).version == 1)
            zip.nextEntry; check(NotebookArchive.read(zip.readBytes().inputStream()).note == note)
        }
        val jsonFile = File(root, "portable.json").apply { writeText(NoteCodec.encode(note)) }
        val v2Payload = LibraryBackup.NotebookPayload(note.id, jsonFile, null, emptyMap())
        val v2 = ByteArrayOutputStream().also { LibraryBackup.write(it, listOf(folder), listOf(v2Payload), emptyMap()) }.toByteArray()
        ZipInputStream(v2.inputStream()).use { zip ->
            zip.nextEntry; check(LibraryBackup.parseManifest(zip.readBytes().toString(Charsets.UTF_8)).version == 2)
            zip.nextEntry; check(NoteCodec.decode(zip.readBytes().toString(Charsets.UTF_8)) == note)
        }
        // Compare a single notebook, without asset bytes hiding the difference in ink cost.
        val inkPaths = paths - "source.pdf"
        val inkSources = sources.filterKeys { it in inkPaths.values }
        val inkManifest = manifest.copy(notes = listOf(NativeBackup.Note(note.id, inkPaths)), objects = inkSources.mapValues { it.value.info })
        val inkPrepared = NativeBackup.Prepared(inkManifest, inkSources)
        var nativeBytes = 0
        val nativeMs = measureNanoTime { repeat(3) {
            val hashed = inkSources.values.associate { NativeBackup.inspect(it.file, it.info.compressed) }
            val preparedAgain = inkPrepared.copy(sources = hashed)
            nativeBytes = ByteArrayOutputStream().also { NativeBackup.writePortable(it, preparedAgain) }.size()
        } } / 3_000_000.0
        val oldMs = measureNanoTime { repeat(3) {
            jsonFile.writeText(NoteCodec.encode(note))
            ByteArrayOutputStream().also { LibraryBackup.write(it, listOf(folder), listOf(v2Payload), emptyMap()) }
        } } / 3_000_000.0
        println("Backup smoke: exclusions and explicit-export override, binary contents, journal replay, undo/redo, deduplication, external objects, bounds, corruption and v1/v2 compatibility passed.")
        println("Synthetic 160,000-point handwriting: v2=${v2.size} bytes, v3=$nativeBytes bytes (includes undo/redo); encode/write v2=%.1f ms, v3=%.1f ms (JVM, not device).".format(oldMs, nativeMs))
        println("Two-notebook portable=${portable.size} bytes; incremental descriptor=${point.size} bytes; unique objects=${sources.size}.")
    } finally { root.deleteRecursively() }
}
