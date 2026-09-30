package com.hereliesaz.geministrator

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Progress of one [DesktopResumableFileDownloader.downloadVerified] call. */
internal sealed interface DownloadProgress {
    /** Bytes on disk so far, including resumed bytes; [total] is null when the server omits it. */
    data class Transferring(val received: Long, val total: Long?) : DownloadProgress

    /** Hashing the complete file against the expected SHA-256. */
    data object Verifying : DownloadProgress
}

/**
 * Resumable downloader for large desktop runtime artifacts (ported from the Android downloader).
 *
 * Partial bytes are intentionally kept in a sibling `.download` file. Every retry issues a fresh
 * request to the stable release URL, so expired GitHub signed redirects are refreshed automatically.
 * If the server honors Range the transfer resumes; if it ignores Range and returns 200, the partial
 * file is safely replaced from byte zero. The caller-supplied SHA-256 remains the final authority.
 */
internal class DesktopResumableFileDownloader(
    private val httpClient: HttpClient,
    private val maxAttempts: Int = 8,
    private val initialRetryDelayMillis: Long = 750L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least one" }
        require(initialRetryDelayMillis >= 0L) { "initialRetryDelayMillis must be non-negative" }
    }

    suspend fun downloadVerified(
        url: String,
        output: File,
        expectedSha256: String,
        expectedSize: Long? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val normalizedSha = expectedSha256.trim().lowercase()
        require(normalizedSha.matches(SHA256_REGEX)) { "Invalid SHA-256 for ${output.name}" }
        require(expectedSize == null || expectedSize >= 0L) { "expectedSize must be non-negative" }

        output.parentFile?.mkdirs()
        if (output.isFile) onProgress(DownloadProgress.Verifying)
        if (output.isFile && validCompletedFile(output, normalizedSha, expectedSize)) {
            return@withContext output
        }
        if (output.exists()) output.delete()

        val partial = File(output.parentFile, "${output.name}.download")
        if (expectedSize != null && partial.isFile && partial.length() > expectedSize) {
            partial.delete()
        }

        if (partial.isFile && expectedSize != null && partial.length() == expectedSize) {
            onProgress(DownloadProgress.Verifying)
            if (sha256(partial) == normalizedSha) {
                finalizePartial(partial, output)
                return@withContext output
            }
            partial.delete()
        }

        var lastFailure: Throwable? = null
        var knownTotal = expectedSize

        for (attempt in 1..maxAttempts) {
            val requestedOffset = partial.takeIf(File::isFile)?.length() ?: 0L
            try {
                val response = httpClient.get(url) {
                    if (requestedOffset > 0L) {
                        header(HttpHeaders.Range, "bytes=$requestedOffset-")
                    }
                }

                if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    if (partial.isFile &&
                        knownTotal != null &&
                        partial.length() == knownTotal &&
                        sha256(partial) == normalizedSha
                    ) {
                        finalizePartial(partial, output)
                        return@withContext output
                    }
                    partial.delete()
                    throw IOException("Server rejected resume range for ${output.name}")
                }

                check(response.status.isSuccess()) {
                    "Download failed for ${output.name}: ${response.status}"
                }

                val contentRange = parseContentRange(response.headers[HttpHeaders.ContentRange])
                val append = requestedOffset > 0L && response.status == HttpStatusCode.PartialContent

                if (append) {
                    val range = contentRange
                        ?: throw IOException("Resume response for ${output.name} omitted Content-Range")
                    if (range.start != requestedOffset) {
                        throw IOException(
                            "Resume response for ${output.name} started at ${range.start}, " +
                                "expected $requestedOffset",
                        )
                    }
                }

                val responseLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val reportedTotal = when {
                    contentRange?.total != null -> contentRange.total
                    responseLength != null && append -> requestedOffset + responseLength
                    responseLength != null -> responseLength
                    else -> null
                }

                if (knownTotal != null && reportedTotal != null && knownTotal != reportedTotal) {
                    val retainShortInitialResponse =
                        requestedOffset == 0L &&
                            response.status == HttpStatusCode.OK &&
                            reportedTotal < knownTotal
                    if (!retainShortInitialResponse) {
                        throw IOException(
                            "Download size changed for ${output.name}: expected $knownTotal bytes, " +
                                "server reports $reportedTotal bytes",
                        )
                    }
                }
                if (knownTotal == null) knownTotal = reportedTotal

                // A 200 after a Range request means the origin ignored Range. Start clean rather
                // than appending a second full copy of the asset.
                FileOutputStream(partial, append).buffered().use { stream ->
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(256 * 1024)
                    var received = if (append) requestedOffset else 0L
                    var lastReportMillis = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        stream.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - lastReportMillis >= PROGRESS_INTERVAL_MILLIS) {
                            lastReportMillis = now
                            onProgress(DownloadProgress.Transferring(received, knownTotal))
                        }
                    }
                }

                val currentSize = partial.length()
                if (knownTotal != null && currentSize < knownTotal) {
                    throw IOException(
                        "Incomplete download for ${output.name}: expected $knownTotal bytes, " +
                            "received $currentSize bytes",
                    )
                }
                if (knownTotal != null && currentSize > knownTotal) {
                    partial.delete()
                    throw IOException(
                        "Oversized download for ${output.name}: expected $knownTotal bytes, " +
                            "received $currentSize bytes",
                    )
                }

                onProgress(DownloadProgress.Verifying)
                val actualSha = sha256(partial)
                if (actualSha != normalizedSha) {
                    // Once all reported bytes are present a digest mismatch cannot be repaired by
                    // appending. Discard the corrupt partial and allow the next attempt to restart.
                    partial.delete()
                    throw IOException(
                        "SHA-256 mismatch for ${output.name}: expected $normalizedSha, got $actualSha",
                    )
                }

                finalizePartial(partial, output)
                return@withContext output
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt == maxAttempts) break
                val backoff = min(initialRetryDelayMillis * (1L shl min(attempt - 1, 5)), 15_000L)
                if (backoff > 0L) delay(backoff)
            }
        }

        val retained = partial.takeIf(File::isFile)?.length() ?: 0L
        throw IOException(
            buildString {
                append("Download failed for ")
                append(output.name)
                append(" after ")
                append(maxAttempts)
                append(" attempts")
                if (retained > 0L) {
                    append("; retained ")
                    append(retained)
                    append(" partial bytes for resume")
                }
            },
            lastFailure,
        )
    }

    private fun validCompletedFile(file: File, expectedSha: String, expectedSize: Long?): Boolean =
        (expectedSize == null || file.length() == expectedSize) && sha256(file) == expectedSha

    private fun finalizePartial(partial: File, output: File) {
        output.delete()
        if (!partial.renameTo(output)) {
            partial.copyTo(output, overwrite = true)
            check(partial.delete()) { "Could not remove partial file for ${output.name}" }
        }
        check(output.isFile) { "Could not finalize ${output.name}" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let(CONTENT_RANGE_REGEX::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start) return null
        return ContentRange(start, end, total)
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?,
    )

    private companion object {
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
        const val PROGRESS_INTERVAL_MILLIS = 500L
        val CONTENT_RANGE_REGEX = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)")
    }
}
