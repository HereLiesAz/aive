package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An exception with no message, like the null-message failure seen on devices. */
private class SilentFailure : RuntimeException()

class AzphaltStoreServiceDiagnosticsTest {
    private fun service(http: HttpClient): AzphaltStoreService {
        val pins = SettingsAzphaltPublisherPinStore(MapSettings())
        val store = SettingsAzphaltInstallStore(MapSettings())
        return AzphaltStoreService(
            repository = AzphaltRepositoryClient(http),
            verifier = AzphaltPackageVerifier(
                archiveReader = object : AzphaltArchiveReader {
                    override suspend fun read(bytes: ByteArray): Map<String, ByteArray> = emptyMap()
                },
                ed25519 = object : AzphaltEd25519Verifier {
                    override suspend fun verify(publicKeySpki: ByteArray, message: ByteArray, signature: ByteArray) = true
                },
                publisherPins = pins,
            ),
            installer = AzphaltWorkflowPackageInstaller(InMemoryWorkflowPersistence(), store, pins),
            installStore = store,
        )
    }

    private fun causes(failure: Throwable): List<Throwable> =
        generateSequence(failure.cause) { it.cause }.toList()

    @Test
    fun aMessagelessEngineFailureNamesTheStepAndCarriesTheClass() = runBlocking {
        val http = HttpClient(MockEngine { throw SilentFailure() })

        val failure = assertFailsWith<AzphaltStoreStepException> { service(http).snapshot() }

        assertEquals(AzphaltStoreStep.Index, failure.step)
        assertTrue(causes(failure).any { it is SilentFailure }, "the original exception is kept as a cause")
        val message = failure.message.orEmpty()
        assertTrue(AzphaltStoreStep.Index.label in message, "message names the step: $message")
        assertTrue(failure.cause!!::class.simpleName.orEmpty() in message, "message carries the class: $message")

        val details = storeFailureDetails(failure)
        assertTrue("Step: ${AzphaltStoreStep.Index.label}" in details)
        assertTrue("SilentFailure" in details, "details carry the exception class: $details")
        assertFalse(details.isBlank())
        http.close()
    }

    @Test
    fun aLaterPhaseFailureNamesThatPhase() = runBlocking {
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("azphalt-repository.json")) {
                    respond(
                        content = """{"name":"Test","version":"1"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    throw SilentFailure()
                }
            },
        )

        val failure = assertFailsWith<AzphaltStoreStepException> { service(http).snapshot() }

        assertEquals(AzphaltStoreStep.Search, failure.step)
        assertTrue(AzphaltStoreStep.Search.label in failure.message.orEmpty())
        http.close()
    }

    @Test
    fun cancellationIsNotWrappedAsAStoreFailure() = runBlocking {
        val cancelled = assertFailsWith<CancellationException> {
            storeStep(AzphaltStoreStep.Index) { throw CancellationException("stop") }
        }
        assertEquals("stop", cancelled.message)
    }

    @Test
    fun aFailureAlreadyAttributedToAStepIsNotWrappedAgain() = runBlocking {
        val inner = AzphaltStoreStepException(AzphaltStoreStep.Revocations, SilentFailure())
        val thrown = assertFailsWith<AzphaltStoreStepException> {
            storeStep(AzphaltStoreStep.Index) { throw inner }
        }
        assertEquals(AzphaltStoreStep.Revocations, thrown.step)
    }

    @Test
    fun detailsListTheCauseChain() {
        val failure = AzphaltStoreStepException(
            AzphaltStoreStep.Revocations,
            IllegalStateException("handshake failed", SilentFailure()),
        )
        val details = storeFailureDetails(failure)
        assertTrue("Exception: IllegalStateException" in details)
        assertTrue("Message: handshake failed" in details)
        assertTrue("Caused by: SilentFailure: no message" in details)
    }

    @Test
    fun theRepositoryPublishesPackageSizeAsBytes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val published = json.decodeFromString<AzphaltPackageSummary>(
            """{"id":"a","name":"A","version":"1.0.0","kind":"workflow","bytes":3436}""",
        )
        assertEquals(3436L, published.sizeInBytes)

        val legacy = json.decodeFromString<AzphaltPackageSummary>(
            """{"id":"a","name":"A","version":"1.0.0","kind":"workflow","byteSize":99}""",
        )
        assertEquals(99L, legacy.sizeInBytes)

        val none = json.decodeFromString<AzphaltPackageSummary>(
            """{"id":"a","name":"A","version":"1.0.0","kind":"workflow"}""",
        )
        assertNull(none.sizeInBytes)
    }
}
