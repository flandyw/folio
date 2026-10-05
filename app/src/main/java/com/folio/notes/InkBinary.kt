package com.folio.notes

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** A record that is truncated or damaged. Readers treat it as the end of what can be trusted. */
class BinaryFormatException(message: String) : Exception(message)

/**
 * A stroke's samples as one flat `x, y, pressure` float array. A decoded page used to hold a
 * three-field object per sample, which is several times the heap of the numbers themselves and
 * thousands of objects for the collector to mark on every pass; this holds a stroke in a single
 * array. It is still a `List<InkPoint>`, so everything that reads points is unchanged — an element
 * is built when it is asked for and is immutable like any other [InkPoint].
 */
class PackedPoints(private val xyp: FloatArray) : AbstractList<InkPoint>(), RandomAccess {
    override val size: Int get() = xyp.size / 3
    override fun get(index: Int): InkPoint {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("Index $index, size $size")
        val at = index * 3
        return InkPoint(xyp[at], xyp[at + 1], xyp[at + 2])
    }
    internal val raw: FloatArray get() = xyp
}

/** Little-endian writer with a running checksum; callers wrap the stream in a buffer. */
internal class BinWriter(private val out: OutputStream) {
    private val crc = CRC32()
    private val word = ByteArray(4)
    private var chunk = ByteArray(0)

    val checksum: Int get() = crc.value.toInt()

    fun u8(value: Int) { out.write(value); crc.update(value) }
    fun bytes(source: ByteArray, offset: Int = 0, length: Int = source.size) {
        out.write(source, offset, length); crc.update(source, offset, length)
    }
    fun varint(value: Int) {
        require(value >= 0) { "negative varint" }
        var rest = value
        while (rest >= 0x80) { u8((rest and 0x7F) or 0x80); rest = rest ushr 7 }
        u8(rest)
    }
    fun i32(value: Int) {
        word[0] = value.toByte(); word[1] = (value ushr 8).toByte()
        word[2] = (value ushr 16).toByte(); word[3] = (value ushr 24).toByte()
        bytes(word, 0, 4)
    }
    fun f32(value: Float) = i32(java.lang.Float.floatToRawIntBits(value))
    fun string(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        varint(encoded.size); bytes(encoded)
    }
    /** The first [count] floats of [values], converted in bulk rather than one call per float. */
    fun floats(values: FloatArray, count: Int = values.size) {
        if (chunk.isEmpty()) chunk = ByteArray(FLOAT_CHUNK * 4)
        val view = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var done = 0
        while (done < count) {
            val n = minOf(FLOAT_CHUNK, count - done)
            view.clear(); view.put(values, done, n)
            bytes(chunk, 0, n * 4)
            done += n
        }
    }
    private companion object { const val FLOAT_CHUNK = 4096 }
}

/** Bounds-checked little-endian reader; every shortfall is a [BinaryFormatException], never a crash. */
internal class BinReader(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN)
    val remaining: Int get() = buf.remaining()

    private fun need(count: Int) { if (count < 0 || count > buf.remaining()) throw BinaryFormatException("truncated") }
    fun u8(): Int { need(1); return buf.get().toInt() and 0xFF }
    fun varint(): Int {
        var shift = 0
        var result = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F) shl shift)
            if (b < 0x80) { if (result < 0) throw BinaryFormatException("varint"); return result }
            shift += 7
            if (shift > 28) throw BinaryFormatException("varint")
        }
    }
    /** An element count, rejected when the bytes left could not possibly hold that many. */
    fun count(minBytesEach: Int): Int {
        val n = varint()
        if (n.toLong() * minBytesEach > remaining) throw BinaryFormatException("count")
        return n
    }
    fun i32(): Int { need(4); return buf.getInt() }
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun string(): String {
        val n = count(1)
        val encoded = ByteArray(n); buf.get(encoded)
        return String(encoded, Charsets.UTF_8)
    }
    fun floats(n: Int): FloatArray {
        need(n * 4)
        val out = FloatArray(n)
        buf.asFloatBuffer().get(out)
        buf.position(buf.position() + n * 4)
        return out
    }
}

