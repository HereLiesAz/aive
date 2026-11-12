package com.hereliesaz.geministrator.cli

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.ApplicationRuntimeState
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.launchOrchestratedWorkflow
import com.hereliesaz.geministrator.launchSavedWorkflow
import com.hereliesaz.geministrator.orchestration.PlanDecision
import kotlinx.coroutines.delay

/** In-process keeper input. Never routed through HTTP, MCP or another agent's commands. */
fun interface KeeperConsole {
    fun readLine(prompt: String): String?
}

internal data class HeadlessRunOptions(
    val objective: String?,
    val hiveId: String?,
    val project: String,
)

/** Parse only known options, refusing duplicates and partial invocations before opening the store. */
internal object HeadlessRunArguments {
    fun parse(args: List<String>): HeadlessRunOptions {
        val flags = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val name = args[index++]
            if (name !in setOf("--objective", "--hive", "--project")) {
                throw IllegalArgumentException("Unknown run argument: $name")
            }
            val value = args.getOrNull(index++)?.takeUnless { it.startsWith("--") || it.isBlank() }
                ?: throw IllegalArgumentException("$name requires a value")
            require(flags.put(name, value) == null) { "$name was given twice" }
        }
        val objective = flags["--objective"]
        val hive = flags["--hive"]
        require(objective != null || hive != null) { "Use --objective or --hive" }
        return HeadlessRunOptions(objective, hive, flags["--project"] ?: "Headless")
    }
}

/**
 * Foreground The Aive orchestration loop. The same persisted run is resumed after interruption.
 * The orchestrator runs on background coroutines; this loop only displays truth and handles
 * explicit keeper decisions from an attached local terminal.
 */
internal class HeadlessForeground(
    private val engine: HeadlessEngine,
    private val console: KeeperConsole?,
    private val output: Appendable,
) {
    suspend fun launch(options: HeadlessRunOptions) {
        check(!engine.runtime.hasUnfinishedActiveRun()) {
            "There is already an unfinished run. Use 'aive resume' instead."
        }
        val objective = options.objective?.trim()
        val provenance = if (console == null) ContextProvenance.ExternalContent else ContextProvenance.KeeperWritten
        if (options.hiveId != null) {
            val definition = engine.runtime.persistence.definitions.get(WorkflowDefinitionId(options.hiveId))
                ?: error("No hive ${options.hiveId}")
            engine.runtime.launchSavedWorkflow(
                definition = definition,
                projectName = options.project,
                objective = objective ?: definition.description ?: definition.name,
                objectiveProvenance = provenance,
                planner = engine.planner,
                planReviewer = engine.reviewer,
                confirmPlan = { decision -> confirmPlan(decision) },
            )
        } else {
            val planner = engine.planner ?: error(
                "No planning provider. Set OPENAI_API_KEY, GEMINI_API_KEY, ANTHROPIC_API_KEY, or another supported provider key.",
            )
            require(!objective.isNullOrBlank()) { "--objective must not be blank" }
            engine.runtime.launchOrchestratedWorkflow(
                projectName = options.project,
                objective = objective,
                orchestrationRuntime = planner,
                planReviewer = engine.reviewer,
                confirmPlan = { decision -> confirmPlan(decision) },
                objectiveProvenance = provenance,
            )
        }
    }

    /** Human approval remains an in-process owner decision, never a new control-API route. */
    private fun confirmPlan(decision: PlanDecision): Boolean {
        output.append("Plan for: ${decision.objective}\n")
        decision.outcome.plan.steps.forEach { step ->
            output.append("  ${step.id}  ${step.name} (${step.roleId})\n")
            output.append("    ${step.objective}\n")
        }
        if (decision.outcome.problem != null) output.append("Reviewer warning: ${decision.outcome.problem}\n")
        if (decision.outcome.objections.isNotEmpty()) {
            decision.outcome.objections.forEach { output.append("Reviewer objection: $it\n") }
        }
        return console?.readLine("Type LAUNCH to accept this plan: ") == "LAUNCH"
    }

    /**
     * Exit 0 only after actual completion, 1 for failure/cancel, 4 when a human decision is needed
     * but no terminal is attached. No pending task is ever automatically approved.
     */
    suspend fun follow(): Int {
        var lastStatus = ""
        var lastPrompt: String? = null
        while (true) {
            val state = engine.runtime.state.value
            val presentation = (state as? ApplicationRuntimeState.Live)?.presentation
                ?: error("No active run; The Aive runtime state is $state")
            val run = presentation.run
            val snapshot = "${run.id.value}:${run.status}:" +
                run.taskRuns.values.joinToString(";") { "${it.taskDefinitionId.value}=${it.status}" }
            if (snapshot != lastStatus) {
                output.append("${run.id.value}: ${run.status}\n")
                run.taskRuns.values.forEach { task ->
                    output.append("  ${task.taskDefinitionId.value}: ${task.status}")
                    task.progressMessage?.let { output.append(" - $it") }
                    output.append('\n')
                }
                lastStatus = snapshot
            }
            when (run.status) {
                WorkflowRunStatus.Completed -> return 0
                WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled -> return 1
                else -> Unit
            }
            val waiting = run.taskRuns.values.firstOrNull { it.status == TaskRunStatus.AwaitingApproval }
            if (waiting != null) {
                val id = waiting.taskDefinitionId.value
                val key = "${run.id.value}:$id:${waiting.attempt}"
                if (key != lastPrompt) {
                    lastPrompt = key
                    if (console == null) {
                        output.append("Human approval required for $id. Stop any daemon, then run 'aive resume' in a terminal.\n")
                        return 4
                    }
                    val definition = presentation.definition.tasks.firstOrNull { it.id == waiting.taskDefinitionId }
                    output.append("Awaiting human decision for ${definition?.name ?: id}\n")
                    definition?.let { output.append("Objective: ${it.objective}\n") }
                    waiting.providerPlan?.let { output.append("Proposed plan: $it\n") }
                    if (console.readLine("Type APPROVE $id to allow this task, or press Enter to leave it pending: ") == "APPROVE $id") {
                        engine.runtime.approveTask(TaskDefinitionId(id))
                    } else {
                        output.append("Approval left pending. Resume from a terminal to decide.\n")
                        return 4
                    }
                }
            } else {
                lastPrompt = null
            }
            delay(600L)
        }
    }

    /** The daemon's keeper console can inspect and decide a task while the runtime cycles. */
    suspend fun approve(taskId: String, keeper: KeeperConsole): Boolean {
        val presentation = (engine.runtime.state.value as? ApplicationRuntimeState.Live)?.presentation
            ?: error("No active run")
        val task = presentation.run.taskRuns[TaskDefinitionId(taskId)]
            ?: error("No task $taskId in the active run")
        require(task.status == TaskRunStatus.AwaitingApproval) { "Task $taskId is not awaiting approval" }
        val definition = presentation.definition.tasks.firstOrNull { it.id.value == taskId }
        output.append("Run ${presentation.run.id.value}, task $taskId: ${definition?.name ?: taskId}\n")
        definition?.let { output.append("Objective: ${it.objective}\n") }
        task.providerPlan?.let { output.append("Proposed plan: $it\n") }
        val accepted = keeper.readLine("Type APPROVE $taskId to authorize: ") == "APPROVE $taskId"
        if (accepted) engine.runtime.approveTask(TaskDefinitionId(taskId))
        return accepted
    }
}
