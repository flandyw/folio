package com.folio.notes.chatgpt

/** Local review choices and edits; independent of providers and notebook storage. */
internal enum class ChatGptReviewIntent(val label: String, val prompt: String) {
    CHECK("Check working", "Check my working step by step and explain any mistakes. Point out what is correct, then suggest specific improvements. If the page shows the marks for a question or a clear total, give a mark as x/y with one line on why; otherwise do not give a mark."),
    EXPLAIN("Explain", "Help me understand this page. Explain the key ideas and any confusing steps in plain language. Suggest what I should clarify in my notes."),
    REVISE("Help me revise", "Turn this page into useful revision feedback. Identify the key ideas to remember, gaps to revisit and one or two useful questions to test my understanding."),
    CUSTOM("My own request", ""),
}

internal data class ChatGptDraft(val text: String, val included: Boolean = true)

internal object ChatGptReview {
    /** Excluded drafts can be unfinished; included drafts must all be usable. */
    fun selected(drafts: List<ChatGptDraft>): List<String>? {
        val selected = drafts.filter { it.included }.map { it.text.trim() }
        return selected.takeIf { it.size in 1..4 && it.all { text -> text.isNotBlank() && text.length <= 800 } }
    }
}