/**
 * The compact binary shape of ink, shared by the page snapshot and the journal. Samples are raw
 * float32, so a round trip is exact, and a stroke whose pressure never leaves 1 (shapes, most
 * highlighter and finger ink) leaves the pressure channel out altogether. Typed text and pictures
 * are small, so they ride along as the same JSON the portable archive carries.
 */
internal object InkBinary {
    private val tools = Tool.entries.associateBy { it.name }
    private const val HAS_STYLE = 1
    private const val HAS_OPACITY = 2
    private const val UNIFORM_PRESSURE = 4
    private const val HAS_LAYER = 8

    private fun defaultOpacity(tool: Tool) = if (tool == Tool.HIGHLIGHTER) 72f / 255f else 1f

    private fun uniformPressure(points: List<InkPoint>): Boolean {
        if (points is PackedPoints) {
            val raw = points.raw
            var i = 2
            while (i < raw.size) { if (raw[i] != 1f) return false; i += 3 }
            return true
        }
        for (p in points) if (p.pressure != 1f) return false
        return true
    }

    fun writeStroke(w: BinWriter, stroke: Stroke) {
        val points = stroke.points
        val uniform = uniformPressure(points)
        var flags = 0
        if (stroke.style != StrokeStyle.SOLID) flags = flags or HAS_STYLE
        if (stroke.opacity != defaultOpacity(stroke.tool)) flags = flags or HAS_OPACITY
        if (uniform) flags = flags or UNIFORM_PRESSURE
        if (stroke.layer != 0) flags = flags or HAS_LAYER
        w.u8(flags)
        w.string(stroke.tool.name)
        w.i32(stroke.color); w.f32(stroke.width)
        if (flags and HAS_OPACITY != 0) w.f32(stroke.opacity)
        if (flags and HAS_STYLE != 0) w.string(stroke.style.name)
        // Only a stroke on a non-base layer carries this, so a page that never used layers is
        // byte-identical to what an older build wrote.
        if (flags and HAS_LAYER != 0) w.varint(stroke.layer)
        w.varint(points.size)
        if (uniform) {
            val xy = FloatArray(points.size * 2)
            if (points is PackedPoints) {
                val raw = points.raw
                for (i in 0 until points.size) { xy[i * 2] = raw[i * 3]; xy[i * 2 + 1] = raw[i * 3 + 1] }
            } else {
                var at = 0
                for (p in points) { xy[at++] = p.x; xy[at++] = p.y }
            }
            w.floats(xy)
        } else if (points is PackedPoints) {
            w.floats(points.raw)
        } else {
            val xyp = FloatArray(points.size * 3)
            var at = 0
            for (p in points) { xyp[at++] = p.x; xyp[at++] = p.y; xyp[at++] = p.pressure }
            w.floats(xyp)
        }
    }

    fun readStroke(r: BinReader): Stroke {
        val flags = r.u8()
        val tool = tools[r.string()] ?: Tool.PEN
        val color = r.i32(); val width = r.f32()
        val opacity = if (flags and HAS_OPACITY != 0) r.f32() else defaultOpacity(tool)
        val style = if (flags and HAS_STYLE != 0) StrokeStyle.safeValueOf(r.string()) else StrokeStyle.SOLID
        val layer = if (flags and HAS_LAYER != 0) r.varint() else 0
        val uniform = flags and UNIFORM_PRESSURE != 0
        val n = r.count(if (uniform) 8 else 12)
        val raw = if (uniform) {
            val xy = r.floats(n * 2)
            FloatArray(n * 3).also { out ->
                for (i in 0 until n) { out[i * 3] = xy[i * 2]; out[i * 3 + 1] = xy[i * 2 + 1]; out[i * 3 + 2] = 1f }
            }
        } else r.floats(n * 3)
        return Stroke(tool, color, width, PackedPoints(raw), opacity, style, layer)
    }

    fun writeStrokes(w: BinWriter, strokes: List<Stroke>) {
        w.varint(strokes.size)
        for (stroke in strokes) writeStroke(w, stroke)
    }

    fun readStrokes(r: BinReader): List<Stroke> {
        val n = r.count(MIN_STROKE_BYTES)
        val out = ArrayList<Stroke>(n)
        for (i in 0 until n) out += readStroke(r)
        return out
    }

