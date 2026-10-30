package com.folio.notes.exam

import com.folio.notes.model.Paper

/** What a template turns into: a fresh notebook plus the tags pre-filled on it. */
data class NotebookTemplate(
    val id: String,
    val title: String,
    val description: String,
    val pages: Int,
    val paper: Paper,
    /** Pre-filled tags the student adjusts after creating, e.g. company VCAA on a past exam. */
    val tags: (year: Int?, company: String) -> ExamTags = { _, _ -> ExamTags() }
) {
    companion object {
        /** A whole past paper: title page plus plenty of workings pages on the maths grid. */
        fun pastExam(paper: Paper = Paper.MATH_GRID) = NotebookTemplate(
            id = "past-exam", title = "Past exam paper", description = "A full paper to sit, with tags for year, company and type",
            pages = 12, paper = paper,
            tags = { year, company -> ExamTags(year = year, company = company, type = ExamType.EXAM_1, status = ExamStatus.TO_DO) }
        )
        /** A topic summary book that becomes part of the bound reference. */
        fun summary() = NotebookTemplate(
            id = "summary", title = "Topic summary", description = "One notebook per topic; exports straight into your bound reference",
            pages = 4, paper = Paper.MATH_GRID,
            tags = { _, _ -> ExamTags(type = ExamType.NOTES, tags = setOf(ExamTagType.BOUND_REFERENCE)) }
        )
        /** Ruled pages for English and the report subjects. */
        fun essays() = NotebookTemplate(
            id = "essays", title = "Essay practice", description = "Ruled pages for handwritten essays and annotations",
            pages = 6, paper = Paper.RULED,
            tags = { _, _ -> ExamTags(type = ExamType.TOPIC_TEST) }
        )
        /** An Exam 2 Section A answer sheet: A–E bubbles to shade in. */
        fun multipleChoice() = NotebookTemplate(
            id = "mc-sheet", title = "Multiple-choice sheet", description = "Exam 2 Section A answer sheet with A–E bubbles, 25 numbered",
            pages = 1, paper = Paper.MC_SHEET
        )
        /** One character per square on tian (田) grid paper, with a dashed cross in each box. */
        fun hanziTian() = NotebookTemplate(
            id = "hanzi-tian", title = "Hanzi · tian grid", description = "田字格 practice — one character per square, 10 pages",
            pages = 10, paper = Paper.TIAN_GRID,
            tags = { _, _ -> ExamTags(type = ExamType.NOTES) }
        )
        /** One character per square on mi (米) grid paper, with cross and diagonals in each box. */
        fun hanziMi() = NotebookTemplate(
            id = "hanzi-mi", title = "Hanzi · mi grid", description = "米字格 practice — cross plus diagonals per square, 10 pages",
            pages = 10, paper = Paper.MI_GRID,
            tags = { _, _ -> ExamTags(type = ExamType.NOTES) }
        )
        val ALL: List<NotebookTemplate> = listOf(pastExam(), summary(), essays(), multipleChoice(), hanziTian(), hanziMi())
        fun byId(id: String?): NotebookTemplate? = ALL.find { it.id == id }
    }
}
