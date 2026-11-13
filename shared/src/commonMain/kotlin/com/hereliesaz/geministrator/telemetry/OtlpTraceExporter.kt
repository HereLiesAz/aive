package com.hereliesaz.geministrator.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/** Retry schedule for one batch: exponential backoff from [initialDelayMillis], capped at [maxDelayMillis]. */
data class OtlpRetryPolicy(
    val maxAttempts: Int = 5,
    val initialDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 30_000L,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** Delay before retry number [retry] (1 for the first retry). */
    fun delayBefore(retry: Int): Long {
        var delayMillis = initialDelayMillis
        repeat((retry - 1).coerceAtLeast(0)) { delayMillis = (delayMillis * 2).coerceAtMost(maxDelayMillis) }
        return delayMillis.coerceAtMost(maxDelayMillis)
    }
}

enum class OtlpExportOutcome {
    /** The collector accepted the batch. */
    Sent,
    /** The collector refused it with a status that retrying cannot fix; the batch is dropped. */
    Rejected,
    /** Every attempt failed with a retryable status or a network error; the batch is dropped. */
    GaveUp,
    /** Export is off or its settings are not valid; nothing was sent. */
    Disabled,
}

/**
 * OTLP/HTTP JSON trace exporter. [enqueue] never suspends or blocks: spans go into a bounded
 * queue that drops the oldest when full. [run] drains it in batches and posts each batch with
 * retry and backoff; it is meant to run in its own coroutine, apart from the workflow engine.
 */
class OtlpTraceExporter(
    httpClient: HttpClient,
    private val settings: () -> TraceExportSettings,
    private val retryPolicy: OtlpRetryPolicy = OtlpRetryPolicy(),
    maxQueuedSpans: Int = DEFAULT_MAX_QUEUED_SPANS,
    private val maxBatchSpans: Int = DEFAULT_MAX_BATCH_SPANS,
) {
    // The collector's headers (which may carry a key) go only where the keeper pointed them: no redirect is followed.
    private val httpClient: HttpClient = httpClient.config { followRedirects = false }
    private val queue = Channel<OtelSpan>(maxQueuedSpans, BufferOverflow.DROP_OLDEST)

    /** Told how each drained batch ended, so a caller can record delivery only once it happened. */
    var onBatchFinished: (suspend (List<OtelSpan>, OtlpExportOutcome) -> Unit)? = null

    fun enqueue(spans: List<OtelSpan>) {
        spans.forEach { queue.trySend(it) }
    }

    /** Drains the queue forever; cancel the calling coroutine to stop. */
    suspend fun run() {
        while (true) {
            val batch = mutableListOf(queue.receive())
            while (batch.size < maxBatchSpans) {
                batch += queue.tryReceive().getOrNull() ?: break
            }
            val outcome = export(batch)
            onBatchFinished?.invoke(batch, outcome)
        }
    }

    suspend fun export(spans: List<OtelSpan>): OtlpExportOutcome {
        if (spans.isEmpty()) return OtlpExportOutcome.Sent
        val current = settings()
        val url = current.activeTracesUrl() ?: return OtlpExportOutcome.Disabled
        val headers = parseOtlpHeaders(current.headers) ?: return OtlpExportOutcome.Disabled
        val body = OtlpJson.traceRequest(spans).toString()
        for (attempt in 1..retryPolicy.maxAttempts) {
            if (attempt > 1) delay(retryPolicy.delayBefore(attempt - 1))
            val status = try {
                httpClient.post(url) {
                    headers.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    setBody(TextContent(body, ContentType.Application.Json))
                }.status.value
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            when {
                status == null -> Unit
                status in 200..299 -> return OtlpExportOutcome.Sent
                status in RETRYABLE_STATUSES -> Unit
                else -> return OtlpExportOutcome.Rejected
            }
        }
        return OtlpExportOutcome.GaveUp
    }

    companion object {
        const val DEFAULT_MAX_QUEUED_SPANS: Int = 2_048
        const val DEFAULT_MAX_BATCH_SPANS: Int = 256

        /** The statuses OTLP/HTTP names as retryable. */
        val RETRYABLE_STATUSES: Set<Int> = setOf(429, 502, 503, 504)
    }
}
