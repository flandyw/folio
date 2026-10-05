package com.folio.notes

import org.json.JSONObject
import java.io.IOException

internal const val STABLE_UPDATE_METADATA_NAME = "update.json"

/** New GitHub releases carry the APK's code separately from their display tag. */
internal fun stableUpdateMetadataUrl(release: JSONObject): String? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
    val assets = release.optJSONArray("assets") ?: return null
    var result: String? = null
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") != STABLE_UPDATE_METADATA_NAME) continue
        if (result != null) throw IOException("The release has duplicate update metadata")
        result = asset.optString("browser_download_url")
        require(isTrustedUpdateUrl(result)) { "Untrusted update metadata URL" }
    }
    return result
}

internal fun withStableUpdateMetadata(release: JSONObject, metadata: JSONObject): JSONObject {
    val tag = release.optString("tag_name")
    val code = metadata.optLong("version_code", -1L)
    if (metadata.optInt("schema_version") != 1 || metadata.optString("tag_name") != tag ||
        metadata.optString("version_name") != tag.removePrefix("v") || code !in 1..2_100_000_000L) {
        throw IOException("The release update metadata is invalid")
    }
    return JSONObject(release.toString())
        .put("version_code", code)
        .put("version_name", metadata.getString("version_name"))
}

/** Shared metadata parser, exercised on the JVM against the live experimental manifest. */
internal fun decodeUpdateRelease(release: JSONObject, installedVersionCode: Long, experimental: Boolean): FolioUpdate? {
    if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null

    val releaseTag = release.optString("tag_name").removePrefix("v")
    // Explicit codes come from the VPS or GitHub's update.json. Only older GitHub
    // releases without metadata use the historical tag-to-commit-count mapping.
    val versionCode = if (release.has("version_code")) release.optLong("version_code", -1L).takeIf { it in 1..2_100_000_000L }
        else if (!experimental) releaseVersionCode(releaseTag) else null
    val validVersionCode = versionCode
        ?: throw IOException("The latest release has an invalid version")
    val versionName = release.optString("version_name").ifBlank {
        release.optString("name").removePrefix("Folio ").ifBlank { releaseTag }
    }
    if (!versionName.matches(Regex("\\d+\\.\\d+\\.\\d+(?:-exp\\.[1-9]\\d{0,3})?"))) {
        throw IOException("The latest release has an invalid version name")
    }
    if (validVersionCode <= installedVersionCode) return null

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
    require(isTrustedUpdateUrl(apk) && isTrustedUpdateUrl(sums)) { "Untrusted update URL" }
    return FolioUpdate(
        versionCode = validVersionCode,
        versionName = versionName,
        apkName = name,
        apkUrl = apk,
        checksumUrl = sums,
        releaseUrl = release.optString("html_url", if (experimental) "https://folio.flandolf.me/releases/" else "https://github.com/flandyw/folio/releases")
    )
}
