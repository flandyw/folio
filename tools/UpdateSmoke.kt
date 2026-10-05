package com.folio.notes

import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    check(updateApiUrl(false) == "https://api.github.com/repos/flandyw/folio/releases/latest")
    check(updateApiUrl(true) == "https://folio.flandolf.me/releases/latest.json")
    check(!AppPrefs.DEFAULT_EXPERIMENTAL_UPDATES)
    check(AppPrefs.lastUpdateCheckKey(false) != AppPrefs.lastUpdateCheckKey(true))
    check(AppPrefs.updateRetryAtKey(false) != AppPrefs.updateRetryAtKey(true))
    check(AppPrefs.lastUpdateCheckKey(false) == AppPrefs.LAST_UPDATE_CHECK)
    check(AppPrefs.updateRetryAtKey(false) == AppPrefs.UPDATE_RETRY_AT)

    val release = JSONObject("""{
        "tag_name":"v2.1.7-exp.1", "name":"Folio 2.1.7-exp.1", "version_code":2170001,
        "version_name":"2.1.7-exp.1",
        "assets":[
            {"name":"folio-2.1.7-exp.1.apk", "browser_download_url":"https://folio.flandolf.me/releases/v2.1.7-exp.1/folio-2.1.7-exp.1.apk"},
            {"name":"SHA256SUMS", "browser_download_url":"https://folio.flandolf.me/releases/v2.1.7-exp.1/SHA256SUMS"}
        ]
    }""")
    check(decodeUpdateRelease(release, 217, true)?.versionCode == 2170001L) // Migration from installed legacy app.
    check(decodeUpdateRelease(release, 2170000, true)?.versionName == "2.1.7-exp.1")
    check(decodeUpdateRelease(release, 2170001, true) == null)
    check(decodeUpdateRelease(release, 2170002, true) == null)
    release.put("version_code", 2170002).put("version_name", "2.1.7-exp.2")
    check(decodeUpdateRelease(release, 2170001, true)?.versionCode == 2170002L)
    release.remove("version_code")
    check(runCatching { decodeUpdateRelease(release, 217, true) }.isFailure)
    release.put("version_code", 2170001).put("version_name", "2.1.7-exp.1")
    release.put("draft", true)
    check(decodeUpdateRelease(release, 217, true) == null)
    release.put("draft", false)

    val github = JSONObject("""{
        "tag_name":"v2.1.8", "name":"Folio 2.1.8",
        "assets":[
            {"name":"folio-2.1.8.apk", "browser_download_url":"https://github.com/flandyw/folio/releases/download/v2.1.8/folio-2.1.8.apk"},
            {"name":"SHA256SUMS", "browser_download_url":"https://github.com/flandyw/folio/releases/download/v2.1.8/SHA256SUMS"}
        ]
    }""")
    check(stableUpdateMetadataUrl(github) == null)
    check(decodeUpdateRelease(github, 217, false)?.versionCode == 218L) // Old GitHub releases retain old codes.
    check(decodeUpdateRelease(github, 2170001, false) == null)
    check(releaseVersionCode("v0.2.83") == 83L)
    val metadataUrl = "https://github.com/flandyw/folio/releases/download/v2.1.8/update.json"
    github.getJSONArray("assets").put(JSONObject().put("name", "update.json").put("browser_download_url", metadataUrl))
    check(stableUpdateMetadataUrl(github) == metadataUrl)
    val metadata = JSONObject("""{"schema_version":1,"tag_name":"v2.1.8","version_name":"2.1.8","version_code":2180000}""")
    val stable = withStableUpdateMetadata(github, metadata)
    check(decodeUpdateRelease(stable, 2179999, false)?.versionCode == 2180000L) // Next stable beats the whole previous block.
    check(decodeUpdateRelease(stable, 2180000, false) == null)
    check(!github.has("version_code")) // Hydration does not mutate the original response.
    metadata.put("version_code", 0)
    check(runCatching { withStableUpdateMetadata(github, metadata) }.isFailure)
    metadata.put("version_code", 2180000).put("tag_name", "v2.1.9")
    check(runCatching { withStableUpdateMetadata(github, metadata) }.isFailure)
    github.getJSONArray("assets").getJSONObject(2).put("browser_download_url", "https://evil.test/update.json")
    check(runCatching { stableUpdateMetadataUrl(github) }.isFailure)

    listOf("http://folio.flandolf.me/releases/latest.json", "https://folio.flandolf.me.evil.test/a.apk",
        "https://attacker@folio.flandolf.me/a.apk", "https://folio.flandolf.me:8443/a.apk").forEach {
        check(!isTrustedUpdateUrl(it)) { "Trusted unsafe URL $it" }
    }
    listOf("https://folio.flandolf.me/releases/latest.json", "https://github.com/flandyw/folio/releases",
        "https://release-assets.githubusercontent.com/a.apk").forEach { check(isTrustedUpdateUrl(it)) }
    release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.test/a.apk")
    check(runCatching { decodeUpdateRelease(release, 217, true) }.isFailure)
    check(updateRetryAtMillis(429, null, null, 60, false, 1000) == 61000L)
    check(updateRetryAtMillis(500, null, null, null, false, 1000) == null)

    if (args.isNotEmpty()) {
        val live = JSONObject(File(args[0]).readText())
        val update = decodeUpdateRelease(live, 0, true) ?: error("Live manifest offered no update")
        check(update.apkUrl.startsWith("https://folio.flandolf.me/releases/"))
        check(decodeUpdateRelease(live, update.versionCode, true) == null)
        println("Live experimental manifest: ${update.versionName} (code ${update.versionCode}) passed.")
    }
    println("Update channels: source selection, parser, version ordering, host trust and independent cooldowns passed.")
}
