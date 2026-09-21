package com.hereliesaz.aive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidResumableFileDownloaderTest {
    @Test
    fun resumesFromRetainedPartialAfterInterruptedTransfer() = runBlocking {
        val bytes = "0123456789".encodeToByteArray()
        var requests = 0
        val client = HttpClient(MockEngine { request ->
            when (requests++) {
                0 -> {
                    assertEquals(null, request.headers[HttpHeaders.Range])
                    respond(
                        content = bytes.copyOfRange(0, 4),
                        status = HttpStatusCode.OK,
                    )
                }
                else -> {
                    assertEquals("bytes=4-", request.headers[HttpHeaders.Range])
                    respond(
                        content = bytes.copyOfRange(4, bytes.size),
                        status = HttpStatusCode.PartialContent,
                        headers = headersOf(
                            HttpHeaders.ContentRange to listOf("bytes 4-9/10"),
                            HttpHeaders.ContentLength to listOf("6"),
                        ),
                    )
                }
            }
        })

        val root = Files.createTempDirectory("aive-resume-test").toFile()
        try {
            val output = File(root, "model.part001")
            File(root, "model.part001.download").writeBytes(bytes.copyOfRange(0, 4))
            val downloader = AndroidResumableFileDownloader(
                httpClient = client,
                maxAttempts = 3,
                initialRetryDelayMillis = 0L,
            )

            downloader.downloadVerified(
                url = "https://example.test/model.part001",
                output = output,
                expectedSha256 = sha256(bytes),
                expectedSize = bytes.size.toLong(),
            )

            assertEquals(bytes.toList(), output.readBytes().toList())
            assertTrue(!File(root, "model.part001.download").exists())
            assertEquals(2, requests)
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun replacesPartialWhenOriginIgnoresRange() = runBlocking {
        val bytes = "abcdefghij".encodeToByteArray()
        val root = Files.createTempDirectory("aive-range-ignore-test").toFile()
        val partial = File(root, "model.tar.gz.download").apply {
            writeBytes(bytes.copyOfRange(0, 4))
        }
        var requestedRange: String? = null
        val client = HttpClient(MockEngine { request ->
            requestedRange = request.headers[HttpHeaders.Range]
            respond(
                content = bytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, bytes.size.toString()),
            )
        })

        try {
            val output = File(root, "model.tar.gz")
            AndroidResumableFileDownloader(
                httpClient = client,
                maxAttempts = 1,
                initialRetryDelayMillis = 0L,
            ).downloadVerified(
                url = "https://example.test/model.tar.gz",
                output = output,
                expectedSha256 = sha256(bytes),
                expectedSize = bytes.size.toLong(),
            )

            assertEquals("bytes=4-", requestedRange)
            assertEquals(bytes.toList(), output.readBytes().toList())
            assertTrue(!partial.exists())
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun keepsPartialBytesWhenRetriesAreExhausted() = runBlocking {
        val bytes = "0123456789".encodeToByteArray()
        var requestedRange: String? = null
        val client = HttpClient(MockEngine { request ->
            requestedRange = request.headers[HttpHeaders.Range]
            respondError(HttpStatusCode.InternalServerError)
        })
        val root = Files.createTempDirectory("aive-retain-test").toFile()

        try {
            val output = File(root, "model.part001")
            val partial = File(root, "model.part001.download").apply {
                writeBytes(bytes.copyOfRange(0, 4))
            }
            val failure = assertFailsWith<java.io.IOException> {
                AndroidResumableFileDownloader(
                    httpClient = client,
                    maxAttempts = 1,
                    initialRetryDelayMillis = 0L,
                ).downloadVerified(
                    url = "https://example.test/model.part001",
                    output = output,
                    expectedSha256 = sha256(bytes),
                    expectedSize = bytes.size.toLong(),
                )
            }

            assertEquals("bytes=4-", requestedRange)
            assertTrue(partial.isFile)
            assertEquals(4L, partial.length())
            assertTrue(failure.message.orEmpty().contains("retained 4 partial bytes"))
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
