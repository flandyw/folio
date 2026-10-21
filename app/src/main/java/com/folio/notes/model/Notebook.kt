package com.folio.notes.model

import com.folio.notes.document.DocumentHistory
import com.folio.notes.exam.ExamAttempt
import com.folio.notes.exam.ExamTags
import com.folio.notes.marking.FeedbackAction
import com.folio.notes.marking.LongResponse
import com.folio.notes.marking.linkResponsePage
import com.folio.notes.mistakes.LocalMistakeReviewAttempt
import java.util.UUID
import kotlin.math.*

/** One page: paper, size, ink, text and pictures. With [loaded] false only its summary is in memory (see [asSummary]). */
data class NotePage(
    val id: String = UUID.randomUUID().toString(),
    val width: Float = 840f, val height: Float = 1188f,
    val paper: Paper = Paper.DOTS, val pdfIndex: Int? = null,
    val strokes: List<Stroke> = emptyList(), val texts: List<TextBox> = emptyList(),
    val images: List<PageImage> = emptyList(),
    /** Bumped on every change to this page, so a cached preview can tell a stale copy from a fresh one. */
    val revision: Int = 0,
    /** False while only this page's summary is in memory; its ink and text are still on disk. */
    val loaded: Boolean = true,
    /** Queued for the redo list — a question worth another attempt before the exam. */
    val redoFlag: Boolean = false,
    val infinite: Boolean = false,
    val title: String = "",
    val bookmarked: Boolean = false,
    /**
     * The page's layers, bottom first. Empty means the single implicit base layer, so a page that
     * never used layers stores and loads exactly as it always did. Part of the page's content (not the
     * index): layer edits travel through the same journal and undo stacks as ink.
     */
    val layers: List<PageLayer> = emptyList()
)
data class Notebook(
    val id: String = UUID.randomUUID().toString(), val title: String,
    val folderId: String? = null, val cover: Int = 0, val starred: Boolean = false,
    val updated: Long = System.currentTimeMillis(), val pages: List<NotePage> = listOf(NotePage()),
    /** Exam metadata: subject, year, company and so on, carried as [ExamTags]. */
    val exam: ExamTags = ExamTags(),
    /** Marked attempts with scores and time taken, newest last. */
    val attempts: List<ExamAttempt> = emptyList(),
    /**
     * True shows the first page itself as the shelf cover, with no book decoration; false keeps
     * the decorative default cover. A fresh notebook has no drawn preview yet, so it shows the
     * default cover either way until its first page has been rendered.
     */
    val pageCover: Boolean = true,
    val mistakePractice: Boolean = false,
    /**
     * The Music score this notebook carries the pencil marks of. Such a notebook is the score's PDF
     * as ordinary pages, opened in the real editor, and never appears in the Library, Study, pickers
     * or backups. Optional in `note.json`, so an older app simply ignores it.
     */
    val musicScoreId: String? = null,
    val mistakeReviews: List<LocalMistakeReviewAttempt> = emptyList(),
    /** The view kept for peeking; stored with the notebook, it can frame any of its pages. */
    val peekAnchor: PeekAnchor? = null,
    /** Null preserves older notebooks’ current-page inheritance. */
    val defaultPaper: Paper? = null,
    val longResponse: LongResponse? = null,
    val feedbackActions: List<FeedbackAction> = emptyList(),
    /** User labels, independent of structured exam metadata. */
    val tags: List<String> = emptyList(),
    val documentHistory: DocumentHistory = DocumentHistory(),
    /** Independent shelf pins for All notebooks and the notebook’s containing folder. */
    val pinned: Boolean = false,
    val pinnedInFolder: Boolean = false
) {
    /** The share of the best attempt's score, 0..1, or null while nothing has been marked. */
    val bestScore: Float? get() = attempts.mapNotNull { it.share }.maxOrNull()
}
/** [color] is an index into `FolderPalette`; 0 is the default look, so older libraries need no migration. */
data class Folder(val id: String = UUID.randomUUID().toString(), val name: String, val parentId: String? = null, val color: Int = 0)

/** Appends an attempt, or replaces one with the same id where it sits, keeping the history in order. */
fun Notebook.withAttempt(attempt: ExamAttempt): Notebook {
    val index = attempts.indexOfFirst { it.id == attempt.id }
    if (index < 0) return copy(attempts = attempts + attempt)
    return copy(attempts = attempts.toMutableList().apply { set(index, attempt) })
}

