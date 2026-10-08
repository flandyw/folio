package com.folio.notes

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Wait for ML Kit before recycling the bitmap, even when the calling job was cancelled. */
internal suspend fun recognizePdfText(recognizer: TextRecognizer, bitmap: Bitmap): Text {
    val result = suspendCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }
    currentCoroutineContext().ensureActive()
    return result
}

/** Bounded, disposable front-matter OCR. Failure leaves embedded text and filenames usable. */
internal suspend fun scanPdfFrontMatter(renderer: PdfRenderer, embedded: List<PdfPageText>): List<PdfPageText> {
    val scanned = (0 until minOf(renderer.pageCount, 2)).filter { index ->
        (embedded.firstOrNull { it.pageIndex == index }?.text?.count(Char::isLetter) ?: 0) < 160
    }
    if (scanned.isEmpty()) return emptyList()
    val recognizer = try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { return emptyList() }
    val texts = mutableListOf<PdfPageText>()
    try {
        for (index in scanned) {
            currentCoroutineContext().ensureActive()
            try {
                renderer.openPage(index).use { page ->
                    val scale = 1800f / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        texts += PdfPageText(index, recognizePdfText(recognizer, bitmap).text
                            .take(ExamEvidenceCollector.MAX_PAGE_CHARACTERS))
                    } finally { bitmap.recycle() }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Continue to the next scanned page. */ }
        }
    } finally { recognizer.close() }
    return texts
}
