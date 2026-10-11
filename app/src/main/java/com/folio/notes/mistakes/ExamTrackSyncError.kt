package com.folio.notes.mistakes

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import com.folio.notes.sync.FocalSessionRefreshingException
import com.folio.notes.sync.FocalSessionRequiredException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Mistake sync talks to Focal, which absorbed the ExamTrack project, so the banner never
 * names a backend the user no longer has an account with. The banner keys its icon and
 * colour off these prefixes instead of the wording, so the copy can change freely.
 */
internal const val SYNC_OFFLINE = "Offline"
internal const val SYNC_FOCAL = "Focal"

/** True when [status] is a sync problem the account sheet should offer to retry. */
internal fun isSyncTrouble(status: String): Boolean = status.startsWith(SYNC_OFFLINE) || status.startsWith(SYNC_FOCAL)

/** Never display SDK exception messages: they can include request headers or payloads. */
internal fun focalSyncError(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val response = causes.filterIsInstance<RestException>().firstOrNull()
    val reason = when {
        causes.any { it is FocalSessionRefreshingException } -> "$SYNC_FOCAL login is reconnecting · sync will retry"
        causes.any { it is FocalSessionRequiredException } -> "$SYNC_FOCAL session ended or the account changed · sign in again"
        response != null -> when (response.statusCode) {
            401 -> "$SYNC_FOCAL session expired or rejected · sign out and sign in again"
            403 -> "$SYNC_FOCAL access denied · check the account and database permissions"
            404 -> "$SYNC_FOCAL sync tables unavailable · check the backend configuration"
            429 -> "$SYNC_FOCAL is limiting requests · sync will retry"
            in 500..599 -> "$SYNC_FOCAL server error (${response.statusCode}) · sync will retry"
            else -> "$SYNC_FOCAL rejected sync (HTTP ${response.statusCode}) · check backend compatibility"
        }
        // The request never left the device: the body could not be encoded, so a retry
        // cannot help and only a newer build can.
        causes.any { it is SerializationException } ->
            "$SYNC_FOCAL could not encode the sync request · install the latest Folio and try again"
        causes.any { it is UnknownHostException } -> "$SYNC_OFFLINE · cannot reach $SYNC_FOCAL · check your connection"
        causes.any { it is SocketTimeoutException || it is HttpRequestTimeoutException } -> "$SYNC_FOCAL connection timed out · sync will retry"
        causes.any { it is SSLException } -> "$SYNC_FOCAL secure connection failed · check device date and network"
        causes.any { it is IOException } -> "$SYNC_FOCAL connection or local storage failed · sync will retry"
        // SDK 3.0.3 wraps transport errors without retaining their original cause.
        causes.any { it is HttpRequestException } -> "$SYNC_OFFLINE · connection to $SYNC_FOCAL interrupted · sync will retry"
        else -> "$SYNC_FOCAL sync failed (${error.javaClass.simpleName}) · retry from Account and sync"
    }
    return reason
}

internal fun mistakeSyncError(error: Throwable): String = "${focalSyncError(error)} · changes kept on this device"
