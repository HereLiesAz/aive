package com.hereliesaz.aive

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The newest GitHub release that carries an Android APK, and the page the user opens to get it. */
internal data class GithubAndroidRelease(
    val version: String,
    val pageUrl: String,
)

internal object GithubReleaseFeed {
    private const val RELEASES_URL =
        "https://api.github.com/repos/HereLiesAz/aive/releases?per_page=5"
    const val RELEASES_PAGE_URL = "https://github.com/HereLiesAz/aive/releases"
    private const val GITHUB_PAGE_PREFIX = "https://github.com/"
    private val apkName = Regex("^TheAive-(.+)-android\\.apk$")

    fun latestAndroidRelease(): GithubAndroidRelease? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "The-Aive-Android-Updater")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("GitHub release check failed with HTTP ${connection.responseCode}")
            }
            parseLatest(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Newest Android APK across all non-draft releases in [document] (the releases API array). A
     * release can hold many APKs (the rolling 0.9.6 release holds one per build), so the newest is the
     * highest version over every matching asset, not the first one listed. Prereleases are included:
     * the release workflow publishes them as such.
     */
    fun parseLatest(document: String): GithubAndroidRelease? =
        Json.parseToJsonElement(document).jsonArray.asSequence()
            .map { it.jsonObject }
            .filterNot { it["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true }
            .flatMap { release ->
                val page = release["html_url"]?.jsonPrimitive?.content
                    ?.takeIf { it.startsWith(GITHUB_PAGE_PREFIX) }
                    ?: RELEASES_PAGE_URL
                release["assets"]?.jsonArray.orEmpty().asSequence().mapNotNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val version = apkName.matchEntire(name)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                    GithubAndroidRelease(version = version, pageUrl = page)
                }
            }
            .reduceOrNull { best, candidate ->
                if (isNewerVersion(candidate.version, best.version)) candidate else best
            }
}
