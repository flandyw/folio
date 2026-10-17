package com.folio.notes

import android.os.Handler
import android.os.Looper
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Process-wide on-device classifiers. Ink capture, fitting and persistence remain independent. */
internal object InkGestureRecognizer {
    enum class Model { GESTURES, SHAPES }
    enum class Availability { NOT_STARTED, DOWNLOADING, READY, UNAVAILABLE }
    private class Classifier(identifier: DigitalInkRecognitionModelIdentifier) {
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        val client = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        val availability = MutableStateFlow(Availability.NOT_STARTED)
        var busy = false
    }
    private val gestures by lazy { Classifier(DigitalInkRecognitionModelIdentifier.EN_AU_GESTURE) }
    private val shapes by lazy { Classifier(DigitalInkRecognitionModelIdentifier.SHAPES) }
    private fun classifier(model: Model) = if (model == Model.GESTURES) gestures else shapes
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Folio ink recognition").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    fun availability(model: Model) = classifier(model).availability.asStateFlow()

    /** One download per model, shared across surfaces. Failed downloads can be retried in settings. */
    fun prepare(model: Model, retry: Boolean = false) {
        val c = classifier(model)
        synchronized(c) {
            if (c.availability.value != Availability.NOT_STARTED &&
                !(retry && c.availability.value == Availability.UNAVAILABLE)) return
            c.availability.value = Availability.DOWNLOADING
        }
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(c.model).addOnSuccessListener { downloaded ->
            if (downloaded) c.availability.value = Availability.READY
            else manager.download(c.model, DownloadConditions.Builder().build())
                .addOnSuccessListener { c.availability.value = Availability.READY }
                .addOnFailureListener { c.availability.value = Availability.UNAVAILABLE }
        }.addOnFailureListener { c.availability.value = Availability.UNAVAILABLE }
    }

    /** Always completes on main; missing/busy/failed models preserve the drawing. No score is a probability. */
    fun classify(model: Model, stroke: Stroke, result: (String?) -> Unit) {
        prepare(model)
        val c = classifier(model)
        synchronized(c) {
            if (c.availability.value != Availability.READY || c.busy) { main.post { result(null) }; return }
            c.busy = true
        }
        worker.execute {
            try {
                val points = Ink.Stroke.builder()
                stroke.points.forEach { points.addPoint(Ink.Point.create(it.x, it.y)) }
                val ink = Ink.builder().addStroke(points.build()).build()
                c.client.recognize(ink).addOnCompleteListener { task ->
                    synchronized(c) { c.busy = false }
                    result(if (task.isSuccessful) task.result.candidates.firstOrNull()?.text else null)
                }
            } catch (_: Exception) {
                synchronized(c) { c.busy = false }
                main.post { result(null) }
            }
        }
    }

    /** Keep Folio's additional families; use Google to choose among its four supported shapes. */
    fun shape(stroke: Stroke, options: ShapeAssistOptions, result: (Tidied?) -> Unit) {
        worker.execute {
            val extra = ShapeRecognition.recognise(stroke, options)?.takeIf { ShapeRecognition.modelLabel(it) == null }
            if (extra != null) main.post { result(extra) }
            else main.post {
                classify(Model.SHAPES, stroke) { label ->
                    if (label == null) result(null)
                    else worker.execute {
                        val fitted = ShapeRecognition.fitClassified(stroke, label, options)
                        main.post { result(fitted) }
                    }
                }
            }
        }
    }

    /** Geometry is a veto, never permission to delete without the scribble classifier. */
    fun scribble(stroke: Stroke, before: NotePage, paths: List<Stroke>, notes: Map<String, List<Stroke>>,
        sensitivity: Float, result: (PageContent?) -> Unit) {
        scribbleDocument(stroke, mapOf(before.id to before), mapOf(before.id to paths), mapOf(before.id to notes), sensitivity) {
            result(it[before.id])
        }
    }

    fun scribbleDocument(stroke: Stroke, before: Map<String, NotePage>, paths: Map<String, List<Stroke>>,
        notes: Map<String, Map<String, List<Stroke>>>, sensitivity: Float, result: (Map<String, PageContent>) -> Unit) {
        prepare(Model.GESTURES)
        if (gestures.availability.value != Availability.READY) { main.post { result(emptyMap()) }; return }
        worker.execute {
            val candidate = ScribbleRecognition.prepare(before, paths, notes, sensitivity)
            if (candidate == null) main.post { result(emptyMap()) }
            else main.post {
                classify(Model.GESTURES, stroke) { label -> result(candidate.confirmed(label)) }
            }
        }
    }
}
