package com.hereliesaz.aive

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class GithubAndroidRelease(
    val version: String,
    val downloadUrl: String,
)

internal object GithubReleaseFeed {
    private const val RELEASES_URL =
        "https://api.github.com/repos/HereLiesAz/aive/releases?per_page=20"
    private val apkName = Regex("^TheAive-(.+)-android\\.apk$")

    fun latestAndroidRelease(): GithubAndroidRelease? {
        val connection = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "The-Aive-Android-Updater")
        }
        return connection.useConnection {
            if (responseCode !in 200..299) {
                error("GitHub release check failed with HTTP $responseCode")
            }
            val document = inputStream.bufferedReader().use { it.readText() }
            val releases = Json.parseToJsonElement(document).jsonArray
            releases.asSequence()
                .filterNot { element ->
                    element.jsonObject["draft"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
                }
                .flatMap { element ->
                    element.jsonObject["assets"]
                        ?.jsonArray
                        ?.asSequence()
                        .orEmpty()
                }
                .mapNotNull { asset ->
                    val name = asset.jsonObject["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val version = apkName.matchEntire(name)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                    val url = asset.jsonObject["browser_download_url"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                    GithubAndroidRelease(version = version, downloadUrl = url)
                }
                .reduceOrNull { best, candidate ->
                    if (isNewerVersion(candidate.version, best.version)) candidate else best
                }
        }
    }

    fun download(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty("User-Agent", "The-Aive-Android-Updater")
        }
        return connection.useConnection {
            if (responseCode !in 200..299) {
                error("Update download failed with HTTP $responseCode")
            }
            inputStream.use { it.readBytes() }
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T =
        try {
            block()
        } finally {
            disconnect()
        }
}
