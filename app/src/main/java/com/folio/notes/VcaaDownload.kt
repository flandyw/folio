package com.folio.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_PDF_BYTES = 100L * 1024 * 1024

/** Every redirect must stay on VCAA HTTPS; a successful response must contain a bounded PDF. */
internal suspend fun downloadVcaaPdf(url: String, target: File,
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
): Unit = withContext(Dispatchers.IO) {
    var source = URL(url)
    try {
        repeat(6) { hop ->
            currentCoroutineContext().ensureActive()
            require(source.protocol == "https" && (source.host == "vcaa.vic.edu.au" || source.host.endsWith(".vcaa.vic.edu.au"))) { "Not a VCAA address" }
            val connection = openConnection(source).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Folio")
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    check(hop < 5) { "VCAA redirected too many times" }
                    val location = connection.getHeaderField("Location") ?: error("VCAA sent an empty redirect")
                    source = URL(source, location)
                } else {
                    check(status in 200..299) { "VCAA answered $status" }
                    check(connection.contentLengthLong <= MAX_PDF_BYTES) { "This file is larger than expected" }
                    var total = 0L
                    connection.inputStream.use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            check(total <= MAX_PDF_BYTES) { "This file is larger than expected" }
                            output.write(buffer, 0, read)
                        }
                    } }
                    val head = ByteArray(5)
                    check(total >= 5 && target.inputStream().use { it.read(head) } == 5 && String(head, Charsets.US_ASCII) == "%PDF-") { "VCAA did not send a PDF" }
                    return@withContext
                }
            } finally { connection.disconnect() }
        }
    } catch (e: Exception) { target.delete(); throw e }
}
