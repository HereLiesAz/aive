package com.hereliesaz.geministrator

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

class DesktopDownloadNotPublishedTest {
    @Test
    fun aMissingReleaseFailsAtOnceAndSaysSo(): Unit = runBlocking {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; respondError(HttpStatusCode.NotFound) })
        val root = Files.createTempDirectory("aive-missing").toFile()
        try {
            val failure = assertFailsWith<DownloadNotPublishedException> {
                DesktopResumableFileDownloader(client, maxAttempts = 8, initialRetryDelayMillis = 0L)
                    .downloadVerified("https://example.invalid/x.tar.gz", File(root, "x.tar.gz"), "a".repeat(64))
            }
            assertEquals(1, requests, "a 404 is not retried")
            assertFalse(File(root, "x.tar.gz.download").exists())
            assert("not published" in failure.message.orEmpty())
        } finally {
            client.close()
            root.deleteRecursively()
        }
    }
}
