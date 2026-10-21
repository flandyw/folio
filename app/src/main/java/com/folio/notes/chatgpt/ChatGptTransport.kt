package com.folio.notes.chatgpt

import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

internal object ChatGptTransport {
    fun client(): OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true).build()

    // Allow connection/address fallback before sending, without replaying a code,
    // refresh token or inference request after transmission has started.
    fun once(body: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
    }

    fun failure(error: IOException, host: String? = null): ChatGptFailure {
        val detail = when (error) {
            is UnknownHostException -> "DNS lookup failed"
            is SSLException -> "the secure connection failed"
            is SocketTimeoutException, is InterruptedIOException -> "the connection timed out"
            is ConnectException -> "the server connection failed"
            is EOFException -> "the server closed the connection early"
            is ProtocolException -> "the server connection protocol failed"
            is SocketException -> "the connection was interrupted"
            else -> "the network request failed"
        }
        // Include only our known service hostname, never an exception's raw message.
        val service = host?.takeIf { it == "auth.openai.com" || it == "api.openai.com" }
        return ChatGptFailure("Could not reach ${service ?: "ChatGPT"}: $detail. Check your connection and try again.")
    }
}
