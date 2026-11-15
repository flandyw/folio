package com.folio.notes.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

/**
 * Where a saved connection sends its requests. ChatGPT signs in with OAuth and speaks the Responses API; the others
 * take a pasted API key and speak OpenAI-compatible Chat Completions ([ChatCompletions]). A stored record names its
 * provider by [id]; records written before keys existed have none and are ChatGPT.
 *
 * **Adding an OpenAI-compatible provider is one entry here and nothing else**: the key card, account list, model
 * catalog, requests, error wording and network diagnostics all read this table. Never change or reuse an [id].
 *
 * @param base HTTPS root that serves `/models` and `/chat/completions`.
 * @param keyPrefix what every key starts with, to catch a key pasted for the wrong provider; empty if there is no fixed prefix.
 * @param keyCheck a GET path under [base] that answers 401 to a bad key without spending anything, or null when the
 *   provider has none (its `/models` is public), in which case a key is first proven by a response.
 * @param keysUrl where the user creates a key; [usageUrl] where they see credits and limits.
 * @param sharedWith who receives the pages, for the privacy notice.
 */
internal enum class AiProvider(val id: String, val label: String, val base: String, val keyPrefix: String, val keyCheck: String?,
    val keysUrl: String, val usageUrl: String, val sharedWith: String) {
    CHATGPT("chatgpt", "ChatGPT", ChatGptProtocol.RESOURCE, "", null, "", "https://chatgpt.com/settings/usage", "OpenAI"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-", "/key",
        "https://openrouter.ai/settings/keys", "https://openrouter.ai/activity", "OpenRouter and the company that runs the chosen model"),
    NVIDIA("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "nvapi-", null,
        "https://build.nvidia.com/settings/api-keys", "https://build.nvidia.com/settings/api-keys", "NVIDIA"),
    AMD("amd", "AMD Token Factory", "https://developer.amd.com.cn/radeon/api/v1", "", "/models",
        "https://developer.amd.com.cn/radeon/tokenfactory", "https://developer.amd.com.cn/radeon/tokenfactory", "AMD");

    /** Uses a pasted API key instead of a ChatGPT sign-in. */
    val key get() = this != CHATGPT

    companion object {
        fun of(record: JSONObject?): AiProvider = entries.firstOrNull { it.id == record?.optString("provider") } ?: CHATGPT
        /** Null when [key] is plausible, else what to tell the user. Header-safe characters only: the key is sent as a header. */
        fun keyProblem(provider: AiProvider, key: String): String? = when {
            key.length !in 20..512 || key.any { it.code !in 33..126 } -> "That does not look like a complete ${provider.label} API key. Paste the whole key."
            !key.startsWith(provider.keyPrefix) -> "${provider.label} keys start with ${provider.keyPrefix}. Check that this key is from ${provider.label}."
            else -> null
        }
    }
}

/** OpenAI-compatible Chat Completions, as OpenRouter and NVIDIA NIM serve it. No Android or network dependencies. */
internal object ChatCompletions {
    // A plain catalog (no capability fields) is filtered by name: these ids are embedding, safety and parsing models, not chat.
    private val notChat = Regex("embed|guard|safety|clip|parse|detector|reward|translate|deplot|topic-control|calibration|code|chatqa|kosmos|fuyu|neva|vila|cosmos")
    // OpenRouter's general-purpose routers; with its `~vendor/family-latest` aliases they make the short list,
    // which therefore tracks new releases without Folio naming any model version.
    private val routers = setOf("openrouter/auto", "openrouter/free")

