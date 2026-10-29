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
    /** Labels of the creatures doing programmatic work: they wear the neuron, whatever they are called. */
    val programmaticLabels: Set<String> = emptySet(),
)

/**
 * Projects orchestration truth into the living terrarium without pretending every workflow node is
 * an agent. [TaskExecutor.RoleAgent] tasks become agent creatures; every other executor that does
 * work ([TaskExecutor.Script], [TaskExecutor.TestRunner], [TaskExecutor.RepositoryOperation],
 * [TaskExecutor.Deployment], [TaskExecutor.NestedWorkflow], [TaskExecutor.GitHubAction],
 * [TaskExecutor.ExternalService]) becomes the neuron instead: a real node, not an adornment, so the
 * swarm doubles as a standing flowchart. Human approval is worn as clothing, and
 * creature-to-creature flow stays a synaptic relationship.
 */
internal fun projectWorkflowTerrarium(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    persistedPositions: Map<String, H2g2TerrariumPosition> = emptyMap(),
): WorkflowTerrariumProjection {
    val tasksById = definition.tasks.associateBy(TaskDefinition::id)
    val rolesById = roles.associateBy(RoleDefinition::id)
    val agentTasks = definition.tasks.filter { it.effectiveExecutor().isTerrariumCreature() }
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

    agentTasks.forEach { downstream ->
        downstream.dependsOn.forEach { dependencyId ->
            collectDependencyPresentation(
                dependencyId = dependencyId,
                downstreamAgentId = downstream.id,
                tasksById = tasksById,
                agentIds = agentIds,
                relationships = relationships,
                adornments = adornments,
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
        serviceVisits = emptyList(),
        programmaticLabels = agentTasks
            .filterNot { task -> (run.taskRuns[task.id]?.executor ?: task.effectiveExecutor()).isAgent() }
            .mapNotNullTo(hashSetOf()) { task -> subjects.firstOrNull { it.node.id == task.id.value }?.node?.label },
    )
}

private fun TaskExecutor.isAgent(): Boolean = when (this) {
    is TaskExecutor.RoleAgent -> true
    is TaskExecutor.Distributed -> delegate.isAgent()
    else -> false
}

private fun collectDependencyPresentation(
    dependencyId: TaskDefinitionId,
    downstreamAgentId: TaskDefinitionId,
    tasksById: Map<TaskDefinitionId, TaskDefinition>,
    agentIds: Set<TaskDefinitionId>,
    relationships: MutableMap<String, H2g2TerrariumRelationship>,
    adornments: MutableMap<String, MutableList<H2g2SwarmAdornment>>,
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

    dependency.effectiveExecutor().dependencyAdornmentManifestation()?.let { manifestation ->
        adornments.getOrPut(downstreamAgentId.value) { mutableListOf() } += h2g2SwarmAdornment(
            dependencyId = "${dependency.id.value}->${downstreamAgentId.value}",
            manifestation = manifestation,
        )
    }

    dependency.dependsOn.forEach { upstream ->
        collectDependencyPresentation(
            dependencyId = upstream,
            downstreamAgentId = downstreamAgentId,
            tasksById = tasksById,
            agentIds = agentIds,
            relationships = relationships,
            adornments = adornments,
            visited = visited,
        )
    }
}

// Every executor but HumanApproval is promoted to a full creature
// by isTerrariumCreature() above and never reach this function as a *dependency's* executor while
// still classified that way; the branches stay for exhaustiveness and for any future executor kind
// that opts back out of creature status.
private fun TaskExecutor.dependencyAdornmentManifestation(): H2g2DependencyManifestation? = when (this) {
    is TaskExecutor.HumanApproval,
    is TaskExecutor.Deployment,
    -> H2g2DependencyManifestation.Clothing

    is TaskExecutor.TestRunner,
    is TaskExecutor.RepositoryOperation,
    is TaskExecutor.NestedWorkflow,
    is TaskExecutor.Script,
    -> H2g2DependencyManifestation.Tool

    is TaskExecutor.ExternalService,
    is TaskExecutor.GitHubAction,
    -> H2g2DependencyManifestation.Tool

    is TaskExecutor.RoleAgent -> H2g2DependencyManifestation.Synapse
    is TaskExecutor.Distributed -> delegate.dependencyAdornmentManifestation()
}

/**
 * Whether this executor gets its own creature node in the terrarium. True for agent-driven work
 * and for every programmatic executor, local or remote, rendered as the neuron
 * ([CreatureRigMapping.PROGRAMMATIC_RIG]) rather than an agent mascot. Only
 * [TaskExecutor.HumanApproval] stays worn (clothing): a person's sign-off is not a step the swarm runs.
 */
private fun TaskExecutor.isTerrariumCreature(): Boolean = when (this) {
    is TaskExecutor.RoleAgent,
    is TaskExecutor.Script,
    is TaskExecutor.TestRunner,
    is TaskExecutor.RepositoryOperation,
    is TaskExecutor.Deployment,
    is TaskExecutor.NestedWorkflow,
    is TaskExecutor.ExternalService,
    is TaskExecutor.GitHubAction,
    -> true

    is TaskExecutor.HumanApproval -> false

    is TaskExecutor.Distributed -> delegate.isTerrariumCreature()
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

/**
 * A stand-in run for showing a workflow with no run behind it (the fish bowl): no task has a run,
 * so every creature is at rest. Never persisted.
 */
internal fun restingWorkflowRun(definition: WorkflowDefinition): WorkflowRun = WorkflowRun(
    id = com.hereliesaz.geministrator.domain.WorkflowRunId("bowl--${definition.id.value}"),
    projectId = com.hereliesaz.geministrator.domain.ProjectId("bowl"),
    workflowDefinitionId = definition.id,
    objective = definition.description ?: definition.name,
    status = com.hereliesaz.geministrator.domain.WorkflowRunStatus.Created,
    taskRuns = emptyMap(),
    createdAtEpochMillis = 0,
    updatedAtEpochMillis = 0,
)
