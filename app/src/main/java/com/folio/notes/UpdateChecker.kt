package com.folio.notes

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class FolioUpdate(
    val versionCode: Long,
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val releaseUrl: String
)

/** Carries the source's cooldown across restarts and manual retries. */
internal class UpdateHttpException(
    val code: Int, message: String, val retryAtMillis: Long? = null
) : IOException(message)

/**
 * User-facing message for a GitHub update failure. Never includes response bodies:
 * they can contain IPs or request IDs.
 */
internal fun githubUpdateErrorMessage(
    responseCode: Int,
    rateRemaining: String?,
    rateResetEpochSeconds: Long?,
    retryAfterSeconds: Long?,
    rateLimitedBody: Boolean,
    nowEpochSeconds: Long
): String {
    val rateLimited = rateLimitedBody || rateRemaining?.trim() == "0"
    if (responseCode == 403 && rateLimited || responseCode == 429) {
        val resetInMinutes = rateResetEpochSeconds
            ?.takeIf { it > nowEpochSeconds }
            ?.let { ((it - nowEpochSeconds) + 59) / 60 }
        val retryInMinutes = retryAfterSeconds
            ?.takeIf { it > 0 }
            ?.let { ((it) + 59) / 60 }
        val waitMinutes = resetInMinutes ?: retryInMinutes
        return if (waitMinutes != null && waitMinutes > 0) {
            "GitHub update limit reached · try again in $waitMinutes min"
        } else {
            "GitHub update limit reached · try again later"
        }
    }
    return when (responseCode) {
        403 -> "GitHub refused the update · try again later"
        404 -> "GitHub update not found · try again later"
        in 500..599 -> "GitHub update check failed (HTTP $responseCode) · try again later"
        else -> "GitHub update failed (HTTP $responseCode) · try again later"
    }
}

/** True when the last automatic check is old enough to check again. Manual checks bypass this. */
internal fun shouldAutoUpdateCheck(nowMillis: Long, lastCheckMillis: Long): Boolean {
    if (lastCheckMillis <= 0L) return true
    if (nowMillis < lastCheckMillis) return true // Clock moved backwards: don't block updates.
    return nowMillis - lastCheckMillis >= AUTO_UPDATE_CHECK_INTERVAL_MILLIS
}

internal const val AUTO_UPDATE_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
internal const val UPDATE_FAILURE_RETRY_MILLIS = 60L * 60 * 1000

internal fun updateRetryAtMillis(code: Int, remaining: String?, resetSeconds: Long?, retrySeconds: Long?, rateLimitedBody: Boolean, nowMillis: Long): Long? {
    if (code != 429 && (code != 403 || (!rateLimitedBody && remaining?.trim() != "0"))) return null
    val reset = resetSeconds?.takeIf { it > nowMillis / 1000 }?.times(1000)
    val retry = retrySeconds?.takeIf { it > 0 }?.let { nowMillis + it * 1000 }
    return maxOf(reset ?: 0L, retry ?: 0L).takeIf { it > nowMillis }
        ?: nowMillis + UPDATE_FAILURE_RETRY_MILLIS
}

internal fun releaseVersionCode(tag: String): Long? {
    val parts = tag.removePrefix("v").split('.')
    val legacyTag = parts.size == 3 && parts[0] == "0" && parts[1] == "2"
    if (!legacyTag && parts.size == 3 && parts.all { it.toLongOrNull() != null }) {
        return parts[0].toLong() * 100 + parts[1].toLong() * 10 + parts[2].toLong()
    }
    return if (legacyTag) parts.lastOrNull()?.toLongOrNull() else null
}

internal const val EXPERIMENTAL_UPDATE_URL = "https://folio.flandolf.me/releases/latest.json"

internal fun updateApiUrl(experimental: Boolean): String = if (experimental) EXPERIMENTAL_UPDATE_URL
    else "https://api.github.com/repos/flandyw/folio/releases/latest"

