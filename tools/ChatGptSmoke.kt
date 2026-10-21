package com.folio.notes.chatgpt

import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun rejects(action: () -> Unit) { check(runCatching(action).isFailure) }
private fun stream(vararg events: JSONObject): String = events.joinToString("\r\n\r\n") { "data: $it" }
private fun read(raw: String) = ChatGptProtocol.readResponse(StringReader(raw).buffered())

fun main() {
    // Only the user's selected, edited suggestions should reach the notebook.
    check(ChatGptReview.selected(listOf(ChatGptDraft("Original"), ChatGptDraft(" ", false))) == listOf("Original"))
    check(ChatGptReview.selected(listOf(ChatGptDraft(" Original ", false), ChatGptDraft("Edited suggestion"))) == listOf("Edited suggestion"))
    check(ChatGptReview.selected(listOf(ChatGptDraft(" "))) == null)
    check(ChatGptReview.selected(listOf(ChatGptDraft("Suggestion", false))) == null)
    check(ChatGptReview.selected(listOf(ChatGptDraft("x".repeat(801)))) == null)
    check(ChatGptReview.selected((1..13).map { ChatGptDraft("Suggestion $it") }) == null)
    check(ChatGptReview.selected(listOf(ChatGptDraft("x".repeat(800))))?.single()?.length == 800)
    check(ChatGptReviewIntent.entries.filter { it != ChatGptReviewIntent.CUSTOM }.all { it.prompt.isNotBlank() && it.prompt.length < 700 })
    check(ChatGptReview.selected((1..12).map { ChatGptDraft("Suggestion $it") })?.size == 12)
    val hints = ChatGptReview.prompt(ChatGptReviewIntent.PRACTICE, "Year 12", "", 8, true)
    check(hints.contains("Year 12") && hints.contains("up to 8 notes") && hints.contains("Hints only"))
    check(ChatGptReview.prompt(ChatGptReviewIntent.CUSTOM, "", "Compare the proofs", 99, false).contains("up to 12 notes"))
    check(ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    check(ChatGptProtocol.random().length == 43)
    val callback = "/auth/callback?state=expected&code=a%2Bb&client_id=oaiapp_test"
    check(ChatGptProtocol.callback(callback, "expected", null) == ("a+b" to "oaiapp_test"))
    check(ChatGptProtocol.callback("/auth/callback?state=expected&code=code", "expected", "oaiapp_test").second == "oaiapp_test")
    rejects { ChatGptProtocol.callback(callback, "wrong", null) }
    rejects { ChatGptProtocol.callback(callback + "&state=expected", "expected", null) }
    rejects { ChatGptProtocol.callback(callback, "expected", "another_client") }
    rejects { ChatGptProtocol.callback(callback.replace("/auth/callback", "/callback"), "expected", null) }
    rejects { ChatGptProtocol.callback("/auth/callback?state=expected&code=code", "expected", null) }
    rejects { ChatGptProtocol.callback("/auth/callback?state=expected&error=access_denied", "expected", null) }
    rejects { ChatGptProtocol.callback(callback.replace("oaiapp_test", "dynamic_agent_client"), "expected", null) }
    check(ChatGptProtocol.query(mapOf("prompt" to "a+b & c")).contains("a%2Bb+%26+c"))

    fun credentials() = JSONObject().put("token_type", "Bearer").put("access_token", "access-test")
        .put("refresh_token", "refresh-test").put("scope", ChatGptProtocol.SCOPES).put("expires_in", 3600)
    val connection = JSONObject()
    ChatGptProtocol.installTokens(connection, credentials(), false, 1000)
    check(connection.getLong("expires_at") == 3_601_000L)
    rejects { ChatGptProtocol.installTokens(JSONObject(), credentials().put("scope", JSONObject.NULL), false, 1000) }
    rejects { ChatGptProtocol.installTokens(JSONObject(), credentials().put("expires_in", "3600"), false, 1000) }
    rejects { ChatGptProtocol.installTokens(JSONObject(), credentials().put("refresh_token", JSONObject.NULL), false, 1000) }
    rejects { ChatGptProtocol.installTokens(JSONObject(), credentials().put("access_token", true), false, 1000) }
    rejects { ChatGptProtocol.installTokens(JSONObject(), credentials().put("access_token", JSONObject.NULL), false, 1000) }
    val rotated = credentials().put("access_token", "rotated-test")
    rotated.remove("scope"); rotated.put("refresh_token", JSONObject.NULL)
    ChatGptProtocol.installTokens(connection, rotated, true, 2000)
    check(connection.getString("refresh_token") == "refresh-test" && connection.getString("access_token") == "rotated-test")
    val identityOnly = JSONObject().put("scope", "openid email").put("id_token", "identity-test")
    ChatGptProtocol.installTokens(connection, identityOnly, false, 1000)
    check(!connection.has("access_token") && !connection.has("expires_at") && connection.getString("id_token") == "identity-test")

    // A real connection must fall back before sending, and must never replay a sent POST.
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = AtomicInteger()
    server.createContext("/") { exchange ->
        requests.incrementAndGet()
        exchange.requestBody.use { check(String(it.readBytes()) == "code=test-code") }
        if (exchange.requestURI.path == "/drop") exchange.close()
        else {
            val unavailable = exchange.requestURI.path == "/unavailable"
            if (unavailable) exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (unavailable) 503 else 200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }
    }
    server.start()
    val client = ChatGptTransport.client().newBuilder().connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
        .dns(object : Dns { override fun lookup(hostname: String) = listOf(
            InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")) }).build()
    try {
        fun request(path: String) = Request.Builder().url("http://folio-test.invalid:${server.address.port}/$path")
            .post(ChatGptTransport.once(FormBody.Builder().add("code", "test-code").build())).build()
        client.newCall(request("fallback")).execute().use { check(it.code == 200 && it.body?.string() == "ok") }
        check(requests.get() == 1)
        client.newCall(request("unavailable")).execute().use { check(it.code == 503) }
        check(requests.get() == 2)
        rejects { client.newCall(request("drop")).execute().close() }
        check(requests.get() == 3)
    } finally {
        server.stop(0); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
    }
    check(ChatGptTransport.failure(UnknownHostException("secret-test")).message?.contains("DNS") == true)
    check(ChatGptTransport.failure(IOException("secret-test")).message?.contains("secret-test") == false)
    check(ChatGptTransport.failure(UnknownHostException("secret-test"), "api.openai.com").message?.contains("api.openai.com") == true)
    check(ChatGptTransport.failure(UnknownHostException("secret-test"), "auth.openai.com").message?.contains("auth.openai.com") == true)
    check(ChatGptTransport.failure(UnknownHostException("secret-test"), "secret-test").message?.contains("secret-test") == false)

    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val pub = pair.public as RSAPublicKey
    fun encoded(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unsigned(bytes: ByteArray) = if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    val jwk = JSONObject().put("kid", "test").put("kty", "RSA").put("n", encoded(unsigned(pub.modulus.toByteArray())))
        .put("e", encoded(unsigned(pub.publicExponent.toByteArray())))
    val keys = JSONObject().put("keys", JSONArray().put(jwk))
    fun claims() = JSONObject().put("iss", ChatGptProtocol.ISSUER).put("aud", "oaiapp_test").put("sub", "account")
        .put("nonce", "nonce").put("exp", 2000).put("iat", 900).put("email", "test@example.com")
    fun token(claims: JSONObject, alg: String = "RS256"): String {
        val header = JSONObject().put("alg", alg).put("kid", "test")
        val value = encoded(header.toString().toByteArray()) + "." + encoded(claims.toString().toByteArray())
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(value.toByteArray()) }
        return value + "." + encoded(signer.sign())
    }
    fun verify(value: String) = ChatGptProtocol.identity(value, "oaiapp_test", "nonce", keys, 1000)
    check(verify(token(claims())).subject == "account")
    rejects { verify(token(claims().put("iss", "https://other.example"))) }
    rejects { verify(token(claims().put("aud", "another_client"))) }
    rejects { verify(token(claims().put("nonce", "other"))) }
    rejects { verify(token(claims().put("exp", 900))) }
    rejects { verify(token(claims().put("iat", 1100))) }
    rejects { verify(token(claims().put("nbf", 1100))) }
    rejects { verify(token(claims().put("sub", ""))) }
    rejects { verify(token(claims(), "none")) }
    rejects { verify(token(claims().put("aud", JSONArray().put("oaiapp_test").put("other")))) }
    check(verify(token(claims().put("aud", JSONArray().put("oaiapp_test").put("other")).put("azp", "oaiapp_test"))).subject == "account")
    val valid = token(claims())
    val parts = valid.split('.')
    val corrupted = Base64.getUrlDecoder().decode(parts[2]).also { it[0] = (it[0].toInt() xor 1).toByte() }
    rejects { verify(parts.take(2).joinToString(".") + "." + encoded(corrupted)) }

    val delta = JSONObject().put("type", "response.output_text.delta").put("delta", "Completed text")
    val completed = JSONObject().put("type", "response.completed")
    check(read(stream(delta, completed)) == "Completed text")
    check(read(stream(delta, completed) + "\r\n\r\n") == "Completed text")
    rejects { read(stream(delta)) }
    rejects { read(stream(delta, JSONObject().put("type", "response.incomplete"))) }
    rejects { read(stream(delta, JSONObject().put("type", "response.failed").put("response", JSONObject().put("error",
        JSONObject().put("code", "subscription_sharing_usage_limit_exceeded"))))) }
    rejects { read("data: [DONE]\n\n") }
    rejects { read(stream(completed)) }
    rejects { read("data: broken json\n\n") }
    rejects { read(stream(JSONObject().put("type", "response.output_text.delta").put("delta", "x".repeat(ChatGptProtocol.MAX_TEXT + 1)), completed)) }
    val finalOnly = JSONObject().put("type", "response.completed").put("response", JSONObject().put("status", "completed").put("output",
        JSONArray().put(JSONObject().put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Final text"))))))
    check(read(stream(finalOnly)) == "Final text")

    // Capability fallbacks must not send unsupported reasoning levels to unknown models.
    fun catalog(slug: String) = JSONObject().put("slug", slug)
    check(ChatGptInference.model(catalog("gpt-6.1-sol")).efforts == listOf("low", "medium", "high", "xhigh", "max"))
    check("none" in ChatGptInference.model(catalog("gpt-6-luna")).efforts)
    check(ChatGptInference.model(catalog("future-model")).efforts.isEmpty())
    check(ChatGptInference.model(catalog("future-model").put("supported_reasoning_levels",
        JSONArray().put(JSONObject().put("effort", "high")).put("low").put("unknown").put("low"))).efforts == listOf("high", "low"))
    check(ChatGptInference.model(catalog("gpt-6.1-sol").put("supported_reasoning_levels", JSONArray())).efforts.isEmpty())

    val usage = JSONObject().put("input_tokens", 100).put("output_tokens", 40)
        .put("input_tokens_details", JSONObject().put("cached_tokens", 20))
        .put("output_tokens_details", JSONObject().put("reasoning_tokens", 30))
    val phases = mutableListOf<ChatGptStage>()
    val withUsage = JSONObject(completed.toString()).put("response", JSONObject().put("status", "completed").put("usage", usage))
    val response = ChatGptProtocol.response(StringReader(stream(
        JSONObject().put("type", "response.created"),
        JSONObject().put("type", "response.output_item.added").put("item", JSONObject().put("type", "reasoning")),
        delta, JSONObject().put("type", "response.in_progress"), delta, withUsage)).buffered(), phases::add)
    check(phases == listOf(ChatGptStage.WAITING, ChatGptStage.REASONING, ChatGptStage.WRITING))
    check(response.usage == ChatGptUsage(100, 40, 30, 20))
    check(response.usage!!.total == 140L) // Do not double-count reasoning.
    check(ChatGptInference.usage(null) == null)
    check(ChatGptInference.usage(JSONObject().put("input_tokens", "100").put("output_tokens", 10)) == null)
    check(ChatGptInference.usage(JSONObject().put("input_tokens", -1).put("output_tokens", 10)) == null)
    check(ChatGptInference.usage(JSONObject().put("input_tokens", 0.5).put("output_tokens", 10)) == null)
    val summary = ChatGptUsageSummary().add(response.usage).add(null)
    check(summary.completed == 2 && summary.reported == 1 && summary.tokens == 140L && summary.last == null)

    val old = JSONObject().put("client_id", "old").put("subject", "known").put("refresh_token", "old-refresh")
        .put("pending_sign_in", JSONObject().put("credentials", JSONObject().put("refresh_token", "pending-refresh")))
    check(ChatGptInference.revocations(old) == listOf("old-refresh" to "refresh_token", "pending-refresh" to "refresh_token"))
    val records = JSONObject().put("active", "old").put("host_id", "host")
        .put("connections", JSONArray().put(old).put(JSONObject().put("client_id", "other").put("access_token", "other-access")))
    ChatGptInference.forget(records, "old", remove = false)
    val cleared = records.getJSONArray("connections").getJSONObject(0)
    check(cleared.getString("subject") == "known" && !cleared.has("pending_sign_in") && !cleared.has("refresh_token"))
    check(records.getString("active") == "old" && records.getString("host_id") == "host")
    ChatGptInference.forget(records, "old", remove = true)
    check(records.getString("active") == "other" && records.getJSONArray("connections").length() == 1)
    ChatGptInference.forget(records, "other", remove = true)
    check(!records.has("active") && records.getJSONArray("connections").length() == 0)

    val timings = ChatGptTimings()
    check(timings.estimate("sol", "low") == null)
    timings.record("sol", "low", 60_000); timings.record("sol", "low", 120_000)
    check(timings.estimate("sol", "low") == 90_000L && timings.estimate("sol", "high") == null)
    timings.record("sol", "low", -1)
    check(timings.estimate("sol", "low") == 90_000L)
    check(ChatGptTimings.remaining(60_000, 100_000).contains("longer"))
    check(!ChatGptTimings.remaining(60_000, 80_000).contains("-"))

    fun annotation() = JSONObject().put("kind", "sticky_note").put("region_id", "R1").put("text", "Check the units.")
    fun feedback(items: JSONArray = JSONArray().put(annotation())) = JSONObject().put("schema_version", 1).put("request_id", "review")
        .put("annotations", items)
    check(ChatGptProtocol.feedback(feedback().toString(), "review") == listOf("Check the units."))
    rejects { ChatGptProtocol.feedback(feedback().toString(), "another_review") }
    rejects { ChatGptProtocol.feedback(feedback(JSONArray().put(annotation().put("region_id", "R2"))).toString(), "review") }
    rejects { ChatGptProtocol.feedback(feedback(JSONArray()).toString(), "review") }
    check(ChatGptProtocol.feedback(feedback(JSONArray((1..12).map { annotation() })).toString(), "review").size == 12)
    rejects { ChatGptProtocol.feedback(feedback(JSONArray((1..13).map { annotation() })).toString(), "review") }
    rejects { ChatGptProtocol.feedback(feedback(JSONArray().put(annotation().put("text", "x".repeat(801)))).toString(), "review") }
    rejects { ChatGptProtocol.feedback(feedback(JSONArray().put(annotation().put("text", " "))).toString(), "review") }
    rejects { ChatGptProtocol.feedback(feedback(JSONArray().put(annotation().put("text", true))).toString(), "review") }
    rejects { ChatGptProtocol.feedback(feedback().put("schema_version", "1").toString(), "review") }
    val math = "Check \\(\\frac{1}{2}x^2\\)."
    check(ChatGptProtocol.feedback(feedback(JSONArray().put(annotation().put("text", math))).toString(), "review") == listOf(math))
    check(ChatGptProtocol.feedbackFormat().getBoolean("strict"))
    println("ChatGPT smoke checks passed: address fallback, no POST replay, credential grants, PKCE, callback binding, signed identity, terminal streams, reasoning capabilities, usage counts, pending credential removal, timing estimates and LaTeX annotations.")
}
