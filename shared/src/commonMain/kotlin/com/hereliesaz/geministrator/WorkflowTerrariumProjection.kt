package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2DependencyManifestation
import com.hereliesaz.conveyance.h2g2.H2g2SwarmAdornment
import com.hereliesaz.conveyance.h2g2.H2g2SwarmIdentityKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumServiceVisit
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumSubject
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.conveyance.h2g2.h2g2SwarmAdornment
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
import kotlin.math.max

internal const val TERRARIUM_ORCHESTRATOR_ID: String = "__haive_orchestrator__"

internal data class WorkflowTerrariumProjection(
    val subjects: List<H2g2TerrariumSubject>,
    val relationships: List<H2g2TerrariumRelationship>,
    /** Worn/carried dependencies keyed by the downstream creature id. */
    val adornments: Map<String, List<H2g2SwarmAdornment>>,
    /** External/non-resident services that physically visit the terrarium and leave again. */
    val serviceVisits: List<H2g2TerrariumServiceVisit>,
)

/**
 * Projects orchestration truth into the living terrarium without pretending every workflow node is
 * an agent. Only [TaskExecutor.RoleAgent] tasks become creatures. Mechanical dependencies may
 * become tools or clothing, external services become transient visiting vehicles, and agent-to-agent
 * flow stays a synaptic relationship.
 */
