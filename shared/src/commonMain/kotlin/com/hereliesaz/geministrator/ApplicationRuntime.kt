package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.persistence.PersistenceCorruptionException
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.ManagedSessionFailure
import com.hereliesaz.geministrator.workflow.ManagedSessionGateway
import com.hereliesaz.geministrator.workflow.ProviderBackedManagedSessionGateway
import com.hereliesaz.geministrator.workflow.StarterWorkflowFactory
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.WorkflowApprovalService
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable
import com.hereliesaz.geministrator.workflow.WorkflowEngine
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeCoordinator
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface ApplicationRuntimeState {
    data object Loading : ApplicationRuntimeState
    data object NoProject : ApplicationRuntimeState
    data class NoRun(val project: Project, val roles: List<RoleDefinition> = emptyList()) : ApplicationRuntimeState
    data class Live(val presentation: LiveWorkflowPresentation) : ApplicationRuntimeState
    data class Disconnected(val message: String) : ApplicationRuntimeState
    data class ResumeFailed(val message: String, val isCorrupted: Boolean = false) : ApplicationRuntimeState
}

sealed class ApplicationRuntimeFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Disconnected(message: String, cause: Throwable? = null) : ApplicationRuntimeFailure(message, cause)
    class Resume(message: String, cause: Throwable? = null) : ApplicationRuntimeFailure(message, cause)
    class Corrupted(message: String, cause: Throwable? = null) : ApplicationRuntimeFailure(message, cause)
}

class WorkflowRuntimePublisher {
    private val mutableState = MutableStateFlow<ApplicationRuntimeState>(ApplicationRuntimeState.Loading)
    val state: StateFlow<ApplicationRuntimeState> = mutableState.asStateFlow()

    fun publish(state: ApplicationRuntimeState) {
        mutableState.value = state
    }
}

