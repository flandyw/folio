package com.folio.notes

import android.graphics.Bitmap
import android.util.AtomicFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Disposable, derived cache only. No recognized response text is retained or added to the notebook. */
internal class MarkOcr(cacheDir: File, source: File) : AutoCloseable {
    private val recognizerDelegate = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val recognizer by recognizerDelegate
    private val directory = File(cacheDir, "mark-ocr-v1").apply { mkdirs() }
    private val sourceKey = "${source.absolutePath}:${source.length()}:${source.lastModified()}"

    suspend fun read(page: NotePage, render: () -> Bitmap): List<MarkZone> {
        val key = "$sourceKey:${page.pdfIndex}:${page.width}:${page.height}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cache = AtomicFile(File(directory, "$digest.json"))
        try {
            val data = JSONArray(cache.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
            return (0 until data.length()).map { index ->
                val z = data.getJSONArray(index)
                MarkZone(page.pdfIndex!!, z.getDouble(0).toFloat(), z.getDouble(1).toFloat(),
                    z.getDouble(2).toFloat(), z.getDouble(3).toFloat(), z.getInt(4))
            }
        } catch (_: Exception) { /* Cache miss or interrupted/corrupt cache: recognize again. */ }
        currentCoroutineContext().ensureActive()
        val bitmap = render()
        val zones = try {
            // ML Kit does not cancel an in-flight inference. Wait for completion before recycling its
            // input or closing the recognizer, then observe cancellation before doing any more work.
            val result = suspendCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            currentCoroutineContext().ensureActive()
            result.textBlocks.flatMap { it.lines }.flatMap { line ->
                val text = StringBuilder()
                val ranges = line.elements.map { element ->
                    if (text.isNotEmpty()) text.append(' ')
                    val start = text.length
                    text.append(element.text)
                    Triple(start, text.length, element.boundingBox)
                }
                MarkZones.find(text.toString()).mapNotNull { match ->
                    val boxes = ranges.filter { it.first < match.end && it.second > match.start }
                        .mapNotNull { it.third }
                    if (boxes.isEmpty()) return@mapNotNull null
                    val left = boxes.minOf { it.left }.coerceIn(0, bitmap.width)
                    val top = boxes.minOf { it.top }.coerceIn(0, bitmap.height)
                    val right = boxes.maxOf { it.right }.coerceIn(0, bitmap.width)
                    val bottom = boxes.maxOf { it.bottom }.coerceIn(0, bitmap.height)
                    if (right <= left || bottom <= top) return@mapNotNull null
                    MarkZone(page.pdfIndex!!, left * page.width / bitmap.width,
                        top * page.height / bitmap.height, (right - left) * page.width / bitmap.width,
                        (bottom - top) * page.height / bitmap.height, match.marks)
                }
            }.distinct()
        } finally { bitmap.recycle() }
        val data = JSONArray().apply {
            zones.forEach { z -> put(JSONArray(listOf(z.x, z.y, z.width, z.height, z.marks))) }
        }
        // Cache failure must not hide successfully recognized labels. Bound disposable disk usage.
        try {
            directory.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                files.take((files.size - 511).coerceAtLeast(0)).forEach { it.delete() }
            }
            val stream = cache.startWrite()
            try {
                stream.write(data.toString().toByteArray())
                cache.finishWrite(stream)
            } catch (failure: Exception) { cache.failWrite(stream); throw failure }
        } catch (_: Exception) { }
        return zones
    }

    override fun close() { if (recognizerDelegate.isInitialized()) recognizer.close() }
}
