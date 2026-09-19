package com.hereliesaz.geministrator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.ComputeNodeDescriptor
import com.hereliesaz.geministrator.distributed.DistributedComputeUiState
import com.hereliesaz.geministrator.distributed.computeDelegationTarget
import com.hereliesaz.geministrator.distributed.isAssignedToRole
import com.hereliesaz.geministrator.distributed.isComputeDelegatable
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId

@Composable
internal fun ComputeDelegationScreen(
    runtimeState: ApplicationRuntimeState,
    distributedComputeState: DistributedComputeUiState,
    onAssignWorkflow: (WorkflowDefinitionId, ComputeDelegationTarget) -> Unit,
    onAssignRole: (WorkflowDefinitionId, RoleDefinitionId, ComputeDelegationTarget) -> Unit,
    onAssignTask: (WorkflowDefinitionId, TaskDefinitionId, ComputeDelegationTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = (runtimeState as? ApplicationRuntimeState.Live)?.presentation
    val localNodeId = distributedComputeState.configuration.nodeId
    val nodes = distributedComputeState.onlineNodes
        .distinctBy(ComputeNodeDescriptor::nodeId)
        .sortedWith(
            compareByDescending<ComputeNodeDescriptor> { it.nodeId == localNodeId }
                .thenBy { it.displayName.lowercase() },
        )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("COMPUTE", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            "Coordinate where work runs. Assign the whole workflow, route a role, or override a single task. " +
                "Assignments are enforced by the relay rather than treated as scheduling suggestions.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        ComputeSectionLabel("Fleet")
        AzphaltRecord(
            seed = "compute-fleet-status",
            eyebrow = "Compute pool",
            title = when {
                distributedComputeState.connected ->
                    nodes.size.toString() + " connected device" + if (nodes.size == 1) "" else "s"
                distributedComputeState.ready -> "Configured · offline"
                else -> "Compute pool not configured"
            },
            body = distributedComputeState.lastError
                ?: if (distributedComputeState.connected) {
                    "Connected hardware advertises capacity, accelerators, models, and supported executor kinds."
                } else {
                    "Configure the relay and this device in Settings before delegating remote work."
                },
            endCap = if (distributedComputeState.connected) "Online" else "Setup",
        )

        nodes.forEach { node ->
            ComputeNodeRecord(node = node, isLocal = node.nodeId == localNodeId)
        }

        if (live == null) {
            ComputeSectionLabel("Assignments")
            AzphaltRecord(
                seed = "compute-no-workflow",
                eyebrow = "No live workflow",
                title = "Nothing to delegate yet",
                body = "Launch or select a workflow run, then return here to assign its work to connected hardware.",
                endCap = "Idle",
            )
            return@Column
        }

        val definition = live.definition
        val delegatableTasks = definition.tasks.filter(TaskDefinition::isComputeDelegatable)
        val remoteWorkers = nodes.filter { node ->
            node.nodeId != localNodeId && node.acceptsWork
        }

        ComputeSectionLabel("Workflow")
        AzphaltRecord(
            seed = "compute-workflow-" + definition.id.value,
            eyebrow = "Workflow",
            title = definition.name,
            body = delegatableTasks.size.toString() + " delegatable task" +
                if (delegatableTasks.size == 1) " · " else "s · " +
                assignmentLabel(delegatableTasks, nodes),
            endCap = "Entire graph",
            well = {
                DelegationTargets(
                    tasks = delegatableTasks,
                    nodes = remoteWorkers,
                    onAssign = { target -> onAssignWorkflow(definition.id, target) },
                )
            },
        )

        ComputeSectionLabel("Roles")
        val rolesById = live.roles.associateBy(RoleDefinition::id)
        val roleIds = definition.tasks
            .mapNotNull { task -> task.roleId }
            .distinct()

        if (roleIds.isEmpty()) {
            Text(
                "This workflow has no role-backed tasks.",
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
        } else {
            roleIds.forEach { roleId ->
                val roleTasks = definition.tasks.filter {
                    it.isAssignedToRole(roleId) && it.isComputeDelegatable()
                }
                if (roleTasks.isEmpty()) return@forEach
                val role = rolesById[roleId]
                AzphaltRecord(
                    seed = "compute-role-" + roleId.value,
                    eyebrow = "Role · " + roleTasks.size + " task" + if (roleTasks.size == 1) "" else "s",
                    title = role?.name ?: roleId.value,
                    body = assignmentLabel(roleTasks, nodes),
                    endCap = "Role",
                    well = {
                        DelegationTargets(
                            tasks = roleTasks,
                            nodes = remoteWorkers,
                            onAssign = { target -> onAssignRole(definition.id, roleId, target) },
                        )
                    },
                )
            }
        }

        ComputeSectionLabel("Task overrides")
        definition.tasks.filter(TaskDefinition::isComputeDelegatable).forEach { task ->
            AzphaltRecord(
                seed = "compute-task-" + task.id.value,
                eyebrow = task.roleId?.let { rolesById[it]?.name } ?: "Task",
                title = task.name,
                body = assignmentLabel(listOf(task), nodes),
                endCap = "Override",
                well = {
                    DelegationTargets(
                        tasks = listOf(task),
                        nodes = remoteWorkers,
                        onAssign = { target -> onAssignTask(definition.id, task.id, target) },
                    )
                },
            )
        }
    }
}

@Composable
private fun ComputeNodeRecord(
    node: ComputeNodeDescriptor,
    isLocal: Boolean,
) {
    AzphaltRecord(
        seed = "compute-fleet-" + node.nodeId,
        eyebrow = node.platform.name + if (isLocal) " · THIS DEVICE" else "",
        title = node.displayName,
        body = buildString {
            append(node.architecture)
            append(" · ")
            append(node.logicalProcessors)
            append(" logical CPUs · ")
            append(formatMemory(node.memoryMiB))
            if (node.accelerators.isNotEmpty()) {
                append("\n")
                append(node.accelerators.joinToString(" · ") { it.name.uppercase() })
            }
            if (node.installedModelIds.isNotEmpty()) {
                append(" · ")
                append(node.installedModelIds.size)
                append(" model")
                if (node.installedModelIds.size != 1) append("s")
            }
            if (node.supportedExecutorKinds.isNotEmpty()) {
                append("\n")
                append(node.supportedExecutorKinds.sorted().joinToString(" · "))
            }
        },
        endCap = when {
            isLocal -> "Origin"
            node.acceptsWork ->
                node.maxParallelLeases.toString() + " slot" + if (node.maxParallelLeases == 1) "" else "s"
            else -> "Observe only"
        },
    )
}

@Composable
private fun DelegationTargets(
    tasks: List<TaskDefinition>,
    nodes: List<ComputeNodeDescriptor>,
    onAssign: (ComputeDelegationTarget) -> Unit,
) {
    val targetKeys = tasks.map { it.computeDelegationTarget().key() }.distinct()
    val selected = targetKeys.singleOrNull()

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AzphaltPill(
            label = "Local",
            seed = "compute-target-local",
            selected = selected == ComputeDelegationTarget.Local.key(),
            onClick = { onAssign(ComputeDelegationTarget.Local) },
        )
        AzphaltPill(
            label = "Any device",
            seed = "compute-target-any",
            selected = selected == ComputeDelegationTarget.AnyRemote.key(),
            onClick = { onAssign(ComputeDelegationTarget.AnyRemote) },
        )
        nodes.forEach { node ->
            val target = ComputeDelegationTarget.Node(node.nodeId)
            AzphaltPill(
                label = node.displayName,
                seed = "compute-target-" + node.nodeId,
                selected = selected == target.key(),
                endCap = node.platform.name,
                onClick = { onAssign(target) },
            )
        }
    }
}

private fun assignmentLabel(
    tasks: List<TaskDefinition>,
    nodes: List<ComputeNodeDescriptor>,
): String {
    if (tasks.isEmpty()) return "No delegatable work"
    val targets = tasks.map(TaskDefinition::computeDelegationTarget).distinctBy { it.key() }
    if (targets.size != 1) return "Mixed assignment"
    return when (val target = targets.single()) {
        ComputeDelegationTarget.Local -> "Runs on the originating device"
        ComputeDelegationTarget.AnyRemote -> "Any eligible connected device"
        is ComputeDelegationTarget.Node -> {
            val node = nodes.firstOrNull { it.nodeId == target.nodeId }
            if (node == null) {
                "Pinned to " + target.nodeId + " · currently offline"
            } else {
                "Pinned to " + node.displayName
            }
        }
    }
}

private fun ComputeDelegationTarget.key(): String = when (this) {
    ComputeDelegationTarget.Local -> "local"
    ComputeDelegationTarget.AnyRemote -> "remote:any"
    is ComputeDelegationTarget.Node -> "remote:" + nodeId
}

private fun formatMemory(memoryMiB: Long): String =
    if (memoryMiB >= 1024) {
        val wholeGiB = memoryMiB / 1024
        val remainder = memoryMiB % 1024
        if (remainder == 0L) wholeGiB.toString() + " GiB" else memoryMiB.toString() + " MiB"
    } else {
        memoryMiB.toString() + " MiB"
    }

@Composable
private fun ComputeSectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}
