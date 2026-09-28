package com.folio.notes.sync

import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.typeOf

/**
 * `rpc` arguments are encoded by the SDK with kotlinx.serialization, from the argument's
 * static type. A parameter map inferred as `Map<String, Any>` has no serializer and every
 * call fails with `SerializationException: Serializer for class 'Any' is not found` before
 * anything is sent — which is how mistake sync used to fail on every queued rating.
 *
 * These tests encode the exact maps `SupabaseSyncRemote` sends, with the same serializer
 * the SDK reaches for, so the shape of the wire body is asserted and the `Any` trap cannot
 * come back unnoticed.
 */
class SyncRemoteParamsTests {
    private val serializer = KotlinXSerializer()

    private fun encode(params: Map<String, kotlinx.serialization.json.JsonElement>): String =
        serializer.encode(typeOf<Map<String, kotlinx.serialization.json.JsonElement>>(), params)

    private fun change(
        operation: String = "put",
        payload: JSONObject? = JSONObject().put("id", "m1").put("question", "Why?"),
        expectedSeq: Long? = 7L
    ) = SyncProtocol.QueuedChange(
        changeId = "c1", entity = "mistakes", rowId = "m1", operation = operation,
        payload = payload, lamport = 3L, createdAt = "2024-05-01T00:00:00Z", expectedSeq = expectedSeq
    )

    @Test
    fun applySendsTheArrayTheRpcExpects() {
        val body = encode(applyParams("user-1", listOf(change()), "device-1"))
        val json = JSONObject(body)
        val change = json.getJSONArray("p_changes").getJSONObject(0)
        assertEquals("c1", change.getString("change_id"))
        assertEquals("device-1", change.getString("client_id"))
        assertEquals("mistakes", change.getString("entity"))
        assertEquals("m1", change.getString("row_id"))
        assertEquals("put", change.getString("operation"))
        assertEquals(3L, change.getLong("lamport"))
        assertEquals(7L, change.getLong("expected_seq"))
        assertEquals("Why?", change.getJSONObject("payload").getString("question"))
        assertEquals("user-1", json.getString("p_expected_user_id"))
        // The array has to stay an array: the SQL signature is `p_changes jsonb`.
        assertEquals(JSONObject(body).get("p_changes") is org.json.JSONArray, true)
    }

    @Test
    fun applySendsNullPayloadAndNoVersionForADelete() {
        val body = JSONObject(encode(applyParams("user-1", listOf(change("delete", null, null)), "device-1")))
        val change = body.getJSONArray("p_changes").getJSONObject(0)
        assertEquals("delete", change.getString("operation"))
        assertEquals(true, change.isNull("payload"))
        assertEquals(false, change.has("expected_seq"))
    }

    @Test
    fun readSendsNumbersAsNumbers() {
        val body = JSONObject(encode(readParams("user-1", 42L, 500)))
        assertEquals(42L, body.getLong("p_after"))
        assertEquals(500L, body.getLong("p_limit"))
        assertEquals("user-1", body.getString("p_expected_user_id"))
    }

    @Test
    fun mutateStampsTheAccountOntoTheCommand() {
        val body = JSONObject(encode(mutateParams("user-1", JSONObject().put("op", "finish"))))
        val command = body.getJSONObject("p_command")
        assertEquals("finish", command.getString("op"))
        assertEquals("user-1", command.getString("expected_user_id"))
    }

    /** The trap itself: the shape that used to be inferred, and the reason it could not be encoded. */
    @Test
    fun anUntypedParameterMapHasNoSerializer() {
        val untyped: Map<String, Any> = mapOf("p_changes" to Json.parseToJsonElement("[]"), "p_expected_user_id" to "user-1")
        val failure = runCatching { serializer.encode(typeOf<Map<String, Any>>(), untyped) }.exceptionOrNull()
        assertEquals(kotlinx.serialization.SerializationException::class.java, failure?.javaClass)
    }
}