    /**
     * The models worth offering: chat models that stream text and are not being withdrawn (drops batch-only
     * variants, image/audio generators and anything with an expiry date), short list first.
     */
    fun models(catalog: JSONObject): List<ChatGptModel> {
        val data = catalog.getJSONArray("data")
        fun list(value: JSONObject?, key: String) = value?.optJSONArray(key)?.let { items -> (0 until items.length()).map { items.optString(it) } }
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val architecture = item.optJSONObject("architecture")
            val suggested = id.startsWith("~") || id in routers
            if (!suggested && list(architecture, "output_modalities")?.let { it != listOf("text") } == true) return@mapNotNull null
            if (list(item, "endpoints")?.none { it.endsWith("/chat/completions") } == true || item.opt("expiration_date") is String) return@mapNotNull null
            if (architecture == null && notChat.containsMatchIn(id)) return@mapNotNull null
            val parameters = list(item, "supported_parameters")
            ChatGptModel(id, (item.opt("name") as? String)?.takeIf(String::isNotBlank) ?: id,
                efforts = if (parameters?.contains("reasoning") == true) listOf("low", "medium", "high") else emptyList(),
                // Unknown (a plain catalog) counts as able, so a vision model is never blocked; an unable one fails with a clear message.
                images = list(architecture, "input_modalities")?.contains("image") ?: true,
                json = when {
                    parameters == null -> "none"
                    "structured_outputs" in parameters -> "schema"
                    "response_format" in parameters -> "object"
                    else -> "none"
                }, suggested = suggested)
        }.sortedBy { if (it.slug in routers) 0 else if (it.suggested) 1 else 2 }
    }

    /**
     * [content] and [format] arrive in the Responses shapes the rest of Folio builds. The schema is always spelled
     * out in the system message too, because many models here cannot enforce one; [structured] false leaves the
     * `response_format` field out for a model that rejected it.
     */
    fun body(model: ChatGptModel, instructions: String, content: JSONArray, format: JSONObject?, effort: String?, structured: Boolean = true): JSONObject {
        val parts = (0 until content.length()).map { content.getJSONObject(it) }
        // Text-only models often accept only a plain string, so parts are used only when there is a picture.
        val user: Any = if (parts.none { it.getString("type") == "input_image" }) parts.joinToString("\n") { it.getString("text") }
        else JSONArray(parts.map { part ->
            if (part.getString("type") == "input_image") JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", part.getString("image_url")).put("detail", part.optString("detail", "auto")))
            else JSONObject().put("type", "text").put("text", part.getString("text"))
        })
        val system = if (format == null) instructions else "$instructions\nReply with one JSON object only, with no code fences or commentary, " +
            "matching this JSON Schema:\n${format.getJSONObject("schema")}"
        val body = JSONObject().put("model", model.slug).put("stream", true).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user)))
        if (format != null && structured) when (model.json) {
            "schema" -> body.put("response_format", JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", format.getString("name")).put("strict", true).put("schema", format.getJSONObject("schema"))))
            "object" -> body.put("response_format", JSONObject().put("type", "json_object"))
        }
        effort?.let { body.put("reasoning", JSONObject().put("effort", it)) }
        return body
    }

    /** Like [ChatGptProtocol.response]: a cut-off, filtered or failed stream never becomes usable text. */
    fun response(reader: BufferedReader, progress: (ChatGptStage) -> Unit = {}): ChatGptResponse {
        val text = StringBuilder()
        var usage: ChatGptUsage? = null
        var stage: ChatGptStage? = null
        var finished = false
        fun advance(next: ChatGptStage) {
            if (stage == null || next.ordinal > checkNotNull(stage).ordinal) { stage = next; progress(next) }
        }
        while (true) {
            val line = reader.readLine() ?: break
            require(line.length <= ChatGptProtocol.MAX_TEXT * 8) { "Stream event too large" }
            // Lines starting with ':' are keep-alive comments (OpenRouter sends them while a model warms up).
            if (line.startsWith(":")) advance(ChatGptStage.WAITING)
            if (!line.startsWith("data:")) continue
            val raw = line.removePrefix("data:").trim()
            if (raw == "[DONE]") { finished = true; break }
            val event = JSONObject(raw)
            if (event.has("error")) throw ChatGptFailure("The model stopped with an error before finishing. Try again, or choose another model.")
            advance(ChatGptStage.WAITING)
            event.optJSONObject("usage")?.let { reported ->
                usage = ChatGptInference.usage(JSONObject().put("input_tokens", reported.opt("prompt_tokens"))
                    .put("output_tokens", reported.opt("completion_tokens"))
                    .put("output_tokens_details", reported.opt("completion_tokens_details"))
                    .put("input_tokens_details", reported.opt("prompt_tokens_details"))) ?: usage
            }
            val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
            val delta = choice.optJSONObject("delta")
            if (listOf("reasoning", "reasoning_content").any { !(delta?.opt(it) as? String).isNullOrEmpty() }) advance(ChatGptStage.REASONING)
            (delta?.opt("content") as? String)?.takeIf(String::isNotEmpty)?.let {
                advance(ChatGptStage.WRITING)
                text.append(it)
                require(text.length <= ChatGptProtocol.MAX_TEXT) { "Response too large" }
            }
            when (choice.opt("finish_reason")) {
                "length" -> throw ChatGptFailure("The model ran out of room before finishing. Try fewer pages or notes, or choose another model.")
                "content_filter" -> throw ChatGptFailure("The model's content filter stopped this response. Try another model.")
                "error" -> throw ChatGptFailure("The model stopped with an error before finishing. Try again, or choose another model.")
                is String -> finished = true // Keep reading: usage may follow in a last chunk.
            }
        }
        if (!finished) throw ChatGptFailure("The connection ended before the model completed its response. Try again.")
        // Some reasoning models write their thinking inline before the answer.
        val answer = text.toString().substringAfterLast("</think>").trim()
        if (answer.isEmpty()) throw ChatGptFailure("The model returned no text. Try again, or choose another model.")
        return ChatGptResponse(answer, usage)
    }

    /** The JSON object in a reply that may wrap it in a code fence or a sentence; validation happens afterwards. */
    fun json(text: String): String {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else text
    }

    /** Status categories only: provider error bodies are never shown. */
    fun failure(provider: AiProvider, status: Int): ChatGptFailure = ChatGptFailure(when (status) {
        401 -> "${provider.label} did not accept this API key. Check it, or remove it and add a new one."
        402 -> "Your ${provider.label} credits have run out. Add credits, or choose a free model."
        403 -> "${provider.label} refused this request. Check the key's permissions and limits, or choose another model."
        404, 410 -> "${provider.label} no longer offers this model. Choose another model."
        413 -> "The pages are too large for this model. Review fewer pages, or choose another model."
        400, 415, 422 -> "${provider.label} rejected this request. The model may not accept page images or this kind of request; choose another model."
        429 -> "${provider.label} is limiting requests. Try again shortly, or choose another model."
        in 500..599 -> "${provider.label} or the model's host is having trouble. Try again shortly, or choose another model (HTTP $status)."
        else -> "${provider.label} could not complete this request. Try again${if (status > 0) " (HTTP $status)" else ""}."
    })
}
