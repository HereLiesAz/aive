package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.memory.procedural.ProcedureTracePhase
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceRedactor
import com.hereliesaz.geministrator.memory.procedural.ProcedureTraceSink
import kotlinx.coroutines.CancellationException

/**
 * Records only the metadata an executor actually reported. Observer failures cannot turn a
 * successful dispatch into a failed task. Exceptions from the executor itself are still rethrown.
 * This wrapper NEVER reruns an operation, changes approvals, or reads script/command contents.
 */
class ProcedureTracingTaskExecutorIntegration(
    private val delegate: TaskExecutorIntegration,
    private val sink: () -> ProcedureTraceSink,
) : TaskExecutorIntegration {
    override val orchestrationToolId: String get() = delegate.orchestrationToolId
    override val retryDispatchFailures: Boolean get() = delegate.retryDispatchFailures
    override fun supports(executor: TaskExecutor): Boolean = delegate.supports(executor)
    override fun supports(executor: TaskExecutor, project: Project): Boolean =
        delegate.supports(executor, project)

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution = record(
        context, ProcedureTracePhase.Dispatch,
    ) { delegate.dispatch(context) }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution = record(
        context, ProcedureTracePhase.Reconcile,
    ) { delegate.reconcile(context) }

    private suspend fun record(
        context: TaskExecutorContext,
        phase: ProcedureTracePhase,
        operation: suspend () -> TaskExecutorExecution,
    ): TaskExecutorExecution {
        val result = try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            notifySafely(context, phase, thrown = true)
            throw failure
        }
        // A Running/Verifying reconciliation can be repeated frequently. Do not flood memory.
        if (phase == ProcedureTracePhase.Dispatch ||
            result.status == TaskRunStatus.Completed ||
            result.status == TaskRunStatus.Failed
        ) notifySafely(context, phase, result = result)
        return result
    }

    private suspend fun notifySafely(
        context: TaskExecutorContext,
        phase: ProcedureTracePhase,
        result: TaskExecutorExecution? = null,
        thrown: Boolean = false,
    ) {
        try {
            sink().record(ProcedureTraceRedactor.capture(context, phase, result, thrown))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Procedural memory is an optional observer, never the executor authority.
        }
    }
}
