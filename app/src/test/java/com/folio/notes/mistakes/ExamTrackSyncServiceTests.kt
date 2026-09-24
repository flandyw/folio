package com.folio.notes.mistakes

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExamTrackSyncServiceTests {
    private fun client(engine: MockEngine, sessions: MemorySessionManager = MemorySessionManager()) = createSupabaseClient("https://example.supabase.co", "public-test-key") {
        httpEngine = engine
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = sessions
            retryDelay = 25.milliseconds
            autoLoadFromStorage = false; autoSaveToStorage = false
            alwaysAutoRefresh = true; enableLifecycleCallbacks = false
        }
        install(Postgrest)
    }
    private fun session(user: String) = UserSession(accessToken = "test-access", refreshToken = "test-refresh",
        expiresIn = 3600, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = user))

    @Test fun signedOutAndMismatchedAccountsNeverAccessData() = runBlocking {
        var calls = 0
        val client = client(MockEngine { calls++; respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        val remote = ExamTrackSyncService(client)
        assertTrue(runCatching { remote.fetch("u") }.isFailure)
        client.auth.importSession(session("u"), autoRefresh = false)
        assertTrue(runCatching { remote.fetch("other") }.isFailure)
        client.auth.clearSession()
        assertTrue(runCatching { remote.contexts("u") }.isFailure)
        assertEquals(0, calls)
        client.close()
    }
    @Test fun authenticatedDownloadIsUserFilteredAndPaged() = runBlocking {
        var calls = 0
        val client = client(MockEngine { request ->
            assertEquals("Bearer test-access", request.headers[HttpHeaders.Authorization])
            assertEquals("eq.u", request.url.parameters["user_id"])
            assertEquals("500", request.url.parameters["limit"])
            assertEquals(if (calls == 0) "0" else "500", request.url.parameters["offset"])
            calls++
            val rows = if (calls == 1) (0 until 500).map { """{"id":"$it","payload":null,"updated_at":"2026-09-01T00:00:00Z","deleted_at":"2026-09-01T00:00:00Z"}""" }.joinToString(",", "[", "]") else "[]"
            respond(rows, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        client.auth.importSession(session("u"), autoRefresh = false)
        assertEquals(500, ExamTrackSyncService(client).fetch("u").size)
        assertEquals(2, calls)
        client.close()
    }
    @Test fun ratingWriteUsesAuthenticatedCompareAndSetAndOriginalIdentity() = runBlocking {
        val at = "2026-09-01T00:00:00.000Z"
        val payload = """{"id":"m","attemptId":"a","question":"Q1","category":"Reasoning","explanation":"why","correction":"answer","resolved":false,"createdAt":"$at","updatedAt":"$at","future":42}"""
        val m = requireNotNull(ExamTrackMistakeCodec.decode(payload))
        val reviewed = MistakeScheduler.recordMistakeReview(m, ReviewRating.GOOD, "2026-09-16T00:00:00.000Z", "r")
        val client = client(MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("eq.u", request.url.parameters["user_id"])
            assertEquals("eq.m", request.url.parameters["id"])
            assertEquals("eq.$at", request.url.parameters["updated_at"])
            assertEquals("is.null", request.url.parameters["deleted_at"])
            val body = JSONObject((request.body as TextContent).text)
            assertEquals(reviewed.updatedAt, body.getString("updated_at"))
            assertEquals(42, body.getJSONObject("payload").getInt("future"))
            respond("[{\"id\":\"m\"}]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        client.auth.importSession(session("u"), autoRefresh = false)
        assertTrue(ExamTrackSyncService(client).update("u", RemoteMistakeRow("m", payload, at, null), reviewed))
        client.close()
    }
    @Test fun deleteWriteUsesAuthenticatedCompareAndSet() = runBlocking {
        val at = "2026-09-01T00:00:00.000Z"
        val deletedAt = "2026-09-18T00:00:00.000Z"
        val payload = """{"id":"m","attemptId":"a","question":"Q1","category":"Reasoning","explanation":"why","correction":"answer","resolved":false,"createdAt":"$at","updatedAt":"$at"}"""
        val client = client(MockEngine { request ->
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("eq.u", request.url.parameters["user_id"])
            assertEquals("eq.m", request.url.parameters["id"])
            assertEquals("eq.$at", request.url.parameters["updated_at"])
            assertEquals("is.null", request.url.parameters["deleted_at"])
            val body = JSONObject((request.body as TextContent).text)
            assertTrue(body.has("payload"))
            assertTrue(body.isNull("payload"))
            assertEquals(deletedAt, body.getString("deleted_at"))
            assertEquals(deletedAt, body.getString("updated_at"))
            respond("[{\"id\":\"m\"}]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        client.auth.importSession(session("u"), autoRefresh = false)
        assertTrue(ExamTrackSyncService(client).delete("u", RemoteMistakeRow("m", payload, at, null), deletedAt))
        client.close()
    }
    @Test fun offlineExpiredStartupRetainsIdentityAndSignOutCancelsRetries() = runBlocking {
        val expired = session("u").copy(expiresAt = kotlinx.datetime.Instant.parse("2020-01-01T00:00:00Z"))
        val saved = MemorySessionManager(expired)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val client = client(MockEngine { calls.incrementAndGet(); throw java.io.IOException("Offline") }, saved)
        val lifecycle = ExamTrackSessionLifecycle(client, saved)
        lifecycle.awaitRestoration()
        withTimeout(3000) { client.auth.sessionStatus.first { it is SessionStatus.RefreshFailure } }
        assertEquals("u", lifecycle.restoredUser?.id)
        assertEquals(expired, saved.loadSession())
        lifecycle.cancelRestoration(); client.auth.clearSession()
        val before = calls.get(); delay(100)
        assertEquals(before, calls.get())
        assertNull(saved.loadSession()); assertNull(client.auth.currentUserOrNull())
        client.close()
    }
    @Test fun revokedRefreshSessionIsRemoved() = runBlocking {
        val saved = MemorySessionManager(session("u").copy(expiresAt = kotlinx.datetime.Instant.parse("2020-01-01T00:00:00Z")))
        val client = client(MockEngine { respond("""{"error":"invalid_grant","error_description":"Invalid refresh token"}""",
            HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")) }, saved)
        val lifecycle = ExamTrackSessionLifecycle(client, saved)
        lifecycle.awaitRestoration()
        withTimeout(3000) { client.auth.sessionStatus.first { it is SessionStatus.NotAuthenticated && it.isSignOut } }
        assertNull(saved.loadSession())
        lifecycle.cancelRestoration(); client.close()
    }
    @Test fun expiredAccessTokenRefreshUsesSdkAndRestoresAccount() = runBlocking {
        val saved = MemorySessionManager(session("u").copy(expiresAt = kotlinx.datetime.Instant.parse("2020-01-01T00:00:00Z")))
        val fresh = session("u").copy(accessToken = "refreshed-test-access")
        val client = client(MockEngine { request ->
            assertEquals("refresh_token", request.url.parameters["grant_type"])
            respond(Json.encodeToString(UserSession.serializer(), fresh), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }, saved)
        val lifecycle = ExamTrackSessionLifecycle(client, saved)
        lifecycle.awaitRestoration()
        withTimeout(3000) { client.auth.sessionStatus.first { it is SessionStatus.Authenticated && it.session.accessToken == fresh.accessToken } }
        assertEquals("u", client.auth.currentUserOrNull()?.id)
        lifecycle.cancelRestoration(); client.close()
    }

}
