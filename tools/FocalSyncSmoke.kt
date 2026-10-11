package com.folio.notes.sync

import com.folio.notes.mistakes.focalSyncError
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

fun main() = runBlocking {
    val calls = AtomicInteger()
    var statusCode = 200
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/rest/v1/rpc/sync_read_changes") { exchange ->
        val count = calls.incrementAndGet()
        val status = if (statusCode == -1 && count == 1) 503 else if (statusCode == -1) 200 else statusCode
        val body = if (status == 200) """{"mode":"changes","head":0,"floor":0,"rows":[],"server_now":"2026-10-11T00:00:00Z"}"""
            else """{"code":"42501","message":"Synthetic failure"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    val client = createSupabaseClient("http://127.0.0.1:${server.address.port}", "dummy-public-key") {
        defaultLogLevel = LogLevel.NONE
        install(Auth) {
            autoLoadFromStorage = false
            autoSaveToStorage = false
            alwaysAutoRefresh = false
            enableLifecycleCallbacks = false
            codeVerifierCache = MemoryCodeVerifierCache()
            sessionManager = object : SessionManager {
                override suspend fun saveSession(session: UserSession) {}
                override suspend fun loadSession(): UserSession? = null
                override suspend fun deleteSession() {}
            }
        }
        install(Postgrest)
    }
    val userId = "11111111-1111-4111-8111-111111111111"
    val user = Json.decodeFromString(UserInfo.serializer(), """{"id":"$userId","aud":"authenticated","role":"authenticated"}""")
    fun session(expired: Boolean = false) = UserSession("test-access-token", "test-refresh-token", expiresIn = 3600,
        tokenType = "bearer", user = user, expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + if (expired) -1000 else 3600000))
    val remote = SupabaseSyncRemote(client)
    try {
        client.auth.awaitInitialization()
        client.auth.importSession(session(), autoRefresh = false)
        check(remote.read(userId, 0).changes.isEmpty() && calls.get() == 1)
        calls.set(0)
        statusCode = -1
        remote.read(userId, 0)
        check(calls.get() == 2) { "A transient 503 should recover" }
        calls.set(0)
        statusCode = 403
        val denied = runCatching { remote.read(userId, 0) }.exceptionOrNull()!!
        check(calls.get() == 1 && focalSyncError(denied).contains("access denied"))
        statusCode = 503
        calls.set(0)
        check(runCatching { remote.read(userId, 0) }.isFailure)
        check(calls.get() == 3) { "Retries must stop after three attempts" }
        statusCode = 200
        calls.set(0)
        client.auth.importSession(session(expired = true), autoRefresh = false)
        val waiting = async { remote.read(userId, 0) }
        delay(100)
        check(!waiting.isCompleted && calls.get() == 0) { "Expired tokens must never reach the RPC" }
        client.auth.importSession(session(), autoRefresh = false)
        waiting.await()
        check(calls.get() == 1)
        calls.set(0)
        val field = client.auth.javaClass.getDeclaredField("_sessionStatus").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val statuses = field.get(client.auth) as MutableStateFlow<SessionStatus>
        statuses.value = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(java.io.IOException("offline")))
        check(client.auth.currentUserOrNull() == null)
        val recovering = async { remote.read(userId, 0) }
        delay(100)
        check(!recovering.isCompleted && calls.get() == 0)
        client.auth.importSession(session(), autoRefresh = false)
        recovering.await()
        check(calls.get() == 1)
        calls.set(0)
        check(runCatching { remote.read("other-account", 0) }.exceptionOrNull() is FocalSessionRequiredException)
        check(calls.get() == 0)
        statuses.value = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(java.io.IOException("offline")))
        val cancelled = launch { remote.read(userId, 0) }
        delay(50)
        cancelled.cancelAndJoin()
        check(calls.get() == 0 && cancelled.isCancelled)
        val pending = runCatching { remote.read(userId, 0) }.exceptionOrNull()
        check(pending is FocalSessionRefreshingException && calls.get() == 0)
        check(focalSyncError(pending).contains("reconnecting"))
        client.auth.clearSession()
        check(runCatching { remote.read(userId, 0) }.exceptionOrNull() is FocalSessionRequiredException)
        check(calls.get() == 0)
        val wrapped = HttpRequestException("secret request detail", HttpRequestBuilder())
        check(focalReadCanRetry(wrapped))
        check(focalSyncError(wrapped).startsWith("Offline") && !focalSyncError(wrapped).contains("secret"))
        for (status in listOf(401,403,404,400)) check(!focalReadCanRetry(RestException("secret", null, status, "secret")))
        for (status in listOf(408,429,500,503)) check(focalReadCanRetry(RestException("secret", null, status, "secret")))
        println("Focal sync recovery checks passed: transient retry, permanent refusal, expired token, refresh failure, account isolation, sign-out, cancellation and safe diagnostics")
    } finally {
        client.close()
        server.stop(0)
    }
}