internal fun projectWorkflowTerrarium(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    persistedPositions: Map<String, H2g2TerrariumPosition> = emptyMap(),
): WorkflowTerrariumProjection {
    val tasksById = definition.tasks.associateBy(TaskDefinition::id)
    val rolesById = roles.associateBy(RoleDefinition::id)
    val agentTasks = definition.tasks.filter { it.effectiveExecutor() is TaskExecutor.RoleAgent }
    val agentIds = agentTasks.mapTo(linkedSetOf(), TaskDefinition::id)

    val explicitOrchestrator = agentTasks.firstOrNull { task ->
        val runRole = run.taskRuns[task.id]?.assignedRoleId?.let(rolesById::get)
        val definitionRole = task.roleId?.let(rolesById::get)
        (runRole ?: definitionRole)?.name == "Orchestrator"
    }
    val orchestratorId = explicitOrchestrator?.id?.value ?: TERRARIUM_ORCHESTRATOR_ID

    val depthCache = mutableMapOf<TaskDefinitionId, Int>()
    fun depth(taskId: TaskDefinitionId, visiting: MutableSet<TaskDefinitionId> = mutableSetOf()): Int {
        depthCache[taskId]?.let { return it }
        if (!visiting.add(taskId)) return 0
        val task = tasksById[taskId] ?: return 0
        val result = if (task.dependsOn.isEmpty()) 0 else 1 + task.dependsOn.maxOf { dependency ->
            depth(dependency, visiting)
        }
        visiting.remove(taskId)
        depthCache[taskId] = result
        return result
    }

    val maxDepth = max(1, agentTasks.maxOfOrNull { depth(it.id) } ?: 0)
    val agentsByDepth = agentTasks.groupBy { depth(it.id) }
    val fallbackPositions = buildMap<String, H2g2TerrariumPosition> {
        if (explicitOrchestrator == null) {
            put(TERRARIUM_ORCHESTRATOR_ID, H2g2TerrariumPosition(.5f, .14f))
        }
        agentsByDepth.forEach { (layerDepth, tasks) ->
            val sorted = tasks.sortedBy { it.id.value }
            sorted.forEachIndexed { index, task ->
                val denominator = (sorted.size + 1).toFloat()
                val x = .16f + (layerDepth.toFloat() / maxDepth.toFloat()) * .68f
                val y = (index + 1).toFloat() / denominator
                val jitter = terrariumJitter(task.id.value)
                put(
                    task.id.value,
                    H2g2TerrariumPosition(
                        x = (x + jitter.first).coerceIn(.08f, .92f),
                        y = (y + jitter.second).coerceIn(.12f, .9f),
                    ),
                )
            }
        }
    }

    val subjects = buildList {
        if (explicitOrchestrator == null) {
            add(
                H2g2TerrariumSubject(
                    node = H2g2WorkflowNode(
                        id = TERRARIUM_ORCHESTRATOR_ID,
                        label = "Orchestrator",
                        subtitle = definition.name,
                        hueSeed = "haive-orchestrator",
                        state = run.status.toTerrariumState(),
                        detail = "The Aive orchestrator",
                    ),
                    position = persistedPositions[TERRARIUM_ORCHESTRATOR_ID]
                        ?: fallbackPositions.getValue(TERRARIUM_ORCHESTRATOR_ID),
                    identitySeed = "haive-orchestrator",
                    identityKind = H2g2SwarmIdentityKind.Orchestrator,
                ),
            )
        }

        agentTasks.forEach { task ->
            val taskRun = run.taskRuns[task.id]
            val executor = taskRun?.executor ?: task.effectiveExecutor()
            val role = taskRun?.assignedRoleId?.let(rolesById::get) ?: task.roleId?.let(rolesById::get)
            val identity = role?.name ?: executor.displayName()
            val isOrchestrator = task.id == explicitOrchestrator?.id
            val parent = birthParentFor(task, tasksById, agentIds, orchestratorId)
            add(
                H2g2TerrariumSubject(
                    node = H2g2WorkflowNode(
                        id = task.id.value,
                        label = identity,
                        subtitle = task.name,
                        hueSeed = identity,
                        state = taskRun?.status.toTerrariumState(),
                        progress = taskRun?.progress,
                        detail = task.objective,
                    ),
                    position = persistedPositions[task.id.value]
                        ?: fallbackPositions.getValue(task.id.value),
                    birthParentId = parent?.takeUnless { it == task.id.value },
                    identitySeed = "${role?.id?.value ?: executor.displayName()}:${task.id.value}",
                    identityKind = if (isOrchestrator) {
                        H2g2SwarmIdentityKind.Orchestrator
                    } else {
                        H2g2SwarmIdentityKind.Generated
                    },
                ),
            )
        }
    }
    val subjectPositions = subjects.associate { it.node.id to it.position }

    val relationships = linkedMapOf<String, H2g2TerrariumRelationship>()
    val adornments = linkedMapOf<String, MutableList<H2g2SwarmAdornment>>()
    val serviceVisits = linkedMapOf<String, H2g2TerrariumServiceVisit>()

    agentTasks.forEach { downstream ->
        downstream.dependsOn.forEach { dependencyId ->
            collectDependencyPresentation(
                dependencyId = dependencyId,
                downstreamAgentId = downstream.id,
                tasksById = tasksById,
                agentIds = agentIds,
                run = run,
                targetPosition = subjectPositions.getValue(downstream.id.value),
                relationships = relationships,
                adornments = adornments,
                serviceVisits = serviceVisits,
                visited = mutableSetOf(),
            )
        }
    }

    // Root agents are visibly delegated by the queen/orchestrator instead of appearing from nowhere.
    agentTasks.filter { task -> nearestUpstreamAgents(task, tasksById, agentIds).isEmpty() }
        .filterNot { it.id.value == orchestratorId }
        .forEach { task ->
            relationships.putIfAbsent(
                "spawn:$orchestratorId:${task.id.value}",
                H2g2TerrariumRelationship(
                    from = orchestratorId,
                    to = task.id.value,
                    kind = H2g2TerrariumRelationshipKind.Spawn,
                    active = taskRunIsNew(run.taskRuns[task.id]),
                ),
            )
        }

    return WorkflowTerrariumProjection(
        subjects = subjects,
        relationships = relationships.values.toList(),
        adornments = adornments.mapValues { (_, values) -> values.distinctBy(H2g2SwarmAdornment::dependencyId) },
        serviceVisits = serviceVisits.values.toList(),
    )
}

