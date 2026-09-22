package com.folio.notes.mistakes

import io.github.jan.supabase.exceptions.RestException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Never display SDK exception messages: they can include request headers or payloads. */
internal fun examTrackSyncError(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val response = causes.filterIsInstance<RestException>().firstOrNull()
    val reason = when {
        response != null -> when (response.statusCode) {
            401 -> "ExamTrack session expired or rejected · sign out and sign in again"
            403 -> "ExamTrack access denied · check the account and database permissions"
            404 -> "ExamTrack sync tables unavailable · check the backend configuration"
            429 -> "ExamTrack is limiting requests · sync will retry"
            in 500..599 -> "ExamTrack server error (${response.statusCode}) · sync will retry"
            else -> "ExamTrack rejected sync (HTTP ${response.statusCode}) · check backend compatibility"
        }
        causes.any { it is UnknownHostException } -> "Offline · cannot reach ExamTrack · check your connection"
        causes.any { it is SocketTimeoutException } -> "ExamTrack connection timed out · sync will retry"
        causes.any { it is SSLException } -> "ExamTrack secure connection failed · check device date and network"
        causes.any { it is IOException } -> "ExamTrack connection or local storage failed · sync will retry"
        else -> "ExamTrack sync failed (${error.javaClass.simpleName}) · retry from Account and sync"
    }
    return "$reason · changes kept on this device"
}