    private fun writeIndexed(w: BinWriter, strokes: List<IndexedStroke>) {
        w.varint(strokes.size)
        for (item in strokes) { w.varint(item.index); writeStroke(w, item.stroke) }
    }

    private fun readIndexed(r: BinReader): List<IndexedStroke> {
        val n = r.count(1 + MIN_STROKE_BYTES)
        val out = ArrayList<IndexedStroke>(n)
        for (i in 0 until n) out += IndexedStroke(r.varint(), readStroke(r))
        return out
    }

    private fun writeIndices(w: BinWriter, indices: List<Int>) {
        w.varint(indices.size)
        for (index in indices) w.varint(index)
    }

    private fun readIndices(r: BinReader): List<Int> {
        val n = r.count(1)
        val out = ArrayList<Int>(n)
        for (i in 0 until n) out += r.varint()
        return out
    }

    fun writeTexts(w: BinWriter, texts: List<TextBox>) = w.string(InkCodec.encodeTexts(texts).toString())
    fun readTexts(r: BinReader): List<TextBox> = InkCodec.decodeTexts(org.json.JSONArray(r.string()))
    fun writeImages(w: BinWriter, images: List<PageImage>) = w.string(InkCodec.encodeImages(images).toString())
    fun readImages(r: BinReader): List<PageImage> = InkCodec.decodeImages(org.json.JSONArray(r.string()))
    fun writeLayers(w: BinWriter, layers: List<PageLayer>) = w.string(InkCodec.encodeLayers(layers).toString())
    fun readLayers(r: BinReader): List<PageLayer> = InkCodec.decodeLayers(org.json.JSONArray(r.string()))

    private const val ADD = 1
    private const val REMOVE = 2
    private const val INSERT = 3
    private const val REPLACE = 4
    private const val REWRITE = 5
    private const val SET = 6
    private const val EDIT_STROKES = 1
    private const val EDIT_TEXTS = 2
    private const val EDIT_IMAGES = 4
    private const val EDIT_LAYERS = 8

    fun writeEdit(w: BinWriter, edit: PageEdit) {
        var flags = 0
        if (edit.strokes != null) flags = flags or EDIT_STROKES
        if (edit.texts != null) flags = flags or EDIT_TEXTS
        if (edit.images != null) flags = flags or EDIT_IMAGES
        if (edit.layers != null) flags = flags or EDIT_LAYERS
        w.u8(flags)
        when (val strokes = edit.strokes) {
            null -> Unit
            is StrokesEdit.Add -> { w.u8(ADD); writeStrokes(w, strokes.strokes) }
            is StrokesEdit.Remove -> { w.u8(REMOVE); writeIndices(w, strokes.indices) }
            is StrokesEdit.Insert -> { w.u8(INSERT); writeIndexed(w, strokes.strokes) }
            is StrokesEdit.Replace -> { w.u8(REPLACE); writeIndexed(w, strokes.strokes) }
            is StrokesEdit.Rewrite -> { w.u8(REWRITE); writeIndices(w, strokes.removed); writeIndexed(w, strokes.placed) }
            is StrokesEdit.Set -> { w.u8(SET); writeStrokes(w, strokes.strokes) }
        }
        edit.texts?.let { writeTexts(w, it) }
        edit.images?.let { writeImages(w, it) }
        edit.layers?.let { writeLayers(w, it) }
    }

    fun readEdit(r: BinReader): PageEdit {
        val flags = r.u8()
        val strokes = if (flags and EDIT_STROKES == 0) null else when (r.u8()) {
            ADD -> StrokesEdit.Add(readStrokes(r))
            REMOVE -> StrokesEdit.Remove(readIndices(r))
            INSERT -> StrokesEdit.Insert(readIndexed(r))
            REPLACE -> StrokesEdit.Replace(readIndexed(r))
            REWRITE -> { val removed = readIndices(r); StrokesEdit.Rewrite(removed, readIndexed(r)) }
            SET -> StrokesEdit.Set(readStrokes(r))
            else -> throw BinaryFormatException("edit kind")
        }
        val texts = if (flags and EDIT_TEXTS != 0) readTexts(r) else null
        val images = if (flags and EDIT_IMAGES != 0) readImages(r) else null
        val layers = if (flags and EDIT_LAYERS != 0) readLayers(r) else null
        return PageEdit(strokes, texts, images, layers)
    }

