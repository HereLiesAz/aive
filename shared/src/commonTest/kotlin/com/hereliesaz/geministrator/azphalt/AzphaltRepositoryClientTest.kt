package com.hereliesaz.geministrator.azphalt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzphaltRepositoryClientTest {
    @Test
    fun searchIsScopedToHaiveAndWorkflowPackages() = runBlocking {
        var app: String? = null
        var kind: String? = null
        var query: String? = null
        val http = HttpClient(MockEngine { request ->
            app = request.url.parameters["app"]
            kind = request.url.parameters["kind"]
            query = request.url.parameters["q"]
            respond(
                content = """
                    {
                      "packages":[{
                        "id":"com.example.release",
                        "name":"Release Workflow",
                        "version":"1.2.0",
                        "latest":"1.2.0",
                        "kind":"workflow",
                        "priceStatus":"free",
                        "targetApps":["com.hereliesaz.aive"]
                      }],
                      "total":1,
                      "page":1,
                      "pages":1
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = AzphaltRepositoryClient(http)

        val result = client.search(query = "release")

        assertEquals(HAIVE_AZPHALT_HOST_ID, app)
        assertEquals("workflow", kind)
        assertEquals("release", query)
        assertEquals("com.example.release", result.packages.single().id)
        http.close()
    }

    @Test
    fun detailDecodesHaiveWorkflowManifestWithoutNeedingUnknownManifestFields() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/packages/com.example.release", request.url.encodedPath)
            respond(
                content = """
                    {
                      "id":"com.example.release",
                      "name":"Release Workflow",
                      "description":"Ship safely",
                      "version":"1.2.0",
                      "latest":"1.2.0",
                      "kind":"workflow",
                      "priceStatus":"free",
                      "manifest":{
                        "azphalt":"0.1",
                        "id":"com.example.release",
                        "name":"Release Workflow",
                        "version":"1.2.0",
                        "kind":"workflow",
                        "license":"MIT",
                        "compat":">=0.1",
                        "targetApps":["com.hereliesaz.aive"],
                        "files":{"LICENSE":"sha256-a","workflows/release.json":"sha256-b"},
                        "someFutureField":{"ignored":true},
                        "workflow":{
                          "format":"haive.workflow.v1",
                          "definitions":[{"id":"release","name":"Release","path":"workflows/release.json"}],
                          "hostPermissions":["WorkflowRegister","WorkflowLaunch"]
                        }
                      },
                      "versions":[{"version":"1.2.0","digest":"sha256-package","yanked":false}]
                    }
                """.trimIndent(),
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val client = AzphaltRepositoryClient(http)

        val detail = client.detail("com.example.release")

        assertEquals("workflow", detail.kind)
        assertEquals(HAIVE_WORKFLOW_FORMAT, detail.manifest?.workflow?.format)
        assertEquals("workflows/release.json", detail.manifest?.workflow?.definitions?.single()?.path)
        assertEquals(listOf("WorkflowRegister", "WorkflowLaunch"), detail.manifest?.workflow?.hostPermissions)
        http.close()
    }

    @Test
    fun paidDownloadSurfacesPaymentRequiredDistinctFromAuthentication() = runBlocking {
        val http = HttpClient(MockEngine {
            respondError(HttpStatusCode.PaymentRequired, "license required")
        })
        val client = AzphaltRepositoryClient(http)

        val failure = assertFailsWith<AzphaltRepositoryException.PaymentRequired> {
            client.download("com.example.paid", "1.0.0")
        }

        assertTrue(failure.message.orEmpty().contains("license required"))
        http.close()
    }

    @Test
    fun entitlementIsSentOnlyAsBearerOnDownload() = runBlocking {
        var authorization: String? = null
        val bytes = "AZP".encodeToByteArray()
        val http = HttpClient(MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                content = bytes,
                headers = headersOf(HttpHeaders.ContentType, AZPHALT_PACKAGE_MEDIA_TYPE),
            )
        })
        val client = AzphaltRepositoryClient(http)

        val downloaded = client.download("com.example.paid", "1.0.0", "signed-token")

        assertEquals("Bearer signed-token", authorization)
        assertTrue(downloaded.contentEquals(bytes))
        http.close()
    }

    @Test
    fun installLinkParsesPackageVersionAndCustomRepository() {
        val parsed = AzphaltInstallLink.parse(
            "azphalt://install?id=com.example.release&version=1.2.0&repo=https%3A%2F%2Frepo.example",
        )

        assertEquals("com.example.release", parsed?.packageId)
        assertEquals("1.2.0", parsed?.version)
        assertEquals("https://repo.example", parsed?.repositoryUrl)
    }

    @Test
    fun installLinkRejectsUnsafeOrNonHttpsInputs() {
        assertNull(AzphaltInstallLink.parse("https://azphalt.store/p/com.example.release"))
        assertNull(AzphaltInstallLink.parse("azphalt://install?id=../escape"))
        assertNull(AzphaltInstallLink.parse("azphalt://install?id=com.example.release&repo=http%3A%2F%2Fevil.example"))
    }

    @Test
    fun repositoryClientRejectsNonHttpsCustomRepository() {
        val http = HttpClient(MockEngine { error("network should not be reached") })
        assertFailsWith<IllegalArgumentException> {
            AzphaltRepositoryClient(http, repositoryUrl = "http://repo.example")
        }
        http.close()
    }
}
