package com.folio.notes

/** Reuse descriptive setup without bringing over PDF content, marks, history or completed status. */
internal fun Notebook.blankLike(title: String): Notebook {
    val first = pages.firstOrNull() ?: NotePage()
    return Notebook(title = title, folderId = folderId, cover = cover, pageCover = pageCover, tags = tags,
        exam = exam.copy(status = ExamStatus.TO_DO), defaultPaper = defaultPaper,
        pages = listOf(NotePage(width = first.width, height = first.height, infinite = first.infinite, paper = defaultPaper ?: first.paper)))
}
