package com.folio.notes.storage

import android.content.Context
import android.util.AtomicFile
import com.folio.notes.ink.recognition.InkToText
import com.folio.notes.model.NotePage
import com.folio.notes.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Disposable, revision-keyed OCR cache. Notebook source and storage versions are untouched. */
internal object NotebookSearchIndex {
    suspend fun handwriting(context: Context, noteId: String, page: NotePage): String = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "handwriting-index/$noteId").apply { mkdirs() }
        val file = AtomicFile(File(directory, "${page.id}.json"))
        runCatching { JSONObject(file.openRead().bufferedReader().use { it.readText() }) }.getOrNull()?.let { cached ->
            if (cached.optInt("revision", -1) == page.revision) return@withContext cached.optString("text")
        }
        val text = buildList {
            InkToText.recognise(page.strokes.filter { it.tool != Tool.HIGHLIGHTER })?.text?.let(::add)
            page.texts.filter { it.stickyInk.isNotEmpty() }.forEach { box -> InkToText.recognise(box.stickyInk)?.text?.let(::add) }
        }.joinToString("\n")
        val stream = file.startWrite()
        try { stream.write(JSONObject().put("revision", page.revision).put("text", text).toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        text
    }
}
