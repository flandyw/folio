package com.folio.notes.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.folio.notes.ink.InkGeometry
import com.folio.notes.model.InkPoint
import com.folio.notes.model.NotePage
import com.folio.notes.model.PageImage

/** A picture picked for a page, re-encoded as JPEG with its longest side capped at [MAX_SIDE] pixels. */
internal class PickedImage(val bytes: ByteArray, val width: Int, val height: Int) {
    /** Where the picture lands on [target]: centred on [at] (else the page), and on a finite page inside the paper. */
    fun placement(target: NotePage, at: InkPoint? = null): PageImage {
        val maxWidth = if (target.infinite) 560f else (target.width - 96f).coerceIn(200f, 640f)
        val (w, h) = InkGeometry.fitImage(width.toFloat(), height.toFloat(), maxWidth)
        val x = (at?.x ?: if (target.infinite) 0f else target.width / 2f) - w / 2f
        val y = (at?.y ?: if (target.infinite) 0f else target.height / 2f) - h / 2f
        return PageImage(
            x = if (target.infinite) x else x.coerceIn(0f, (target.width - w).coerceAtLeast(0f)),
            y = if (target.infinite) y else y.coerceIn(0f, (target.height - h).coerceAtLeast(0f)),
            width = w, height = h
        )
    }

    companion object {
        const val MAX_SIDE = 2048

        /** Decodes [uri] off the main thread; throws with a readable message when it cannot. */
        suspend fun read(context: Context, uri: Uri): PickedImage = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val raw = input.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                var sample = 1
                while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_SIDE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                    ?: error("This picture could not be opened")
                val capped = if (max(decoded.width, decoded.height) > MAX_SIDE) {
                    val s = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
                    Bitmap.createScaledBitmap(decoded, (decoded.width * s).toInt().coerceAtLeast(1),
                        (decoded.height * s).toInt().coerceAtLeast(1), true).also { decoded.recycle() }
                } else decoded
                val out = ByteArrayOutputStream()
                check(capped.compress(Bitmap.CompressFormat.JPEG, 85, out)) { "This picture could not be saved" }
                val w = capped.width; val h = capped.height
                capped.recycle()
                PickedImage(out.toByteArray(), w, h)
            } ?: error("This picture could not be opened")
        }
    }
}