class ApplicationRuntime private constructor(
    val persistence: WorkflowPersistence,
    val providerRegistry: AgentProviderRegistry,
    val sessionGateway: ManagedSessionGateway,
    val engine: WorkflowEngine,
    val coordinator: WorkflowRuntimeCoordinator,
    val publisher: WorkflowRuntimePublisher,
    private val runtimeScope: CoroutineScope,
    private val roles: List<RoleDefinition>,
) {
    private data class Current(
        val project: Project,
        val definition: WorkflowDefinition,
        val state: WorkflowRuntimeState,
    )

    private var current: Current? = null
    private var currentGeneration: Long = 0L
    private var cycleJob: Job? = null
    private val runtimeMutex = Mutex()
    /** Non-null while the UI is viewing a historical run; cycling continues on [current]. */
    private var viewingRun: Pair<WorkflowDefinition, WorkflowRun>? = null
    val state: StateFlow<ApplicationRuntimeState> = publisher.state

    suspend fun loadLatest() {
        runtimeMutex.withLock {
            publisher.publish(ApplicationRuntimeState.Loading)
            try {
                val projects = persistence.projects.all()
                if (projects.isEmpty()) {
                    replaceCurrent(null)
                    publisher.publish(ApplicationRuntimeState.NoProject)
                    return@withLock
                }

                val latestProjectRun = projects
                    .flatMap { project ->
                        persistence.runs.byProject(project.id).map { run -> project to run }
                    }
                    .maxByOrNull { (_, run) -> run.updatedAtEpochMillis }

                if (latestProjectRun == null) {
                    replaceCurrent(null)
                    publisher.publish(
                        ApplicationRuntimeState.NoRun(
                            project = projects.maxByOrNull(Project::updatedAtEpochMillis) ?: projects.first(),
                            roles = roles,
                        ),
                    )
                    return@withLock
                }

                val (project, run) = latestProjectRun
                val definition = persistence.definitions.get(run.workflowDefinitionId)
                    ?: error("Workflow definition ${run.workflowDefinitionId.value} was not found")
                val runtimeState = try {
                    coordinator.resume(run.id)
                } catch (failure: Throwable) {
                    throw classifyResumeFailure(failure)
                }

                replaceCurrent(Current(project, definition, runtimeState))
                publishCurrent()
                startCycling()
            } catch (failure: Throwable) {
                replaceCurrent(null)
                publishFailure(failure)
            }
        }
    }

    suspend fun refresh() = loadLatest()

    suspend fun loadRunHistory(): List<Pair<Project, List<WorkflowRun>>> {
        val projects = persistence.projects.all()
        return projects.map { project ->
            project to persistence.runs.byProject(project.id)
                .sortedByDescending(WorkflowRun::updatedAtEpochMillis)
        }.sortedByDescending { (project, runs) ->
            runs.firstOrNull()?.updatedAtEpochMillis ?: project.updatedAtEpochMillis
        }
    }

    suspend fun switchToRun(runId: WorkflowRunId) {
        runtimeMutex.withLock {
            try {
                val run = persistence.runs.get(runId)
                    ?: error("Run ${runId.value} not found")
                val definition = persistence.definitions.get(run.workflowDefinitionId)
                    ?: error("Workflow definition ${run.workflowDefinitionId.value} not found")
                viewingRun = definition to run
                publishCurrent()
            } catch (failure: Throwable) {
                publishFailure(failure)
            }
        }
    }

    /** Clear any historical-run view and return to displaying the active run. */
    suspend fun clearRunView() {
        runtimeMutex.withLock {
            viewingRun = null
            publishCurrent()
        }
    }

    suspend fun launchStarterWorkflow(
        projectName: String,
        objective: String,
        repository: RepositoryRef? = null,
        existingProject: Project? = null,
    ) {
        runtimeMutex.withLock {
            val cleanProjectName = projectName.trim()
            val cleanObjective = objective.trim()
            require(cleanProjectName.isNotEmpty()) { "Project name is required" }
            require(cleanObjective.isNotEmpty()) { "Objective is required" }

            val normalizedRepository = repository?.let {
                val owner = it.owner.trim()
                val name = it.name.trim()
                require(owner.isNotEmpty()) { "Repository owner is required" }
                require(name.isNotEmpty()) { "Repository name is required" }
                RepositoryRef(
                    owner = owner,
                    name = name,
                    defaultBranch = it.defaultBranch?.trim()?.takeIf(String::isNotEmpty),
                )
            }
            val now = nowEpochMillis()
            val project = existingProject?.copy(
                name = cleanProjectName,
                repository = normalizedRepository ?: existingProject.repository,
                updatedAtEpochMillis = now,
            ) ?: Project(
                id = ProjectId("project-$now"),
                name = cleanProjectName,
                repository = normalizedRepository,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            )
            val definition = StarterWorkflowFactory.create(
                id = WorkflowDefinitionId("workflow-$now"),
                objective = cleanObjective,
            )
            val launchService = WorkflowLaunchService(
                preparer = WorkflowDefinitionPreparer(providerRegistry, roles),
                persistence = persistence,
                eventSink = RepositoryWorkflowEventSink(persistence.events),
                roles = roles,
            )
            val (prepared, runtimeState) = launchService.launch(
                project = project,
                definition = definition,
                workflowRunId = WorkflowRunId("run-$now"),
                objective = cleanObjective,
                nowEpochMillis = now,
                taskRunIdFactory = { id -> TaskRunId("run-$now-${id.value}") },
            )

            replaceCurrent(Current(project, prepared, runtimeState))
            publishCurrent()
            startCycling()
        }
    }

    suspend fun approveTask(taskDefinitionId: TaskDefinitionId) {
        runtimeMutex.withLock {
            val snapshot = requireNotNull(current) { "No active workflow is loaded" }
            val task = requireNotNull(snapshot.definition.tasks.firstOrNull { it.id == taskDefinitionId }) {
                "Task ${taskDefinitionId.value} is not defined"
            }
            val taskRun = requireNotNull(snapshot.state.run.taskRuns[taskDefinitionId]) {
                "Task ${taskDefinitionId.value} has no runtime state"
            }
            require(taskRun.status == TaskRunStatus.AwaitingApproval) {
                "Task ${taskDefinitionId.value} is not awaiting approval"
            }

            val now = nowEpochMillis()
            val nextState = if (taskRun.assignedProviderId != null) {
                val handle = requireNotNull(snapshot.state.handles[taskDefinitionId]) {
                    "Task ${taskDefinitionId.value} has no provider session to approve"
                }
                val eventSink = RepositoryWorkflowEventSink(persistence.events)
                val gateCoordinator = ApprovalGateCoordinator(
                    repository = persistence.approvalGates,
                    eventSink = eventSink,
                )
                val approvalService = WorkflowApprovalService(
                    gateRepository = persistence.approvalGates,
                    gateCoordinator = gateCoordinator,
                    sessionGateway = sessionGateway,
                    failureEscalationDecisionStore = persistence,
                )
                val gateId = ApprovalGateId(
                    "plan:${snapshot.state.run.id.value}:${taskDefinitionId.value}:${taskRun.attempt}",
                )
                if (persistence.approvalGates.get(gateId) == null) {
                    gateCoordinator.open(
                        id = gateId,
                        workflowRunId = snapshot.state.run.id,
                        taskDefinitionId = taskDefinitionId,
                        kind = ApprovalGateKind.PlanApproval,
                        reason = "Plan approval required for '${task.name}'",
                        requiresHuman = true,
                        nowEpochMillis = now,
                    )
                }

                val decision = approvalService.approvePlan(
                    gateId = gateId,
                    handle = handle,
                    decidedByRoleId = null,
                    note = "Approved in application",
                    nowEpochMillis = now,
                )
                val stateForCycle = if (decision.status == ApprovalGateStatus.Rejected) {
                    snapshot.state.copy(handles = snapshot.state.handles - taskDefinitionId)
                } else {
                    snapshot.state
                }
                coordinator.cycle(
                    project = snapshot.project,
                    definition = snapshot.definition,
                    state = stateForCycle,
                    nowEpochMillis = now,
                    artifactIdFactory = ::artifactId,
                )
            } else {
                require(task.effectiveExecutor() is TaskExecutor.HumanApproval) {
                    "Task ${taskDefinitionId.value} is not a human approval gate"
                }
                WorkflowRuntimeState(
                    run = engine.completeHumanApprovalTask(
                        definition = snapshot.definition,
                        run = snapshot.state.run,
                        taskDefinitionId = taskDefinitionId,
                        nowEpochMillis = now,
                    ),
                    handles = snapshot.state.handles,
                ).also {
                    coordinator.persist(snapshot.project, snapshot.definition, it)
                }
            }

            replaceCurrent(snapshot.copy(state = nextState))
            publishCurrent()
        }
    }

    fun close() {
        cycleJob?.cancel()
        cycleJob = null
        replaceCurrent(null)
        runtimeScope.cancel()
    }

    private fun replaceCurrent(next: Current?) {
        currentGeneration += 1L
        current = next
        viewingRun = null
    }

    private fun startCycling() {
        if (cycleJob?.isActive == true) return
        cycleJob = runtimeScope.launch {
            while (isActive) {
                delay(CYCLE_INTERVAL_MILLIS)
                var failed = false
                runtimeMutex.withLock {
                    val snapshot = current ?: return@withLock
                    val snapshotGeneration = currentGeneration
                    if (snapshot.state.run.status.isTerminal()) return@withLock

                    try {
                        val nextState = coordinator.cycle(
                            project = snapshot.project,
                            definition = snapshot.definition,
                            state = snapshot.state,
                            nowEpochMillis = nowEpochMillis(),
                            artifactIdFactory = ::artifactId,
                        )
                        if (currentGeneration != snapshotGeneration || current !== snapshot) return@withLock
                        replaceCurrent(snapshot.copy(state = nextState))
                        publishCurrent()
                    } catch (failure: Throwable) {
                        if (currentGeneration == snapshotGeneration && current === snapshot) {
                            publishFailure(classifyRuntimeFailure(failure))
                        }
                        failed = true
                    }
                }
                if (failed) delay(RETRY_BACKOFF_MILLIS)
            }
        }
    }

    private fun publishCurrent() {
        val viewing = viewingRun
        if (viewing != null) {
            val (definition, run) = viewing
            publisher.publish(
                ApplicationRuntimeState.Live(
                    LiveWorkflowPresentation(
                        definition = definition,
                        run = run,
                        roles = roles,
                    ),
                ),
            )
            return
        }
        val snapshot = current ?: return
        publisher.publish(
            ApplicationRuntimeState.Live(
                LiveWorkflowPresentation(
                    definition = snapshot.definition,
                    run = snapshot.state.run,
                    roles = roles,
                ),
            ),
        )
    }

    internal fun publishFailure(failure: Throwable) {
        val message = failure.message
            ?.takeIf(String::isNotBlank)
            ?: failure::class.simpleName.orEmpty().ifBlank { "Runtime failure" }
        publisher.publish(
            when (failure) {
                is ApplicationRuntimeFailure.Disconnected -> ApplicationRuntimeState.Disconnected(message)
                is ApplicationRuntimeFailure.Corrupted -> ApplicationRuntimeState.ResumeFailed(message, isCorrupted = true)
                else -> ApplicationRuntimeState.ResumeFailed(message)
            },
        )
    }

    suspend fun recoverFromCorruption() {
        val settingsPersistence = persistence as? SettingsWorkflowPersistence ?: return
        settingsPersistence.recoverFromCorruption()
        loadLatest()
    }

    suspend fun checkProviderHealth(): Map<String, Result<AgentCapabilities>> {
        val results = mutableMapOf<String, Result<AgentCapabilities>>()
        for (providerId in providerRegistry.providerIds) {
            val provider = providerRegistry.provider(providerId) ?: continue
            results[providerId.value] = runCatching { provider.capabilities() }
        }
        return results
    }

    suspend fun exportJson(): String? =
        (persistence as? SettingsWorkflowPersistence)?.exportJson()

    suspend fun importJson(encoded: String) {
        (persistence as? SettingsWorkflowPersistence)?.importJson(encoded)
        loadLatest()
    }

    suspend fun loadRunTimeline(): List<WorkflowEvent> {
        val runId = runtimeMutex.withLock { current?.state?.run?.id } ?: return emptyList()
        return persistence.events.forRun(runId)
    }

    suspend fun saveRole(role: RoleDefinition) {
        persistence.roles.put(role)
        loadLatest()
    }

    fun validateCurrentWorkflow(): List<String> {
        val definition = current?.definition ?: return emptyList()
        return WorkflowGraphValidator.validate(definition).map { it.humanReadable() }
    }

    suspend fun exportDiagnosticBundle(): String {
        val snapshot = runtimeMutex.withLock { current }
        val run = snapshot?.state?.run
        val definition = snapshot?.definition
        val events = if (run != null) persistence.events.forRun(run.id) else emptyList()

        val retryCount = events.count { it is com.hereliesaz.geministrator.events.RetryScheduled }
        val failureCount = events.count { it is com.hereliesaz.geministrator.events.TaskFailed }
        val escalationCount = events.count { it is com.hereliesaz.geministrator.events.TaskEscalated }
        val artifactCount = events.count { it is com.hereliesaz.geministrator.events.ArtifactCreated }
        val durationMs = if (run != null) run.updatedAtEpochMillis - run.createdAtEpochMillis else 0L

        val startsByTask = events
            .filterIsInstance<com.hereliesaz.geministrator.events.TaskStarted>()
            .groupBy { it.taskDefinitionId }
            .mapValues { (_, starts) -> starts.minOf { it.occurredAtEpochMillis } }
        val completionsByTask = events
            .filterIsInstance<com.hereliesaz.geministrator.events.TaskCompleted>()
            .associateBy { it.taskDefinitionId }

        return buildString {
            appendLine("{")
            appendLine("  \"schema\": \"haive-diagnostic-v1\",")
            appendLine("  \"runId\": ${jsonStr(run?.id?.value)},")
            appendLine("  \"objective\": ${jsonStr(run?.objective)},")
            appendLine("  \"status\": ${jsonStr(run?.status?.name)},")
            appendLine("  \"durationMs\": $durationMs,")
            appendLine("  \"taskCount\": ${definition?.tasks?.size ?: 0},")
            appendLine("  \"taskRunCount\": ${run?.taskRuns?.size ?: 0},")
            appendLine("  \"eventCount\": ${events.size},")
            appendLine("  \"retryCount\": $retryCount,")
            appendLine("  \"failureCount\": $failureCount,")
            appendLine("  \"escalationCount\": $escalationCount,")
            appendLine("  \"artifactCount\": $artifactCount,")
            appendLine("  \"taskRuns\": [")
            val taskRuns = run?.taskRuns?.values?.toList() ?: emptyList()
            taskRuns.forEachIndexed { index, taskRun ->
                val comma = if (index < taskRuns.size - 1) "," else ""
                val taskStart = startsByTask[taskRun.taskDefinitionId]
                val taskEnd = completionsByTask[taskRun.taskDefinitionId]?.occurredAtEpochMillis
                val taskDurationMs = if (taskStart != null && taskEnd != null) taskEnd - taskStart else "null"
                appendLine("    {\"id\": ${jsonStr(taskRun.taskDefinitionId.value)}, \"status\": ${jsonStr(taskRun.status.name)}, \"attempt\": ${taskRun.attempt}, \"durationMs\": $taskDurationMs, \"providerId\": ${jsonStr(taskRun.assignedProviderId?.value)}}$comma")
            }
            appendLine("  ]")
            append("}")
        }
    }

    private fun jsonStr(value: String?): String {
        if (value == null) return "null"
        val escaped = buildString {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '' -> append("\\f")
                    else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
                }
            }
        }
        return "\"$escaped\""
    }

    private fun WorkflowRunStatus.isTerminal() =
        this == WorkflowRunStatus.Completed ||
            this == WorkflowRunStatus.Failed ||
            this == WorkflowRunStatus.Cancelled

    private fun artifactId(
        taskRun: TaskRun,
        artifact: ProviderArtifact,
        index: Int,
    ) = ArtifactId("${taskRun.id.value}:${artifact.kind}:${taskRun.attempt}:$index")

    companion object {
        private const val CYCLE_INTERVAL_MILLIS = 1_000L
        private const val RETRY_BACKOFF_MILLIS = 2_000L

        internal fun classifyResumeFailure(failure: Throwable): ApplicationRuntimeFailure =
            classifyRuntimeFailure(failure)

        internal fun classifyRuntimeFailure(failure: Throwable): ApplicationRuntimeFailure = when (failure) {
            is ApplicationRuntimeFailure -> failure
            is PersistenceCorruptionException -> ApplicationRuntimeFailure.Corrupted(
                failure.message ?: "Workflow persistence is corrupted",
                failure,
            )
            is ManagedSessionFailure.ProviderUnavailable -> ApplicationRuntimeFailure.Disconnected(
                failure.message ?: "Provider runtime is unavailable",
                failure,
            )
            else -> ApplicationRuntimeFailure.Resume(
                failure.message ?: "Runtime operation failed",
                failure,
            )
        }

        suspend fun create(
            providers: Collection<AgentProvider>,
            scope: CoroutineScope,
            persistence: WorkflowPersistence = SettingsWorkflowPersistence.createDefault(),
            executorIntegrations: TaskExecutorIntegrationRegistry = TaskExecutorIntegrationRegistry.Empty,
        ): ApplicationRuntime {
            val runtimeJob = SupervisorJob(scope.coroutineContext[Job])
            val runtimeScope = CoroutineScope(scope.coroutineContext + runtimeJob)
            val registry = AgentProviderRegistry(providers)
            val gateway = ProviderBackedManagedSessionGateway(registry, runtimeScope)
            val publisher = WorkflowRuntimePublisher()

            fun build(roles: List<RoleDefinition>): ApplicationRuntime {
                val engine = WorkflowEngine(
                    sessionGateway = gateway,
                    roles = roles,
                    eventSink = RepositoryWorkflowEventSink(persistence.events),
                )
                val coordinator = WorkflowRuntimeCoordinator(
                    persistence = persistence,
                    engine = engine,
                    sessionGateway = gateway,
                    executorIntegrations = executorIntegrations,
                )
                return ApplicationRuntime(
                    persistence,
                    registry,
                    gateway,
                    engine,
                    coordinator,
                    publisher,
                    runtimeScope,
                    roles,
                )
            }

            val runtime = try {
                val roles = (BuiltInRoles.all + persistence.roles.all())
                    .associateBy(RoleDefinition::id)
                    .values
                    .toList()
                build(roles)
            } catch (failure: Throwable) {
                build(BuiltInRoles.all).also {
                    it.publishFailure(
                        ApplicationRuntimeFailure.Resume(
                            failure.message ?: "Runtime bootstrap failed",
                            failure,
                        ),
                    )
                }
            }

            if (runtime.state.value == ApplicationRuntimeState.Loading) {
                runtime.loadLatest()
            }
            return runtime
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