    /** Flags + tool length byte + colour + width + count: the least a stroke can occupy. */
    private const val MIN_STROKE_BYTES = 12
}

/**
 * One page's compacted snapshot, `pages/<id>.fps`:
 *
 * ```
 * "FOLP"  version  journalSeq  revision  strokes  texts  images  undo-edits  redo-edits  crc32
 * ```
 *
 * The sequence and revision come first so the journal can learn where it stands from a few bytes
 * instead of reading the page. The trailing checksum covers everything before it.
 */
internal object PageSnapshotBinary {
    /**
     * Version 1 is the original layout. Version 2 adds the page's layer list after the pictures and
     * is only written for a page that has layers, so every other page stays a version-1 file.
     */
    const val VERSION = 2
    private const val BASE_VERSION = 1
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'O'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte())
    /** Enough of the head to read the magic, version, sequence and revision (two varints of 5 bytes). */
    const val HEAD_BYTES = 16

    class Snapshot(
        val strokes: List<Stroke>, val texts: List<TextBox>, val images: List<PageImage>,
        val layers: List<PageLayer>,
        val journalSeq: Int, val revision: Int, val history: PageJournal.History
    )

    fun write(out: OutputStream, page: NotePage, journalSeq: Int, history: PageJournal.History) {
        val w = BinWriter(out)
        w.bytes(MAGIC); w.u8(if (page.layers.isEmpty()) BASE_VERSION else VERSION)
        w.varint(journalSeq); w.varint(page.revision.coerceAtLeast(0))
        InkBinary.writeStrokes(w, page.strokes)
        InkBinary.writeTexts(w, page.texts)
        InkBinary.writeImages(w, page.images)
        if (page.layers.isNotEmpty()) InkBinary.writeLayers(w, page.layers)
        w.varint(history.undo.size); history.undo.forEach { InkBinary.writeEdit(w, it) }
        w.varint(history.redo.size); history.redo.forEach { InkBinary.writeEdit(w, it) }
        // Not routed through the checksummed path: the checksum cannot cover itself.
        val sum = w.checksum
        out.write(byteArrayOf(sum.toByte(), (sum ushr 8).toByte(), (sum ushr 16).toByte(), (sum ushr 24).toByte()))
    }

    fun isSnapshot(bytes: ByteArray) =
        bytes.size >= MAGIC.size + 1 && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun read(bytes: ByteArray): Snapshot {
        if (!isSnapshot(bytes) || bytes.size < MAGIC.size + 1 + 4) throw BinaryFormatException("not a page snapshot")
        val body = bytes.size - 4
        val crc = CRC32().also { it.update(bytes, 0, body) }
        val stored = (bytes[body].toInt() and 0xFF) or ((bytes[body + 1].toInt() and 0xFF) shl 8) or
            ((bytes[body + 2].toInt() and 0xFF) shl 16) or ((bytes[body + 3].toInt() and 0xFF) shl 24)
        if (crc.value.toInt() != stored) throw BinaryFormatException("checksum")
        val r = BinReader(bytes, MAGIC.size, body - MAGIC.size)
        val version = r.u8()
        if (version != BASE_VERSION && version != VERSION) throw BinaryFormatException("unsupported page version")
        val seq = r.varint(); val revision = r.varint()
        val strokes = InkBinary.readStrokes(r)
        val texts = InkBinary.readTexts(r)
        val images = InkBinary.readImages(r)
        val layers = if (version >= VERSION) InkBinary.readLayers(r) else emptyList()
        val undo = ArrayList<PageEdit>(); repeat(r.count(1)) { undo += InkBinary.readEdit(r) }
        val redo = ArrayList<PageEdit>(); repeat(r.count(1)) { redo += InkBinary.readEdit(r) }
        return Snapshot(strokes, texts, images, layers, seq, revision, PageJournal.History(undo, redo))
    }

    /** The journal sequence and revision from the first bytes of a snapshot, or null if they are not one. */
    fun peek(head: ByteArray, length: Int): Pair<Int, Int>? = try {
        if (!isSnapshot(head)) null else {
            val r = BinReader(head, MAGIC.size, length - MAGIC.size)
            val version = r.u8()
            if (version != BASE_VERSION && version != VERSION) null else r.varint() to r.varint()
        }
    } catch (_: BinaryFormatException) { null }
}

