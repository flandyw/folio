package com.folio.notes

import android.content.Context
import android.os.Build
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

/** Thrown for non-2xx GitHub responses so callers can distinguish "no release" from errors. */
internal class GithubHttpException(val code: Int, message: String) : IOException(message)

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

/** Hosts GitHub uses for the releases API, release pages and redirected asset bytes. */
internal val TRUSTED_UPDATE_HOSTS = setOf(
    "api.github.com",
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com"
)

internal fun isTrustedUpdateUrl(url: String): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    return parsed.protocol == "https" && parsed.host.lowercase() in TRUSTED_UPDATE_HOSTS
}

/** Checks and downloads signed Folio releases from the project's official GitHub repository. */
class FolioUpdateChecker(private val context: Context) {
    private val apiUrl = "https://api.github.com/repos/flandyw/folio/releases/latest"

    fun check(): FolioUpdate? {
        val installedVersion = installedVersionCode()
        val release = try {
            getJson(apiUrl)
        } catch (error: GithubHttpException) {
            // No published releases yet: not an error, just nothing to install.
            if (error.code == 404) return null
            throw error
        }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null

        val versionName = release.optString("tag_name").removePrefix("v")
        val versionCode = versionName.substringAfterLast('.', "").toLongOrNull()
            ?: throw IOException("The latest release has an invalid version")
        if (versionCode <= installedVersion) return null

        var apkName: String? = null
        var apkUrl: String? = null
        var checksumUrl: String? = null
        release.optJSONArray("assets")?.let { assets ->
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                when {
                    name.endsWith(".apk", ignoreCase = true) && name.startsWith("folio-") -> {
                        apkName = name
                        apkUrl = url
                    }
                    name == "SHA256SUMS" -> checksumUrl = url
                }
            }
        }
        val apk = apkUrl ?: throw IOException("The latest release has no APK")
        val sums = checksumUrl ?: throw IOException("The latest release has no checksum")
        val name = apkName ?: throw IOException("The latest release has no APK name")
        require(name.matches(Regex("folio-[A-Za-z0-9._-]+\\.apk"))) { "Invalid update filename" }
        requireTrustedDownload(apk)
        requireTrustedDownload(sums)
        return FolioUpdate(
            versionCode = versionCode,
            versionName = versionName,
            apkName = name,
            apkUrl = apk,
            checksumUrl = sums,
            releaseUrl = release.optString("html_url", "https://github.com/flandyw/folio/releases")
        )
    }

    /** Downloads to a temporary file, checks SHA-256, then atomically exposes the APK to the UI. */
    fun download(update: FolioUpdate, onProgress: (Int) -> Unit = {}): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, update.apkName)
        val temporary = File(directory, "${update.apkName}.part")
        temporary.delete()
        try {
            val expected = checksum(update).lowercase()
            downloadTo(update.apkUrl, temporary, onProgress)
            val actual = sha256(temporary)
            if (actual != expected) throw IOException("The downloaded update failed its checksum")
            if (target.exists() && !target.delete()) throw IOException("Couldn't replace the previous update")
            if (!temporary.renameTo(target)) throw IOException("Couldn't prepare the update")
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

    private fun downloadTo(url: String, target: File, onProgress: (Int) -> Unit) {
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var read: Int
                    var lastProgress = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied > MAX_APK_BYTES) throw IOException("The update is unexpectedly large")
                        if (total > 0) {
                            val progress = (copied * 100 / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) { lastProgress = progress; onProgress(progress) }
                        }
                    }
                }
            }
            onProgress(100)
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
                // Follow redirects manually so every hop stays on GitHub-owned hosts.
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
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
                throw GithubHttpException(code, "GitHub update failed (HTTP $code) · try again later")
            }
            if (code in 200..299) return connection
            val message = readGithubError(connection, code)
            connection.disconnect()
            throw GithubHttpException(code, message)
        }
    }

    private fun readGithubError(connection: HttpURLConnection, code: Int): String {
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
        return githubUpdateErrorMessage(
            responseCode = code,
            rateRemaining = remaining,
            rateResetEpochSeconds = reset,
            retryAfterSeconds = retryAfter,
            rateLimitedBody = rateLimitedBody,
            nowEpochSeconds = System.currentTimeMillis() / 1000
        )
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
