package com.folio.notes.mistakes

import io.github.jan.supabase.exceptions.RestException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ExamTrackSyncErrorTests {
    private fun http(status: Int) = RestException("private-payload", "Bearer private-token", status, "private-request")

    @Test fun authenticationAndDatabaseFailuresAreNotReportedAsOffline() {
        assertTrue(examTrackSyncError(http(401)).contains("sign out and sign in"))
        assertTrue(examTrackSyncError(http(403)).contains("access denied"))
        assertTrue(examTrackSyncError(http(404)).contains("backend configuration"))
        assertTrue(examTrackSyncError(http(400)).contains("HTTP 400"))
    }

    @Test fun wrappedNetworkFailuresExplainTheActualProblem() {
        assertTrue(examTrackSyncError(Exception(UnknownHostException("private-host"))).startsWith("Offline"))
        assertTrue(examTrackSyncError(Exception(SocketTimeoutException())).contains("timed out"))
        assertTrue(examTrackSyncError(SSLHandshakeException("private-host")).contains("secure connection failed"))
        assertTrue(examTrackSyncError(http(503)).contains("server error (503)"))
        assertTrue(examTrackSyncError(http(429)).contains("limiting requests"))
    }

    @Test fun sdkRequestDetailsNeverReachTheUser() {
        for (error in listOf(http(401), http(400), IllegalStateException("Bearer private-token"))) {
            val message = examTrackSyncError(error)
            assertFalse(message.contains("private"))
            assertFalse(message.contains("Bearer"))
            assertTrue(message.contains("changes kept on this device"))
        }
    }
}
