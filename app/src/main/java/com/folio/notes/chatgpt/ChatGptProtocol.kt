package com.folio.notes.chatgpt

import java.io.BufferedReader
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** The OAuth, SSE and annotation contracts, without Android or network dependencies. */
internal object ChatGptProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK = "/auth/callback"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val MAX_TEXT = 64 * 1024

    fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun query(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

    fun callback(target: String, state: String, registeredClient: String?): Pair<String, String> {
        val uri = URI(target)
        require(!uri.isAbsolute && uri.rawPath == CALLBACK) { "Invalid callback path" }
        val fields = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.forEach {
            val pair = it.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], "UTF-8")
            require(key !in fields) { "Duplicate callback field" }
            fields[key] = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        require(MessageDigest.isEqual(fields["state"].orEmpty().toByteArray(), state.toByteArray())) { "Invalid callback state" }
        fields["error"]?.let { throw ChatGptFailure("Sign-in was declined or could not be completed. Try again.") }
        val client = fields["client_id"] ?: registeredClient
        require(!client.isNullOrBlank() && client != "dynamic_agent_client") { "Missing issued client ID" }
        require(registeredClient == null || client == registeredClient) { "Account registration changed" }
        val code = fields["code"]?.takeIf { it.isNotBlank() } ?: error("Missing authorization code")
        return code to client
    }

    data class Identity(val subject: String, val email: String)

    /** Only confirmed scopes establish plan permission; refresh may retain prior grants. */
    fun installTokens(record: JSONObject, token: JSONObject, refresh: Boolean, now: Long) {
        fun text(name: String, fallback: String = "") = when (val value = token.opt(name)) {
            null, JSONObject.NULL -> fallback
            is String -> value
            else -> throw ChatGptFailure("ChatGPT returned an invalid credential field ($name). Sign in again.")
        }
        val scope = text("scope", if (refresh) record.optString("scope") else "")
        if (scope.isBlank()) throw ChatGptFailure("ChatGPT did not confirm the granted permissions. Sign in again.")
        val scopes = scope.split(Regex("\\s+")).filter(String::isNotEmpty)
        val access = text("access_token")
        if (access.isBlank()) {
            if (refresh || "offline_access" in scopes || PLAN_SCOPE in scopes)
                throw ChatGptFailure("ChatGPT did not return the requested access credentials. Sign in again.")
            // Identity-only consent is a valid sign-in, with inference disabled.
            for (field in listOf("access_token", "refresh_token", "expires_at")) record.remove(field)
        } else {
            if (!text("token_type").equals("Bearer", true))
                throw ChatGptFailure("ChatGPT returned an unsupported access token type. Sign in again.")
            val seconds = token.opt("expires_in") as? Number
            if (seconds == null || !seconds.toDouble().isFinite() || seconds.toDouble() <= 0 || seconds.toDouble() > 31_536_000)
                throw ChatGptFailure("ChatGPT did not return a valid access token lifetime. Sign in again.")
            val renewal = text("refresh_token", if (refresh) record.optString("refresh_token") else "")
            if ((refresh || "offline_access" in scopes) && renewal.isBlank())
                throw ChatGptFailure("ChatGPT did not return the refresh credential. Sign in again.")
            record.put("access_token", access).put("refresh_token", renewal)
                .put("expires_at", now + (seconds.toDouble() * 1000).toLong())
        }
        record.put("scope", scopes.joinToString(" "))
        text("id_token").takeIf(String::isNotBlank)?.let { record.put("id_token", it) }
    }

    fun identity(token: String, client: String, nonce: String?, keys: JSONObject, now: Long): Identity {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ID token" }
        fun decode(part: String) = Base64.getUrlDecoder().decode(part)
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256") { "Invalid signature algorithm" }
        val kid = header.getString("kid")
        val candidates = keys.getJSONArray("keys")
        val matches = (0 until candidates.length()).map { candidates.getJSONObject(it) }.filter {
            it.optString("kid") == kid && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        }
        require(matches.size == 1) { "Signing key unavailable" }
        val jwk = matches.single()
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(jwk.getString("n"))), BigInteger(1, decode(jwk.getString("e")))))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(key)
        signature.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(signature.verify(decode(parts[2]))) { "Invalid ID token signature" }
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        require(claims.getString("iss") == ISSUER) { "Invalid issuer" }
        val audiences = when (val aud = claims.get("aud")) {
            is String -> listOf(aud)
            is JSONArray -> (0 until aud.length()).map { aud.getString(it) }
            else -> error("Invalid audience")
        }
        require(client in audiences) { "Invalid audience" }
        require(!claims.has("azp") || claims.getString("azp") == client) { "Invalid authorized party" }
        require(audiences.size <= 1 || claims.optString("azp") == client) { "Missing authorized party" }
        require(claims.getLong("exp") > now - 5 && claims.getLong("iat") <= now + 5) { "Expired ID token" }
        require(!claims.has("nbf") || claims.getLong("nbf") <= now + 5) { "ID token is not active" }
        require(nonce == null || claims.getString("nonce") == nonce) { "Invalid nonce" }
        val subject = claims.getString("sub")
        require(subject.isNotBlank()) { "Missing account identity" }
        return Identity(subject, claims.optString("email", "ChatGPT account"))
    }

    /** A partial stream or a late failure must never become an applicable annotation. */
    fun readResponse(reader: BufferedReader): String = response(reader).text

    fun response(reader: BufferedReader, progress: (ChatGptStage) -> Unit = {}): ChatGptResponse {
        val text = StringBuilder()
        val data = StringBuilder()
        var usage: ChatGptUsage? = null
        var stage: ChatGptStage? = null
        fun advance(next: ChatGptStage) {
            if (stage == null || next.ordinal > checkNotNull(stage).ordinal) { stage = next; progress(next) }
        }
        fun dispatch(): Boolean {
            if (data.isEmpty()) return false
            val raw = data.toString(); data.setLength(0)
            if (raw == "[DONE]") return false
            val event = JSONObject(raw)
            when (event.optString("type")) {
                "response.created", "response.in_progress" -> advance(ChatGptStage.WAITING)
                "response.output_item.added" -> if (event.optJSONObject("item")?.optString("type") == "reasoning") advance(ChatGptStage.REASONING)
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> advance(ChatGptStage.REASONING)
                "response.output_text.delta" -> {
                    advance(ChatGptStage.WRITING)
                    text.append(event.getString("delta"))
                    require(text.length <= MAX_TEXT) { "Response too large" }
                }
                "response.failed", "error" -> throw apiFailure(event.optJSONObject("response") ?: event)
                "response.incomplete" -> throw ChatGptFailure("ChatGPT stopped before completing the response. Try again.")
                "response.completed" -> {
                    val response = event.optJSONObject("response")
                    require(response == null || response.optString("status", "completed") == "completed") { "Incomplete response" }
                    if (text.isEmpty()) {
                        val output = response?.optJSONArray("output") ?: JSONArray()
                        for (i in 0 until output.length()) {
                            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (j in 0 until content.length()) {
                                val item = content.optJSONObject(j) ?: continue
                                if (item.optString("type") == "output_text") text.append(item.getString("text"))
                            }
                        }
                    }
                    usage = ChatGptInference.usage(response?.optJSONObject("usage"))
                    require(text.isNotBlank() && text.length <= MAX_TEXT) { "ChatGPT returned no usable text" }
                    return true
                }
            }
            return false
        }
        while (true) {
            val line = reader.readLine() ?: break
            require(line.length <= MAX_TEXT * 8) { "Stream event too large" }
            if (line.isEmpty()) { if (dispatch()) return ChatGptResponse(text.toString(), usage) }
            else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                require(data.length <= MAX_TEXT * 8) { "Stream event too large" }
            }
        }
        if (dispatch()) return ChatGptResponse(text.toString(), usage)
        throw ChatGptFailure("The connection ended before ChatGPT completed its response. Try again.")
    }

    fun apiFailure(value: JSONObject, status: Int = 0): ChatGptFailure {
        val code = value.optJSONObject("error")?.optString("code") ?: value.optString("error")
        val message = when (code) {
            "subscription_sharing_usage_limit_exceeded" -> "Your ChatGPT app usage limit has been reached. Manage usage in ChatGPT settings."
            "subscription_sharing_usage_unavailable" -> "ChatGPT plan usage is unavailable for this connection. Check its permissions in ChatGPT settings."
            "invalid_grant" -> "Your ChatGPT session needs a new sign-in."
            else -> when (status) {
                401, 403 -> "ChatGPT did not authorise this request. Reconnect your account and check plan permissions."
                429 -> "ChatGPT is limiting requests. Try again shortly."
                else -> "ChatGPT could not complete this request. Try again${if (status > 0) " (HTTP $status)" else ""}."
            }
        }
        return ChatGptFailure(message)
    }

    fun feedback(raw: String, request: String): List<String> {
        require(raw.length <= MAX_TEXT) { "Feedback too large" }
        val value = JSONObject(raw)
        require(value.opt("schema_version") is Number && value.getDouble("schema_version") == 1.0 && value.opt("request_id") == request) { "Feedback does not match this request" }
        val annotations = value.getJSONArray("annotations")
        require(annotations.length() in 1..4) { "Expected one to four comments" }
        return (0 until annotations.length()).map {
            val annotation = annotations.getJSONObject(it)
            require(annotation.opt("region_id") == "R1" && annotation.opt("kind") == "sticky_note") { "Unknown annotation target" }
            require(annotation.opt("text") is String) { "Invalid comment text" }
            annotation.getString("text").trim().also { text -> require(text.length in 1..800) { "Invalid comment length" } }
        }
    }

    fun feedbackFormat(): JSONObject = JSONObject("""{
      "type":"json_schema","name":"folio_feedback","strict":true,"schema":{
        "type":"object","additionalProperties":false,
        "properties":{"schema_version":{"type":"integer","enum":[1]},"request_id":{"type":"string"},
          "annotations":{"type":"array","items":{"type":"object","additionalProperties":false,
            "properties":{"region_id":{"type":"string","enum":["R1"]},"kind":{"type":"string","enum":["sticky_note"]},"text":{"type":"string"}},
            "required":["region_id","kind","text"]}}},"required":["schema_version","request_id","annotations"]}}
    """)
}

internal class ChatGptFailure(message: String) : Exception(message)