/** A new page identity keeps copied ink and PDF backgrounds independent in undo history. */
fun Notebook.withDuplicatedPage(index: Int): Notebook {
    if (index !in pages.indices) return this
    val duplicate = pages[index].copy(id = UUID.randomUUID().toString())
    return copy(pages = pages.toMutableList().apply { add(index + 1, duplicate) }).linkResponsePage(duplicate.id, pages[index].id)
}

/**
 * Title for a notebook copy that never collides with [existingTitles]:
 * "Copy of X", then "Copy of X (2)", "Copy of X (3)", and so on.
 */
fun duplicateNotebookTitle(base: String, existingTitles: Set<String>): String {
    val trimmed = base.trim().ifEmpty { "Notebook" }
    val first = "Copy of $trimmed"
    if (first !in existingTitles) return first
    var n = 2
    while ("$first ($n)" in existingTitles) n++
    return "$first ($n)"
}

/** Practice pages and score notebooks live behind their own features, not on the Library shelf. */
val Notebook.hiddenFromLibrary: Boolean get() = mistakePractice || musicScoreId != null

/**
 * A copy of this notebook with a fresh notebook id and fresh page ids, ready to save
 * beside the original. Exam tags, cover, folder and page content travel along; marked
 * attempts and mistake-review links do not, so the copy starts unmarked. The peek view
 * is remapped onto the copied page it framed.
 */
fun Notebook.duplicatedAsCopy(newTitle: String, now: Long = System.currentTimeMillis()): Notebook {
    val pageIds = pages.associate { it.id to UUID.randomUUID().toString() }
    return copy(
        id = UUID.randomUUID().toString(),
        title = newTitle,
        updated = now,
        starred = false,
        pinned = false,
        pinnedInFolder = false,
        attempts = emptyList(),
        mistakeReviews = emptyList(),
        documentHistory = DocumentHistory(),
        musicScoreId = null,
        pages = pages.map { page -> page.copy(id = pageIds[page.id] ?: UUID.randomUUID().toString()) },
        peekAnchor = peekAnchor?.let { anchor -> pageIds[anchor.pageId]?.let { anchor.copy(pageId = it) } },
        longResponse = longResponse?.remapPages(pageIds)?.let { response -> response.copy(attempts = response.attempts.map { it.copy(resultId = null) }) },
        feedbackActions = feedbackActions.map { it.copy(pageId = pageIds[it.pageId] ?: it.pageId) }
    )
}

/** Reorders one page, clamped to the notebook; a bad index leaves the notebook untouched. */
fun Notebook.withMovedPage(from: Int, to: Int): Notebook {
    if (from !in pages.indices) return this
    val target = to.coerceIn(0, pages.lastIndex)
    if (from == target) return this
    return copy(pages = pages.toMutableList().apply { add(target, removeAt(from)) })
}

/** Removes a page, keeping one blank page so a notebook always has somewhere to write. */
fun Notebook.withDeletedPage(index: Int): Notebook {
    if (index !in pages.indices) return this
    val removedId = pages[index].id
    val remaining = pages.toMutableList().apply { removeAt(index) }
    return copy(pages = remaining.ifEmpty { listOf(NotePage()) },
        peekAnchor = peekAnchor?.takeIf { it.pageId != removedId })
}

/** Inserts a page at [index], clamped to the notebook's bounds. */
fun Notebook.withInsertedPage(index: Int, page: NotePage = NotePage()): Notebook {
    val at = index.coerceIn(0, pages.size)
    return copy(pages = pages.toMutableList().apply { add(at, page) })
}

/** Swaps in a page whose content has just arrived from disk, keeping its place in the notebook. */
fun Notebook.withPage(page: NotePage): Notebook =
    copy(pages = pages.replacePage(page))

/** This page as the on-disk index holds it: its identity, paper and revision, but none of its ink. */
fun NotePage.asSummary(): NotePage = copy(strokes = emptyList(), texts = emptyList(), images = emptyList(), layers = emptyList(), loaded = false)

/** Marks a content change, so cached previews and exports know their copy is out of date. */
fun NotePage.revised(): NotePage = copy(revision = revision + 1)

/**
 * Where the page you were reading ends up once a page moves from [from] to [to], so reordering
 * keeps the open page on screen instead of jumping to an unrelated one.
 */
fun movedPageIndex(current: Int, from: Int, to: Int): Int = when {
    current == from -> to
    from < to && current in (from + 1)..to -> current - 1
    to < from && current in to..(from - 1) -> current + 1
    else -> current
}
