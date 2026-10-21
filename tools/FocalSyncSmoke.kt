package com.folio.notes.sync

import com.folio.notes.mistakes.sync.ExamTrackSessionLifecycle
import com.folio.notes.mistakes.sync.FocalAccountStatus
import com.folio.notes.mistakes.sync.focalRefreshRequiresSignIn
import com.folio.notes.mistakes.sync.focalSyncError
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun main() = runBlocking {
    val calls = AtomicInteger()
    val refreshCalls = AtomicInteger()
    val refreshResponses = AtomicInteger()
    val refreshStatus = AtomicInteger(200)
    val refreshDelay = AtomicLong()
    val acceptedToken = AtomicReference("rotated-access-token")
    val userId = "11111111-1111-4111-8111-111111111111"
    var statusCode = 200
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/rest/v1/rpc/sync_read_changes") { exchange ->
        val count = calls.incrementAndGet()
        val status = if (statusCode == -2) {
            if (exchange.requestHeaders.getFirst("Authorization") == "Bearer ${acceptedToken.get()}") 200 else 401
        } else if (statusCode == -1 && count == 1) 503 else if (statusCode == -1) 200 else statusCode
        val body = if (status == 200) """{"mode":"changes","head":0,"floor":0,"rows":[],"server_now":"2026-10-11T00:00:00Z"}"""
            else """{"code":"42501","message":"Synthetic failure"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.createContext("/auth/v1/token") { exchange ->
        val count = refreshCalls.incrementAndGet()
        Thread.sleep(refreshDelay.get())
        val status = refreshStatus.get()
        val body = if (status == 200) {
            val token = "rotated-access-token-$count"
            acceptedToken.set(token)
            """{"access_token":"$token","refresh_token":"rotated-refresh-token-$count","expires_in":3600,"expires_at":${System.currentTimeMillis() / 1000 + 3600},"token_type":"bearer","user":{"id":"$userId","aud":"authenticated","role":"authenticated"}}"""
        } else """{"error":"invalid_grant","error_description":"Synthetic refresh failure"}"""
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        refreshResponses.incrementAndGet()
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
        check(!focalReadCanRetry(pending))
        client.auth.clearSession()
        check(runCatching { remote.read(userId, 0) }.exceptionOrNull() is FocalSessionRequiredException)
        check(calls.get() == 0)
        val wrapped = HttpRequestException("secret request detail", HttpRequestBuilder())
        check(focalReadCanRetry(wrapped))
        check(focalSyncError(wrapped).startsWith("Offline") && !focalSyncError(wrapped).contains("secret"))
        for (status in listOf(401,403,404,400)) check(!focalReadCanRetry(RestException("secret", null, status, "secret")))
        for (status in listOf(408,429,500,503)) check(focalReadCanRetry(RestException("secret", null, status, "secret")))
        for (status in listOf(400,401,422)) check(focalRefreshRequiresSignIn(RestException("secret", null, status, "secret")))
        for (status in listOf(403,404,408,429,500,503)) check(!focalRefreshRequiresSignIn(RestException("secret", null, status, "secret")))
        println("Focal RPC retry, cancellation and safe-diagnostic checks passed")

        class SavedSession(var saved: UserSession?, val unreadable: Boolean = false) : SessionManager {
            var deletes = 0
            var refuseSave = false
            var refuseRead = false
            val saveFailures = AtomicInteger()
            override suspend fun saveSession(session: UserSession) {
                if (refuseSave) { saveFailures.incrementAndGet(); throw java.io.IOException("storage unavailable") }
                saved = session
            }
            override suspend fun loadSession(): UserSession? {
                if (unreadable) throw java.security.GeneralSecurityException("key unavailable")
                if (refuseRead) throw java.io.IOException("storage unavailable")
                return saved
            }
            override suspend fun deleteSession() { saved = null; deletes++ }
        }
        fun ownedClient(saved: SessionManager) = createSupabaseClient("http://127.0.0.1:${server.address.port}", "dummy-public-key") {
            defaultLogLevel = LogLevel.NONE
            install(Auth) {
                autoLoadFromStorage = false
                autoSaveToStorage = false
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
                codeVerifierCache = MemoryCodeVerifierCache()
                sessionManager = saved
            }
            install(Postgrest)
        }
        val saved = SavedSession(session(expired = true))
        var owned = ownedClient(saved)
        refreshStatus.set(429)
        var lifecycle = ExamTrackSessionLifecycle(owned, saved)
        try {
            withTimeout(5_000) {
                lifecycle.state.first { it.status == FocalAccountStatus.RECONNECTING }
                while (refreshCalls.get() == 0 || refreshResponses.get() == 0) delay(10)
            }
            check(saved.saved?.refreshToken == "test-refresh-token" && saved.deletes == 0) { "Rate limiting must keep the saved login" }
            refreshStatus.set(200)
            withTimeout(8_000) { lifecycle.state.first { it.status == FocalAccountStatus.CONNECTED } }
            check(saved.saved?.refreshToken?.startsWith("rotated-refresh-token-") == true)
            lifecycle.awaitSession(userId)
            println("Focal rate-limit recovery and rotated-token persistence checks passed")

            // A process restart/APK update uses the newly persisted token without a new login.
            lifecycle.close()
            owned.close()
            val beforeRestart = refreshCalls.get()
            owned = ownedClient(saved)
            lifecycle = ExamTrackSessionLifecycle(owned, saved)
            lifecycle.awaitRestoration()
            lifecycle.awaitSession(userId)
            check(lifecycle.state.value.status == FocalAccountStatus.CONNECTED && refreshCalls.get() == beforeRestart)
            val ownedRemote = SupabaseSyncRemote(owned, lifecycle)
            statusCode = -2
            acceptedToken.set("force-refresh")
            refreshDelay.set(100)
            val beforeConcurrent = refreshCalls.get()
            coroutineScope { List(3) { async { ownedRemote.read(userId, 0) } }.awaitAll() }
            check(refreshCalls.get() == beforeConcurrent + 1) { "Concurrent 401s must share one token exchange" }
            println("Focal restart restoration and shared-refresh checks passed")

            // A refused disk write retries persistence of the rotated token, not the exchange.
            saved.refuseSave = true
            acceptedToken.set("force-refresh")
            val unsaved = async { ownedRemote.read(userId, 0) }
            withTimeout(5_000) {
                while (saved.saveFailures.get() == 0) delay(10)
            }
            check(lifecycle.state.value.status == FocalAccountStatus.RECONNECTING)
            val beforeSaveRetry = refreshCalls.get()
            saved.refuseSave = false
            withTimeout(8_000) { lifecycle.state.first { it.status == FocalAccountStatus.CONNECTED } }
            unsaved.await()
            check(refreshCalls.get() == beforeSaveRetry && saved.saved?.accessToken == owned.auth.currentAccessTokenOrNull())
            println("Focal rotated-token storage retry check passed")

            acceptedToken.set("force-refresh")
            refreshStatus.set(400)
            check(runCatching { ownedRemote.read(userId, 0) }.exceptionOrNull() is FocalSessionRequiredException)
            check(lifecycle.state.value.needsSignIn && lifecycle.state.value.user?.id == userId && saved.saved == null)
            refreshStatus.set(200)
            lifecycle.signIn("test@example.com", "test-password")
            check(lifecycle.state.value.status == FocalAccountStatus.CONNECTED && saved.saved != null)
            calls.set(0)
            check(runCatching { ownedRemote.read("other-account", 0) }.exceptionOrNull() is FocalSessionRequiredException)
            check(calls.get() == 0)

            acceptedToken.set("force-refresh")
            refreshDelay.set(250)
            val beforeCancel = refreshCalls.get()
            val leavingScreen = launch { ownedRemote.read(userId, 0) }
            withTimeout(5_000) { while (refreshCalls.get() == beforeCancel) delay(10) }
            leavingScreen.cancelAndJoin()
            lifecycle.signOut()
            delay(300)
            check(lifecycle.state.value.status == FocalAccountStatus.SIGNED_OUT && saved.saved == null && owned.auth.currentSessionOrNull() == null)
        } finally { lifecycle.close(); owned.close() }

        val unreadable = SavedSession(session(), unreadable = true)
        val brokenClient = ownedClient(unreadable)
        val brokenLifecycle = ExamTrackSessionLifecycle(brokenClient, unreadable)
        try {
            brokenLifecycle.awaitRestoration()
            check(brokenLifecycle.state.value.needsSignIn && unreadable.deletes == 0 && unreadable.saved != null)
        } finally { brokenLifecycle.close(); brokenClient.close() }
        val temporarilyUnavailable = SavedSession(session()).also { it.refuseRead = true }
        val retryClient = ownedClient(temporarilyUnavailable)
        val retryLifecycle = ExamTrackSessionLifecycle(retryClient, temporarilyUnavailable)
        try {
            retryLifecycle.awaitRestoration()
            check(retryLifecycle.state.value.status == FocalAccountStatus.RECONNECTING && temporarilyUnavailable.deletes == 0)
            temporarilyUnavailable.refuseRead = false
            withTimeout(8_000) { retryLifecycle.state.first { it.status == FocalAccountStatus.CONNECTED } }
        } finally { retryLifecycle.close(); retryClient.close() }
        println("Focal sync recovery checks passed: HTTP retries, restart restoration, rate limits, shared refresh, durable rotated tokens, revoked login, account isolation, sign-out and cancellation")
    } finally {
        client.close()
        server.stop(0)
    }
}
