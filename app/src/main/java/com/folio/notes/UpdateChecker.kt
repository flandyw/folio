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

/** Checks and downloads signed Folio releases from the project's official GitHub repository. */
class FolioUpdateChecker(private val context: Context) {
    private val apiUrl = "https://api.github.com/repos/flandyw/folio/releases/latest"

    fun check(): FolioUpdate? {
        val installedVersion = installedVersionCode()
        val release = getJson(apiUrl)
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
        return line.trim().substringBefore(Regex("\\s+")).takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
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
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Folio/${installedVersionName()}")
            connect()
            if (responseCode !in 200..299) {
                disconnect()
                throw IOException("GitHub returned HTTP $responseCode")
            }
        }
    }

    private fun requireTrustedDownload(url: String) {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.host in setOf("api.github.com", "github.com")) { "Untrusted update URL" }
    }

    private companion object {
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
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
