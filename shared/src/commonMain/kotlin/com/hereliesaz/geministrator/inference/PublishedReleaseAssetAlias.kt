package com.hereliesaz.geministrator.inference

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.cancellation.CancellationException

/**
 * Download-side alias for the Epoch-8 model release assets.
 *
 * Those assets are already published on GitHub under the app's previous name and cannot be renamed
 * from this repository. The catalog names them with the current `aive-` prefix, and the downloaders
 * try that name first; only when GitHub answers 404 for it do they fall back to the published
 * name. Republishing the assets under the `aive-` names retires the fallback.
 */
private val RENAMED_ASSET_PREFIXES = listOf("aive-memory-layer-", "aive-specialist_", "aive-orch_planner-")

// Legacy input name: the brand prefix the already-published assets still carry on GitHub.
private const val PUBLISHED_BRAND_PREFIX = "haive"

/** The asset file name as already published remotely for a current catalog asset [name], or null when [name] has no alias. */
fun publishedReleaseAssetName(name: String): String? =
    if (RENAMED_ASSET_PREFIXES.any(name::startsWith)) PUBLISHED_BRAND_PREFIX + name.removePrefix("aive") else null

/** [url] with its asset name replaced by the already-published name, or null when the asset has no alias. */
fun publishedReleaseAssetUrl(url: String): String? {
    val slash = url.lastIndexOf('/')
    if (slash < 0) return null
    val published = publishedReleaseAssetName(url.substring(slash + 1)) ?: return null
    return url.substring(0, slash + 1) + published
}

/**
 * [url], unless it names an aliased asset that GitHub reports missing (404); then the already-published
 * URL. Any other answer, or a failed probe, keeps [url] so ordinary download retries apply to it.
 */
suspend fun resolveReleaseAssetUrl(client: HttpClient, url: String): String {
    val published = publishedReleaseAssetUrl(url) ?: return url
    val missing = try {
        client.prepareGet(url) { header(HttpHeaders.Range, "bytes=0-0") }
            .execute { response -> response.status == HttpStatusCode.NotFound }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ResponseException) {
        failure.response.status == HttpStatusCode.NotFound
    } catch (ignored: Throwable) {
        false
    }
    return if (missing) published else url
}
