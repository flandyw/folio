package com.folio.notes

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Response links use page identities, so reordering, export and notebook restore preserve them. */
enum class ResponseMode(val label: String) {
    PLAN("Plan only"), PARAGRAPH("Practise a paragraph"), FULL("Full response")
}

enum class FeedbackPractice(val label: String, val mode: ResponseMode) {
    PARAGRAPH("Rewrite a paragraph", ResponseMode.PARAGRAPH),
    QUESTION("Retry the question", ResponseMode.FULL),
    PLAN("Practise a plan", ResponseMode.PLAN)
}

data class ResponseAttempt(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val mode: ResponseMode = ResponseMode.FULL,
    val created: Long = System.currentTimeMillis(),
    val pageIds: List<String>,
    val plan: String = "",
    val parentId: String? = null,
    val sourceActionId: String? = null,
    val resultId: String? = null
)

data class LongResponse(
    val prompt: String,
    val topic: String = "",
    val marks: Int? = null,
    val targetMinutes: Int? = null,
    val attempts: List<ResponseAttempt> = emptyList()
) {
    fun attemptFor(pageId: String?) = attempts.firstOrNull { pageId in it.pageIds }
    fun updateAttempt(id: String, change: (ResponseAttempt) -> ResponseAttempt) =
        copy(attempts = attempts.map { if (it.id == id) change(it) else it })

    fun remapPages(ids: Map<String, String>) = copy(attempts = attempts.map { attempt ->
        attempt.copy(pageIds = attempt.pageIds.mapNotNull(ids::get))
    })
}

data class FeedbackAction(
    val id: String = UUID.randomUUID().toString(),
    val pageId: String,
    val text: String,
    val practice: FeedbackPractice = FeedbackPractice.PARAGRAPH,
    val sourceTextId: String? = null,
    val done: Boolean = false,
    val practiceAttemptId: String? = null
)

/** Optional additive index fields; absent in older notebooks. Ink codecs are unchanged. */
object LongResponseCodec {
    fun encode(value: LongResponse) = JSONObject().apply {
        put("prompt", value.prompt); put("topic", value.topic)
        value.marks?.let { put("marks", it) }
        value.targetMinutes?.let { put("targetMinutes", it) }
        put("attempts", JSONArray().apply { value.attempts.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("title", a.title); put("mode", a.mode.name); put("created", a.created)
            put("pages", JSONArray(a.pageIds)); put("plan", a.plan)
            a.parentId?.let { put("parent", it) }; a.sourceActionId?.let { put("sourceAction", it) }; a.resultId?.let { put("result", it) }
        }) } })
    }

    fun decode(o: JSONObject?): LongResponse? = o?.let {
        LongResponse(it.optString("prompt"), it.optString("topic"),
            it.optInt("marks").takeIf { n -> n in 1..1000 },
            it.optInt("targetMinutes").takeIf { n -> n in 1..1440 },
            it.optJSONArray("attempts").objects().map { a ->
                ResponseAttempt(a.getString("id"), a.getString("title"),
                    ResponseMode.entries.find { mode -> mode.name == a.optString("mode") } ?: ResponseMode.FULL,
                    a.optLong("created"), a.getJSONArray("pages").let { pages ->
                        (0 until pages.length()).map { i -> pages.getString(i) }.distinct()
                    }, a.optString("plan"), a.optionalString("parent"), a.optionalString("sourceAction"), a.optionalString("result"))
            })
    }

    fun encodeActions(actions: List<FeedbackAction>) = JSONArray().apply {
        actions.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("page", a.pageId); put("text", a.text); put("practice", a.practice.name)
            put("done", a.done)
            a.sourceTextId?.let { put("sourceText", it) }
            a.practiceAttemptId?.let { put("attempt", it) }
        }) }
    }

    fun decodeActions(array: JSONArray?) = array.objects().map { a ->
        FeedbackAction(a.getString("id"), a.getString("page"), a.getString("text"),
            FeedbackPractice.entries.find { it.name == a.optString("practice") } ?: FeedbackPractice.PARAGRAPH,
            a.optionalString("sourceText"), a.optBoolean("done"), a.optionalString("attempt"))
    }

    private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).map { getJSONObject(it) }
}

/** Continuation pages follow the attempt being edited, even when it is an older draft. */
fun Notebook.linkResponsePage(pageId: String, sourcePageId: String?): Notebook {
    val response = longResponse ?: return this
    val attempt = response.attemptFor(sourcePageId) ?: return this
    return copy(longResponse = response.updateAttempt(attempt.id) { it.copy(pageIds = it.pageIds + pageId) })
}
