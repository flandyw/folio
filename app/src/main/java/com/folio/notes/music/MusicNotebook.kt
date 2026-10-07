package com.folio.notes.music

import com.folio.notes.InkPoint
import com.folio.notes.NotePage
import com.folio.notes.ShapeTools
import com.folio.notes.Stroke
import com.folio.notes.StrokeStyle
import com.folio.notes.TextBox
import com.folio.notes.Tool
import java.util.UUID

/**
 * How a score becomes a notebook. A score's PDF is imported as ordinary pages (840 units wide, the
 * same as any imported PDF) and its pencil marks are the editor's own strokes and text boxes, so the
 * reader is the real editor. Older Folio versions kept marks in the music index, in page fractions;
 * this carries them over once, on the score's first open.
 */
internal object MusicNotebook {
    /** The editor's width for an imported PDF page. */
    const val PAGE_WIDTH = 840f

    /** Music's stroke width and label size are in a 1000-unit page; the editor's page is 840. */
    private const val LEGACY_PAGE = 1000f

    fun toolFor(name: String): Tool = when (name) {
        MUSIC_HIGHLIGHTER -> Tool.HIGHLIGHTER
        MUSIC_PEN -> Tool.PEN
        else -> runCatching { Tool.valueOf(name) }.getOrNull()?.takeIf { it in ShapeTools } ?: Tool.PEN
    }

    /** True when the score still holds marks an older Folio drew, which the first open must carry over. */
    fun hasLegacyInk(score: MusicScore) = score.ink.isNotEmpty() || score.texts.isNotEmpty()

    fun stroke(source: MusicStroke, width: Float, height: Float): Stroke {
        val unit = width / LEGACY_PAGE
        return Stroke(toolFor(source.tool), source.color, source.width * unit,
            source.points.map { InkPoint(it.x * width, it.y * height) },
            opacity = source.opacity, style = StrokeStyle.safeValueOf(source.style))
    }

    fun text(source: MusicText, width: Float, height: Float): TextBox {
        val x = source.x * width
        val unit = width / LEGACY_PAGE
        return TextBox(x = x, y = source.y * height, width = (width - x - 16f).coerceIn(TextBox.MIN_WIDTH, TextBox.DEFAULT_WIDTH),
            text = source.text, size = source.size * unit, color = source.color)
    }

    /** [pages] with the score's older strokes and labels placed on the page they were drawn on. */
    fun carryOver(score: MusicScore, pages: List<NotePage>): List<NotePage> = pages.mapIndexed { index, page ->
        val strokes = score.ink.filter { it.page == index }
        val texts = score.texts.filter { it.page == index }
        if (strokes.isEmpty() && texts.isEmpty()) page
        else page.copy(
            strokes = strokes.map { stroke(it, page.width, page.height) },
            texts = texts.map { text(it, page.width, page.height) })
    }

    /** Fresh copies of the chosen source pages, re-pointed at the new PDF's page numbers. */
    fun copyPages(source: List<NotePage>, seed: MusicSeed, pageCount: Int): List<NotePage>? {
        if (seed.pages.size != pageCount || seed.pages.any { it !in source.indices }) return null
        return seed.pages.mapIndexed { newIndex, from ->
            source[from].copy(id = UUID.randomUUID().toString(), pdfIndex = newIndex, revision = 0)
        }
    }

    /** Pencil strokes plus typed labels on each page, for the shelf and the page grid. */
    fun counts(pages: List<NotePage>): List<Int> =
        pages.map { page -> page.strokes.size + page.texts.count { it.text.isNotBlank() && !it.isSticky } }
}
