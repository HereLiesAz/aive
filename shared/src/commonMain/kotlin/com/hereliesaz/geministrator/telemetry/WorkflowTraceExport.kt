package com.hereliesaz.geministrator.telemetry

import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.WorkflowEventRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Turns finished runs into OTLP traces (docs/architecture/OBSERVABILITY.md). It watches the
 * runs the runtime publishes; when one ends, it reads that run's event journal, derives its
 * spans and hands them to the exporter's queue. Nothing here runs inside an engine cycle, and
 * nothing happens while export is off.
 */
class WorkflowTraceExport(
    val store: TraceExportSettingsStore,
    exporterFactory: (() -> TraceExportSettings) -> OtlpTraceExporter = { settings ->
        OtlpTraceExporter(defaultHttpClient(), settings)
    },
) {
    private val exporter: OtlpTraceExporter by lazy { exporterFactory(store::load) }
    private var worker: Job? = null

    /** Watches [runs] in [scope] until it is cancelled. Each run is exported at most once. */
    fun attach(
        scope: CoroutineScope,
        runs: Flow<WorkflowRun?>,
        events: WorkflowEventRepository,
    ): Job = scope.launch {
        runs.collect { run ->
            if (run == null) return@collect
            try {
                exportIfFinished(scope, run, events)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Trace export is best effort and must never disturb the runtime.
            }
        }
    }

    internal suspend fun exportIfFinished(
        scope: CoroutineScope,
        run: WorkflowRun,
        events: WorkflowEventRepository,
    ): Boolean {
        val spans = spansIfDue(run, events) ?: return false
        if (worker?.isActive != true) worker = scope.launch { exporter.run() }
        exporter.enqueue(spans)
        return true
    }

    /** The run's spans when it has ended since export was turned on and was not sent before; otherwise null. */
    internal suspend fun spansIfDue(run: WorkflowRun, events: WorkflowEventRepository): List<OtelSpan>? {
        if (!run.status.isFinished()) return null
        val settings = store.load()
        if (settings.activeTracesUrl() == null) return null
        val enabledAt = settings.enabledAtEpochMillis ?: return null
        if (run.updatedAtEpochMillis < enabledAt) return null
        val runId = run.id.value
        if (store.isExported(runId)) return null
        store.markExported(runId)
        return WorkflowTraceBuilder.build(events.forRun(run.id), settings.contentPolicy)
            .takeIf { it.isNotEmpty() }
    }

    companion object {
        /** The app's instance, on the app's settings. Created on first use. */
        val durable: WorkflowTraceExport by lazy { WorkflowTraceExport(TraceExportSettingsStore()) }

        private fun defaultHttpClient(): HttpClient = HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000L
                connectTimeoutMillis = 10_000L
            }
        }
    }
}

private fun WorkflowRunStatus.isFinished(): Boolean = when (this) {
    WorkflowRunStatus.Completed, WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled -> true
    WorkflowRunStatus.Created, WorkflowRunStatus.Running, WorkflowRunStatus.AwaitingHuman -> false
}
