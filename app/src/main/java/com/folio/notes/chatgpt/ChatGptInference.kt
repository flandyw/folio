package com.folio.notes.chatgpt

import org.json.JSONArray
import org.json.JSONObject

internal data class ChatGptModel(val slug: String, val name: String, val efforts: List<String> = emptyList())
internal data class ChatGptUsage(val input: Long, val output: Long, val reasoning: Long?, val cached: Long?) {
    val total get() = input + output // Reasoning is already included in output.
}
internal data class ChatGptUsageSummary(val completed: Int = 0, val reported: Int = 0, val tokens: Long = 0, val last: ChatGptUsage? = null) {
    fun add(usage: ChatGptUsage?) = copy(completed = completed + 1, reported = reported + if (usage != null) 1 else 0,
        tokens = tokens + (usage?.total ?: 0), last = usage)
}
internal data class ChatGptResponse(val text: String, val usage: ChatGptUsage?)
internal enum class ChatGptStage(val label: String) {
    PREPARING("Preparing page"), SENDING("Sending request"), WAITING("ChatGPT is processing"),
    REASONING("ChatGPT is thinking"), WRITING("Writing response"), CHECKING("Checking feedback"),
}

/** Server capabilities take priority. Fallbacks are limited to documented GPT-6 models. */
internal object ChatGptInference {
    private val knownEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    fun model(value: JSONObject): ChatGptModel {
        val slug = value.getString("slug")
        val levels = value.optJSONArray("supported_reasoning_levels")
        val efforts = if (levels != null) (0 until levels.length()).mapNotNull { index ->
            val item = levels.opt(index)
            val effort = if (item is String) item else (item as? JSONObject)?.optString("effort")
            effort?.takeIf { it in knownEfforts }
        }.distinct() else when (slug) {
            "gpt-6.1-sol", "gpt-6-astra" -> knownEfforts.drop(2)
            "gpt-6-sol", "gpt-6-luna" -> listOf("none") + knownEfforts.drop(2)
            else -> emptyList()
        }
        return ChatGptModel(slug, value.optString("display_name", slug), efforts)
    }
    fun effortLabel(effort: String?): String = when (effort) {
        null -> "Model default"; "xhigh" -> "Extra high"; "max" -> "Maximum"
        else -> effort.replaceFirstChar { it.uppercase() }
    }
    fun usage(value: JSONObject?): ChatGptUsage? {
        fun count(objectValue: JSONObject?, key: String): Long? {
            val number = objectValue?.opt(key) as? Number ?: return null
            val count = number.toLong()
            return count.takeIf { it in 0..9_000_000_000_000L && number.toDouble() == it.toDouble() }
        }
        val input = count(value, "input_tokens") ?: return null
        val output = count(value, "output_tokens") ?: return null
        return ChatGptUsage(input, output, count(value?.optJSONObject("output_tokens_details"), "reasoning_tokens")?.takeIf { it <= output },
            count(value?.optJSONObject("input_tokens_details"), "cached_tokens")?.takeIf { it <= input })
    }
    fun revocations(record: JSONObject): List<Pair<String, String>> = listOfNotNull(record,
        record.optJSONObject("pending_sign_in")?.optJSONObject("credentials")).mapNotNull {
        it.optString("refresh_token").takeIf(String::isNotBlank)?.let { token -> token to "refresh_token" }
            ?: it.optString("access_token").takeIf(String::isNotBlank)?.let { token -> token to "access_token" }
    }.distinct()
    fun forget(stored: JSONObject, client: String, remove: Boolean) {
        val entries = stored.getJSONArray("connections")
        val records = (0 until entries.length()).map { entries.getJSONObject(it) }
        val remaining = records.mapNotNull { record ->
            if (record.getString("client_id") != client) record
            else if (remove) null
            else JSONObject(record.toString()).apply {
                for (field in listOf("access_token", "refresh_token", "id_token", "scope", "expires_at", "pending_sign_in")) remove(field)
            }
        }
        stored.put("connections", JSONArray(remaining))
        if (remaining.none { it.getString("client_id") == stored.optString("active") }) {
            stored.remove("active")
            remaining.firstOrNull()?.let { stored.put("active", it.getString("client_id")) }
        }
    }
}

/** A bounded, session-local estimate using successful reviews with this exact setting. */
internal class ChatGptTimings {
    private val samples = mutableMapOf<Pair<String, String?>, List<Long>>()
    fun record(model: String, effort: String?, millis: Long) {
        if (millis !in 1_000..1_800_000) return
        val key = model to effort
        samples[key] = (samples[key].orEmpty() + millis).takeLast(5)
    }
    fun estimate(model: String, effort: String?): Long? = samples[model to effort]?.let { it.sum() / it.size }
    companion object {
        fun duration(millis: Long): String {
            val seconds = (millis.coerceAtLeast(0) / 1000)
            return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
        }
        fun remaining(estimate: Long?, elapsed: Long): String = when {
            estimate == null -> "Learning review time for this setting · finish time unavailable"
            elapsed >= estimate * 3 / 2 -> "Taking longer than earlier reviews · still waiting for completion"
            else -> "Estimated ${duration((estimate * 7 / 10 - elapsed).coerceAtLeast(0))}–${duration(estimate * 3 / 2 - elapsed)} remaining · based on earlier reviews"
        }
    }
}