/** Official stable and experimental sources, including GitHub asset redirect hosts. */
internal val TRUSTED_UPDATE_HOSTS = setOf(
    "folio.flandolf.me",
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com"
)

internal fun isTrustedUpdateUrl(url: String): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    return parsed.protocol == "https" && parsed.host.lowercase() in TRUSTED_UPDATE_HOSTS &&
        parsed.userInfo == null && (parsed.port == -1 || parsed.port == 443)
}

data class UpdateDownloadProgress(val percent: Int? = null, val bytesPerSecond: Long = 0, val verifying: Boolean = false)
data class DownloadedUpdate(val update: FolioUpdate, val file: File)

/** Stable GitHub releases by default; the VPS channel is explicitly opt-in. */
class FolioUpdateChecker(private val context: Context) {
    private val directory get() = File(context.noBackupFilesDir, "updates").apply { mkdirs() }
    private val record get() = AtomicFile(File(directory, "ready.json"))

    /** Keep only the verified, still-newer APK; discard interrupted downloads and installed builds. */
    fun restoreDownload(): DownloadedUpdate? {
        val ready = runCatching {
            val json = JSONObject(record.openRead().bufferedReader().use { it.readText() })
            val update = FolioUpdate(json.getLong("versionCode"), json.getString("versionName"),
                json.getString("apkName"), json.getString("apkUrl"), json.getString("checksumUrl"), json.getString("releaseUrl"))
            require(update.apkName.matches(Regex("folio-[A-Za-z0-9._-]+\\.apk")))
            val file = File(directory, "${update.versionCode}-${update.apkName}")
            require(update.versionCode > installedVersionCode() && file.isFile && sha256(file) == json.getString("sha256"))
            DownloadedUpdate(update, file)
        }.getOrNull()
        if (ready == null) record.delete()
        directory.listFiles()?.filter { it != ready?.file && it.name != "ready.json" }?.forEach { it.delete() }
        // Older builds used an evictable cache without a persistent install action.
        File(context.cacheDir, "updates").deleteRecursively()
        return ready
    }

    fun discardDownload(ready: DownloadedUpdate) {
        if (ready.file.exists() && !ready.file.delete()) throw IOException("Couldn’t delete the downloaded update")
        record.delete()
    }

    private fun saveDownload(update: FolioUpdate, checksum: String) {
        val json = JSONObject().put("versionCode", update.versionCode).put("versionName", update.versionName)
            .put("apkName", update.apkName).put("apkUrl", update.apkUrl).put("checksumUrl", update.checksumUrl)
            .put("releaseUrl", update.releaseUrl).put("sha256", checksum)
        val output = record.startWrite()
        try {
            output.write(json.toString().toByteArray())
            record.finishWrite(output)
        } catch (error: Throwable) {
            record.failWrite(output)
            throw error
        }
    }

    fun check(experimental: Boolean = false): FolioUpdate? {
        val installedVersion = installedVersionCode()
        var release = try {
            getJson(updateApiUrl(experimental))
        } catch (error: UpdateHttpException) {
            // No published releases yet: not an error, just nothing to install.
            if (error.code == 404) return null
            throw error
        }
        if (!experimental) stableUpdateMetadataUrl(release)?.let { url ->
            release = withStableUpdateMetadata(release, getJson(url))
        }
        return decodeUpdateRelease(release, installedVersion, experimental)
    }