private fun collectDependencyPresentation(
    dependencyId: TaskDefinitionId,
    downstreamAgentId: TaskDefinitionId,
    tasksById: Map<TaskDefinitionId, TaskDefinition>,
    agentIds: Set<TaskDefinitionId>,
    run: WorkflowRun,
    targetPosition: H2g2TerrariumPosition,
    relationships: MutableMap<String, H2g2TerrariumRelationship>,
    adornments: MutableMap<String, MutableList<H2g2SwarmAdornment>>,
    serviceVisits: MutableMap<String, H2g2TerrariumServiceVisit>,
    visited: MutableSet<TaskDefinitionId>,
) {
    if (!visited.add(dependencyId)) return
    val dependency = tasksById[dependencyId] ?: return
    if (dependencyId in agentIds) {
        relationships.putIfAbsent(
            "dependency:${dependencyId.value}:${downstreamAgentId.value}",
            H2g2TerrariumRelationship(
                from = dependencyId.value,
                to = downstreamAgentId.value,
                kind = H2g2TerrariumRelationshipKind.Dependency,
            ),
        )
        return
    }

    val executor = dependency.effectiveExecutor()
    when (executor) {
        is TaskExecutor.ExternalService -> addServiceVisit(
            dependency = dependency,
            downstreamAgentId = downstreamAgentId,
            taskRun = run.taskRuns[dependency.id],
            serviceName = executor.service,
            operation = executor.operation,
            targetPosition = targetPosition,
            serviceVisits = serviceVisits,
        )
        is TaskExecutor.GitHubAction -> addServiceVisit(
            dependency = dependency,
            downstreamAgentId = downstreamAgentId,
            taskRun = run.taskRuns[dependency.id],
            serviceName = "GitHub",
            operation = executor.workflow,
            targetPosition = targetPosition,
            serviceVisits = serviceVisits,
        )
        else -> executor.dependencyAdornmentManifestation()?.let { manifestation ->
            adornments.getOrPut(downstreamAgentId.value) { mutableListOf() } += h2g2SwarmAdornment(
                dependencyId = "${dependency.id.value}->${downstreamAgentId.value}",
                manifestation = manifestation,
            )
        }
    }

    dependency.dependsOn.forEach { upstream ->
        collectDependencyPresentation(
            dependencyId = upstream,
            downstreamAgentId = downstreamAgentId,
            tasksById = tasksById,
            agentIds = agentIds,
            run = run,
            targetPosition = targetPosition,
            relationships = relationships,
            adornments = adornments,
            serviceVisits = serviceVisits,
            visited = visited,
        )
    }
}

private fun addServiceVisit(
    dependency: TaskDefinition,
    downstreamAgentId: TaskDefinitionId,
    taskRun: TaskRun?,
    serviceName: String,
    operation: String?,
    targetPosition: H2g2TerrariumPosition,
    serviceVisits: MutableMap<String, H2g2TerrariumServiceVisit>,
) {
    val attempt = taskRun?.attempt ?: 1
    val visitKey = "service:${dependency.id.value}:${downstreamAgentId.value}:$attempt"
    serviceVisits[visitKey] = H2g2TerrariumServiceVisit(
        id = visitKey,
        serviceName = serviceName,
        operation = operation,
        target = targetPosition,
        active = taskRun?.status.isServiceVisitActive(),
    )
}

private fun TaskRunStatus?.isServiceVisitActive(): Boolean = this in setOf(
    TaskRunStatus.Planning,
    TaskRunStatus.Running,
    TaskRunStatus.Verifying,
    TaskRunStatus.Retrying,
)

