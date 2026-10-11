package com.folio.notes.chatgpt

/** Local review choices and edits; independent of providers and notebook storage. */
internal enum class ChatGptReviewIntent(val label: String, val prompt: String) {
    CHECK("Check working", "Check my working step by step and explain any mistakes. Point out what is correct, then suggest specific improvements. If the pages show the marks for a question or a clear total, give a mark as x/y with one line on why; otherwise do not give a mark."),
    EXPLAIN("Explain", "Help me understand these pages. Explain the key ideas and any confusing steps in plain language. Suggest what I should clarify in my notes."),
    REVISE("Help me revise", "Turn these pages into useful revision feedback. Identify the key ideas to remember, gaps to revisit and one or two useful questions to test my understanding."),
    MARK("Mark against criteria", "Assess the work against the question, rubric or model answer supplied in the pages or context. Give criterion-by-criterion evidence, explain lost marks and the smallest changes that earn them back. Distinguish missing evidence from incorrect reasoning. Only give numerical marks when an explicit allocation is available; otherwise give qualitative feedback."),
    ERRORS("Find misconceptions", "Find recurring misconceptions across these pages, not just individual slips. For each, cite the page and visible step, explain the underlying misunderstanding, give a corrected example and a quick diagnostic question. Prioritize errors that affect later working. Distinguish certain errors from unclear handwriting."),
    CONNECTIONS("Connect ideas", "Connect the ideas across these pages. Identify dependencies, contradictions, changes in notation and gaps between steps. Explain how the topics fit together and which earlier idea unlocks a later one. Cite page numbers and give a compact verbal concept map with practical examples."),
    PRACTICE("Build practice", "Create targeted practice based on weaknesses in this work: start with a warm-up, then a similar problem, then a transfer or exam-style challenge. Put questions and worked answers in separate numbered notes, with matching question numbers. Use the student's notation and level. Make each question self-contained and check that the answer follows from its stated data."),
    FLASHCARDS("Make flashcards", "Create active-recall flashcards from the key ideas in these pages, prioritizing gaps and commonly confused concepts. Each note is one card with a clearly labelled Question and Answer. Include why/how and application cards, not only definitions. Keep each card focused and avoid reproducing an unclear claim as fact."),
    PLAN("Plan next steps", "Build a prioritized study plan from the evidence in these pages. Identify strengths, the highest-impact gaps, prerequisites and concrete practice tasks. Order tasks so prerequisites come first. Give each task a short time estimate and a measurable check for mastery. Finish with a short self-test and state which topics need more source material."),
    WRITING("Improve writing", "Review structure, argument, evidence, clarity and subject-specific language. Cite page numbers and short phrases so feedback can be located. Explain the most valuable revisions, provide a short improved example while preserving the student's meaning and voice, and flag claims needing evidence. Use supplied criteria when available."),
    SUMMARY("Create study notes", "Synthesize these pages into concise study notes: key ideas, definitions, formulas with conditions of use, and one worked example where helpful. Reconcile repeated material and highlight contradictions or missing steps rather than silently guessing. Cite source page numbers. Finish with the most useful things to memorize and understand."),
    CUSTOM("My own request", ""),
}

internal data class ChatGptDraft(val text: String, val included: Boolean = true)

internal object ChatGptReview {
    const val MAX_NOTES = 12
    const val MAX_NOTE_LENGTH = 800

    fun prompt(intent: ChatGptReviewIntent, details: String, custom: String, depth: Int, hintsOnly: Boolean): String {
        val task = if (intent == ChatGptReviewIntent.CUSTOM) custom.take(4000)
            else intent.prompt + details.take(3000).takeIf(String::isNotBlank)?.let { "\nContext, question or marking criteria: $it" }.orEmpty()
        return task + "\nOutput budget: up to ${depth.coerceIn(1, MAX_NOTES)} notes. Prioritize useful substance over filling the budget." +
            if (hintsOnly) "\nHints only: do not reveal final answers, full worked solutions or flashcard answers. Use progressively stronger hints and questions so I can finish the work myself."
            else "\nInclude worked examples or answers when the task calls for them."
    }

    /** Excluded drafts can be unfinished; included drafts must all be usable. */
    fun selected(drafts: List<ChatGptDraft>): List<String>? {
        val selected = drafts.filter { it.included }.map { it.text.trim() }
        return selected.takeIf { it.size in 1..MAX_NOTES && it.all { text -> text.isNotBlank() && text.length <= MAX_NOTE_LENGTH } }
    }
}
