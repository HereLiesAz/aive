package com.hereliesaz.geministrator.azphalt

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Standards-only client for azphalt.store or any conforming Azphalt Repository API.
 *
 * The app id is always sent on catalog searches so app-scoped Haive workflows are visible while
 * packages scoped to unrelated hosts stay out of the UI. Exact-id detail/download remains available
 * as required by the Repository API; the installer performs the authoritative targetApps check.
 */
class AzphaltRepositoryClient(
    private val httpClient: HttpClient,
    repositoryUrl: String = AZPHALT_STORE_URL,
    private val hostAppId: String = HAIVE_AZPHALT_HOST_ID,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    val repositoryUrl: String = normalizeRepositoryUrl(repositoryUrl)

    suspend fun index(): AzphaltRepositoryIndex {
        val response = httpClient.get("$repositoryUrl/.well-known/azphalt-repository.json")
        response.requireSuccess("read Azphalt repository index")
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun search(
        query: String = "",
        kinds: Set<String> = setOf("workflow"),
        page: Int = 1,
        sort: String = "popular",
    ): AzphaltPackageSearchResponse {
        require(page > 0) { "page must be positive" }

        val scoped = searchRequest(
            query = query,
            kinds = kinds,
            page = page,
            sort = sort,
            appId = hostAppId,
        )
        if (scoped.packages.isNotEmpty() || page != 1) return scoped

        // Some deployed Repository API frontends have historically returned an empty page for
        // app-scoped searches while their unscoped catalog was healthy. Do not turn that server-side
        // indexing drift into an apparently empty Aive Store: retry without ?app=, then enforce the
        // exact same app-scope rule locally from each summary's targetApps metadata.
        val unscoped = searchRequest(
            query = query,
            kinds = kinds,
            page = page,
            sort = sort,
            appId = null,
        )
        val acceptedHostIds = setOf(hostAppId, LEGACY_HAIVE_AZPHALT_HOST_ID)
        val compatible = unscoped.packages.filter { summary ->
            summary.targetApps.isEmpty() || summary.targetApps.any(acceptedHostIds::contains)
        }
        return unscoped.copy(
            packages = compatible,
            total = compatible.size,
            page = 1,
            pages = 1,
        )
    }

    private suspend fun searchRequest(
        query: String,
        kinds: Set<String>,
        page: Int,
        sort: String,
        appId: String?,
    ): AzphaltPackageSearchResponse {
        val response = httpClient.get("$repositoryUrl/packages") {
            url {
                appId?.trim()?.takeIf(String::isNotEmpty)?.let { parameters.append("app", it) }
                query.trim().takeIf(String::isNotEmpty)?.let { parameters.append("q", it) }
                if (kinds.isNotEmpty()) parameters.append("kind", kinds.sorted().joinToString(","))
                parameters.append("page", page.toString())
                parameters.append("sort", sort)
            }
        }
        response.requireSuccess("search Azphalt repository")
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun detail(packageId: String): AzphaltPackageDetail {
        val id = requirePackageId(packageId)
        val response = httpClient.get("$repositoryUrl/packages/$id")
        response.requireSuccess("read Azphalt package $id")
        return json.decodeFromString(response.bodyAsText())
    }

    suspend fun download(
        packageId: String,
        version: String,
        entitlementToken: String? = null,
    ): ByteArray {
        val id = requirePackageId(packageId)
        val safeVersion = requireVersion(version)
        val response = httpClient.get("$repositoryUrl/packages/$id/versions/$safeVersion/download") {
            entitlementToken?.trim()?.takeIf(String::isNotEmpty)?.let { token ->
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        when (response.status) {
            HttpStatusCode.Unauthorized -> throw AzphaltRepositoryException.AuthenticationRequired(
                response.errorMessage("Authentication required for $id@$safeVersion"),
            )
            HttpStatusCode.PaymentRequired -> throw AzphaltRepositoryException.PaymentRequired(
                response.errorMessage("Payment or package entitlement required for $id@$safeVersion"),
            )
            else -> response.requireSuccess("download Azphalt package $id@$safeVersion")
        }
        return response.body()
    }

    suspend fun updates(installed: Collection<AzphaltInstalledPackageRef>): List<AzphaltPackageUpdate> {
        if (installed.isEmpty()) return emptyList()
        val normalized = installed.map { ref ->
            AzphaltInstalledPackageRef(
                id = requirePackageId(ref.id),
                version = requireVersion(ref.version),
            )
        }
        val response = httpClient.post("$repositoryUrl/updates") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(normalized))
        }
        response.requireSuccess("check Azphalt package updates")
        return json.decodeFromString<AzphaltUpdateCheckResponse>(response.bodyAsText()).updates
    }

    suspend fun revocations(since: String? = null): List<AzphaltRevocation> {
        val response = httpClient.get("$repositoryUrl/revocations") {
            since?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
                url { parameters.append("since", value) }
            }
        }
        response.requireSuccess("read Azphalt revocations")
        return json.decodeFromString<AzphaltRevocationsResponse>(response.bodyAsText()).revocations
    }

    suspend fun resolveInstallLink(
        link: AzphaltInstallLink,
        entitlementToken: String? = null,
    ): AzphaltResolvedDownload {
        val client = if (normalizeRepositoryUrl(link.repositoryUrl) == repositoryUrl) {
            this
        } else {
            AzphaltRepositoryClient(
                httpClient = httpClient,
                repositoryUrl = link.repositoryUrl,
                hostAppId = hostAppId,
                json = json,
            )
        }
        val detail = client.detail(link.packageId)
        val version = link.version ?: detail.latest ?: detail.version
        val bytes = client.download(detail.id, version, entitlementToken)
        return AzphaltResolvedDownload(client.repositoryUrl, detail, version, bytes)
    }

    private suspend fun HttpResponse.requireSuccess(action: String) {
        if (status.value in 200..299) return
        throw AzphaltRepositoryException.Http(
            statusCode = status.value,
            message = errorMessage("Unable to $action (${status.value})"),
        )
    }

    private suspend fun HttpResponse.errorMessage(fallback: String): String {
        val body = runCatching { bodyAsText().trim() }.getOrNull().orEmpty()
        return body.takeIf(String::isNotEmpty) ?: fallback
    }

    companion object {
        private val packageIdPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,253}[A-Za-z0-9])?$")
        private val versionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}$")

        fun normalizeRepositoryUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            require(normalized.startsWith("https://", ignoreCase = true)) {
                "Azphalt repository URL must use HTTPS"
            }
            return normalized
        }

        fun requirePackageId(value: String): String {
            val normalized = value.trim()
            require(packageIdPattern.matches(normalized)) { "Invalid Azphalt package id" }
            return normalized
        }

        fun requireVersion(value: String): String {
            val normalized = value.trim()
            require(versionPattern.matches(normalized)) { "Invalid Azphalt package version" }
            return normalized
        }
    }
}

data class AzphaltResolvedDownload(
    val repositoryUrl: String,
    val detail: AzphaltPackageDetail,
    val version: String,
    val bytes: ByteArray,
)