private fun TaskExecutor.dependencyAdornmentManifestation(): H2g2DependencyManifestation? = when (this) {
    is TaskExecutor.HumanApproval,
    is TaskExecutor.Deployment,
    -> H2g2DependencyManifestation.Clothing

    is TaskExecutor.TestRunner,
    is TaskExecutor.RepositoryOperation,
    is TaskExecutor.NestedWorkflow,
    -> H2g2DependencyManifestation.Tool

    is TaskExecutor.ExternalService,
    is TaskExecutor.GitHubAction,
    -> null

    is TaskExecutor.RoleAgent -> H2g2DependencyManifestation.Synapse
    is TaskExecutor.Distributed -> delegate.dependencyAdornmentManifestation()
}

private fun birthParentFor(
    task: TaskDefinition,
    tasksById: Map<TaskDefinitionId, TaskDefinition>,
    agentIds: Set<TaskDefinitionId>,
    orchestratorId: String,
): String? {
    val moaRoot = task.id.value.substringBefore("--moa-").takeIf { "--moa-" in task.id.value }
    if (moaRoot != null && TaskDefinitionId(moaRoot) in agentIds) return moaRoot
    return nearestUpstreamAgents(task, tasksById, agentIds).singleOrNull()?.value ?: orchestratorId
}

private fun nearestUpstreamAgents(
    task: TaskDefinition,
    tasksById: Map<TaskDefinitionId, TaskDefinition>,
    agentIds: Set<TaskDefinitionId>,
): Set<TaskDefinitionId> {
    val result = linkedSetOf<TaskDefinitionId>()
    val queue = ArrayDeque<TaskDefinitionId>()
    task.dependsOn.forEach(queue::addLast)
    val visited = mutableSetOf<TaskDefinitionId>()
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        if (id in agentIds) {
            result += id
        } else {
            tasksById[id]?.dependsOn?.forEach(queue::addLast)
        }
    }
    return result
}

private fun taskRunIsNew(run: TaskRun?): Boolean = run == null || run.status in setOf(
    TaskRunStatus.Created,
    TaskRunStatus.Blocked,
    TaskRunStatus.Ready,
    TaskRunStatus.Planning,
)

private fun TaskRunStatus?.toTerrariumState(): H2g2WorkflowState = when (this) {
    null, TaskRunStatus.Created -> H2g2WorkflowState.Pending
    TaskRunStatus.Blocked -> H2g2WorkflowState.Blocked
    TaskRunStatus.Ready -> H2g2WorkflowState.Ready
    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> H2g2WorkflowState.Gate
    TaskRunStatus.Planning, TaskRunStatus.Running, TaskRunStatus.Verifying, TaskRunStatus.Retrying -> H2g2WorkflowState.Active
    TaskRunStatus.Completed -> H2g2WorkflowState.Complete
    TaskRunStatus.Failed, TaskRunStatus.Cancelled -> H2g2WorkflowState.Failed
}

private fun com.hereliesaz.geministrator.domain.WorkflowRunStatus.toTerrariumState(): H2g2WorkflowState = when (this) {
    com.hereliesaz.geministrator.domain.WorkflowRunStatus.Created -> H2g2WorkflowState.Pending
    com.hereliesaz.geministrator.domain.WorkflowRunStatus.Running -> H2g2WorkflowState.Active
    com.hereliesaz.geministrator.domain.WorkflowRunStatus.AwaitingHuman -> H2g2WorkflowState.Gate
    com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed -> H2g2WorkflowState.Complete
    com.hereliesaz.geministrator.domain.WorkflowRunStatus.Failed,
    com.hereliesaz.geministrator.domain.WorkflowRunStatus.Cancelled,
    -> H2g2WorkflowState.Failed
}

private fun terrariumJitter(seed: String): Pair<Float, Float> {
    var hash = 0x811C9DC5.toInt()
    seed.forEach { character ->
        hash = hash xor character.code
        hash *= 0x01000193
    }
    val x = (((hash ushr 8) and 0xFF) / 255f - .5f) * .035f
    val y = (((hash ushr 16) and 0xFF) / 255f - .5f) * .055f
    return x to y
}