/**
 * The binary journal, `pages/<id>.fjl`: one framed record per page transaction.
 *
 * ```
 * length:i32  crc32(payload):i32  payload = version seq revision flags forward [undoPush] [redoPush]
 * ```
 *
 * A frame is only believed when its whole payload is present and its checksum matches, which is
 * what makes a torn final append detectable without parsing: everything before it is intact, and
 * the tail is discarded exactly as a half-written JSONL line used to be.
 */
internal object JournalBinary {
    const val VERSION = 1
    private const val UNDO_POP = 1
    private const val REDO_POP = 2
    private const val CLEAR_REDO = 4
    private const val HAS_UNDO = 8
    private const val HAS_REDO = 16
    private const val MAX_RECORD_BYTES = 512 * 1024 * 1024

    /** One transaction as a ready-to-append frame. */
    fun frame(transaction: PageTransaction): ByteArray {
        val payload = ByteArrayOutputStream()
        val w = BinWriter(payload)
        w.u8(VERSION)
        w.varint(transaction.seq.coerceAtLeast(0)); w.varint(transaction.revision.coerceAtLeast(0))
        var flags = 0
        if (transaction.undoPop) flags = flags or UNDO_POP
        if (transaction.redoPop) flags = flags or REDO_POP
        if (transaction.clearRedo) flags = flags or CLEAR_REDO
        if (transaction.undoPush != null) flags = flags or HAS_UNDO
        if (transaction.redoPush != null) flags = flags or HAS_REDO
        w.u8(flags)
        InkBinary.writeEdit(w, transaction.forward)
        transaction.undoPush?.let { InkBinary.writeEdit(w, it) }
        transaction.redoPush?.let { InkBinary.writeEdit(w, it) }
        val body = payload.toByteArray()
        val out = ByteArrayOutputStream(body.size + 8)
        val header = BinWriter(out)
        header.i32(body.size)
        header.i32(CRC32().also { it.update(body) }.value.toInt())
        out.write(body)
        return out.toByteArray()
    }

    private fun decode(payload: ByteArray, offset: Int, length: Int): PageTransaction {
        val r = BinReader(payload, offset, length)
        if (r.u8() != VERSION) throw BinaryFormatException("unsupported journal version")
        val seq = r.varint(); val revision = r.varint()
        val flags = r.u8()
        val forward = InkBinary.readEdit(r)
        val undo = if (flags and HAS_UNDO != 0) InkBinary.readEdit(r) else null
        val redo = if (flags and HAS_REDO != 0) InkBinary.readEdit(r) else null
        return PageTransaction(seq, revision, forward, undo, flags and UNDO_POP != 0, redo,
            flags and REDO_POP != 0, flags and CLEAR_REDO != 0)
    }

    private fun int(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /** Walks complete, checksummed frames from the front; [visit] gets each payload's offset and length. */
    private inline fun scan(bytes: ByteArray, visit: (Int, Int) -> Boolean): Int {
        var at = 0
        var end = 0
        while (bytes.size - at >= 8) {
            val length = int(bytes, at)
            if (length <= 0 || length > MAX_RECORD_BYTES || length > bytes.size - at - 8) break
            val crc = CRC32().also { it.update(bytes, at + 8, length) }
            if (crc.value.toInt() != int(bytes, at + 4)) break
            if (!visit(at + 8, length)) break
            at += 8 + length
            end = at
        }
        return end
    }

    /** The byte length of the longest prefix made only of complete, intact frames. */
    fun completePrefixLength(bytes: ByteArray): Int = scan(bytes) { _, _ -> true }

    /** Every record before the first damaged or torn frame, in order. */
    fun readAll(bytes: ByteArray): List<PageTransaction> {
        val out = ArrayList<PageTransaction>()
        scan(bytes) { offset, length ->
            val record = try { decode(bytes, offset, length) } catch (_: Exception) { null }
            if (record != null) out += record
            record != null
        }
        return out
    }
}
