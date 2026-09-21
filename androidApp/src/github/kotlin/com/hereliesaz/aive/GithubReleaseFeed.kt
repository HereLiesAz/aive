package com.hereliesaz.aive

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class GithubAndroidRelease(
    val version: String,
    val downloadUrl: String,
    val sha256: String? = null,
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
                    val sha256 = asset.jsonObject["digest"]?.jsonPrimitive?.content
                        ?.removePrefix("sha256:")
                        ?.lowercase()
                        ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                    GithubAndroidRelease(version = version, downloadUrl = url, sha256 = sha256)
                }
                .reduceOrNull { best, candidate ->
                    if (isNewerVersion(candidate.version, best.version)) candidate else best
                }
        }
    }

    fun download(url: String, destination: File): File {
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
            destination.parentFile?.mkdirs()
            val buffer = ByteArray(256 * 1024)
            inputStream.use { input ->
                destination.outputStream().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            }
            destination
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T =
        try {
            block()
        } finally {
            disconnect()
        }
}