    /** Downloads to a temporary file, checks SHA-256, then atomically exposes the APK to the UI. */
    fun download(update: FolioUpdate, onProgress: (UpdateDownloadProgress) -> Unit = {}): File {
        val directory = directory
        val target = File(directory, "${update.versionCode}-${update.apkName}")
        val temporary = File(directory, "${update.versionCode}-${update.apkName}.part")
        temporary.delete()
        try {
            val expected = checksum(update).lowercase()
            downloadTo(update.apkUrl, temporary, onProgress)
            onProgress(UpdateDownloadProgress(100, verifying = true))
            val actual = sha256(temporary)
            if (actual != expected) throw IOException("The downloaded update failed its checksum")
            if (target.exists() && !target.delete()) throw IOException("Couldn't replace the previous update")
            if (!temporary.renameTo(target)) throw IOException("Couldn't prepare the update")
            try { saveDownload(update, expected) } catch (error: Throwable) { target.delete(); throw error }
            directory.listFiles()?.filter { it.extension == "apk" && it != target }?.forEach { it.delete() }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun checksum(update: FolioUpdate): String {
        val lines = getText(update.checksumUrl).lineSequence()
        val line = lines.firstOrNull { it.trim().endsWith("  ${update.apkName}") || it.trim().endsWith(" *${update.apkName}") }
            ?: throw IOException("The release checksum does not name its APK")
        return line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: throw IOException("The release checksum is invalid")
    }

    private fun downloadTo(url: String, target: File, onProgress: (UpdateDownloadProgress) -> Unit) {
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var read: Int
                    var lastSampleAt = SystemClock.elapsedRealtime()
                    var lastSampleBytes = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - lastSampleAt
                        if (elapsed >= 500) {
                            onProgress(UpdateDownloadProgress(if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else null,
                                (copied - lastSampleBytes) * 1000 / elapsed))
                            lastSampleAt = now
                            lastSampleBytes = copied
                        }
                    }
                }
            }
            onProgress(UpdateDownloadProgress(100, verifying = true))
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject = JSONObject(getText(url))

    private fun getText(url: String): String {
        val connection = open(url)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        requireTrustedDownload(url)
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                // Every redirect must stay on an official update host.
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                if (URL(current).host == "api.github.com") setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Folio/${installedVersionName()}")
            }
            connection.connect()
            val code = connection.responseCode
            if (code in 300..399 && redirects < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                val next = location?.let { runCatching { URL(URL(current), it).toString() }.getOrNull() }
                if (next != null && isTrustedUpdateUrl(next)) {
                    current = next
                    redirects++
                    continue
                }
                throw UpdateHttpException(code, "Update redirect failed (HTTP $code) · try again later")
            }
            if (code in 200..299) return connection
            val error = readUpdateError(connection, code)
            connection.disconnect()
            throw error
        }
    }

    private fun readUpdateError(connection: HttpURLConnection, code: Int): UpdateHttpException {
        val remaining = connection.getHeaderField("X-RateLimit-Remaining")
        val reset = connection.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()
            ?: connection.getHeaderField("X-Ratelimit-Reset")?.trim()?.toLongOrNull()
        val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
        val body = try {
            connection.errorStream?.bufferedReader()?.use { reader ->
                val buffer = CharArray(ERROR_BODY_LIMIT)
                val read = reader.read(buffer)
                if (read > 0) String(buffer, 0, read) else ""
            }.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val rateLimitedBody = body.contains("rate limit", ignoreCase = true) ||
            body.contains("abuse", ignoreCase = true)
        val now = System.currentTimeMillis()
        val message = githubUpdateErrorMessage(
            responseCode = code,
            rateRemaining = remaining,
            rateResetEpochSeconds = reset,
            retryAfterSeconds = retryAfter,
            rateLimitedBody = rateLimitedBody,
            nowEpochSeconds = now / 1000
        )
        return UpdateHttpException(code,
            if (connection.url.host == "folio.flandolf.me") message.replace("GitHub", "Folio experimental server") else message,
            updateRetryAtMillis(code, remaining, reset, retryAfter, rateLimitedBody, now))
    }

    private fun requireTrustedDownload(url: String) {
        require(isTrustedUpdateUrl(url)) { "Untrusted update URL" }
    }

    private companion object {
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val ERROR_BODY_LIMIT = 4096
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}
