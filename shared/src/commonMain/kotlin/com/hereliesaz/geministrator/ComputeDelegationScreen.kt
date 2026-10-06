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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.compute_any_device
import com.hereliesaz.geministrator.resources.compute_any_eligible_connected_device
import com.hereliesaz.geministrator.resources.compute_assignments
import com.hereliesaz.geministrator.resources.compute_assignments_are_enforced_by_the_relay
import com.hereliesaz.geministrator.resources.compute_compute
import com.hereliesaz.geministrator.resources.compute_compute_pool
import com.hereliesaz.geministrator.resources.compute_compute_pool_not_configured
import com.hereliesaz.geministrator.resources.compute_configure_the_relay_and_this_device
import com.hereliesaz.geministrator.resources.compute_configured_offline
import com.hereliesaz.geministrator.resources.compute_connected_device
import com.hereliesaz.geministrator.resources.compute_connected_hardware_advertises_capacity_accelerators_models
import com.hereliesaz.geministrator.resources.compute_coordinate_where_work_runs_assign_the
import com.hereliesaz.geministrator.resources.compute_currently_offline
import com.hereliesaz.geministrator.resources.compute_delegatable_task
import com.hereliesaz.geministrator.resources.compute_entire_graph
import com.hereliesaz.geministrator.resources.compute_fleet
import com.hereliesaz.geministrator.resources.compute_idle
import com.hereliesaz.geministrator.resources.compute_launch_or_select_a_workflow_run
import com.hereliesaz.geministrator.resources.compute_local
import com.hereliesaz.geministrator.resources.compute_logical_cpus
import com.hereliesaz.geministrator.resources.compute_mixed_assignment
import com.hereliesaz.geministrator.resources.compute_model
import com.hereliesaz.geministrator.resources.compute_no_delegatable_work
import com.hereliesaz.geministrator.resources.compute_no_live_workflow
import com.hereliesaz.geministrator.resources.compute_nothing_to_delegate_yet
import com.hereliesaz.geministrator.resources.compute_observe_only
import com.hereliesaz.geministrator.resources.compute_online
import com.hereliesaz.geministrator.resources.compute_origin
import com.hereliesaz.geministrator.resources.compute_override
import com.hereliesaz.geministrator.resources.compute_pinned_to
import com.hereliesaz.geministrator.resources.compute_role
import com.hereliesaz.geministrator.resources.compute_role_2
import com.hereliesaz.geministrator.resources.compute_roles
import com.hereliesaz.geministrator.resources.compute_runs_on_the_originating_device
import com.hereliesaz.geministrator.resources.compute_setup
import com.hereliesaz.geministrator.resources.compute_slot
import com.hereliesaz.geministrator.resources.compute_task
import com.hereliesaz.geministrator.resources.compute_task_2
import com.hereliesaz.geministrator.resources.compute_task_overrides
import com.hereliesaz.geministrator.resources.compute_this_device
import com.hereliesaz.geministrator.resources.compute_this_workflow_has_no_role_backed
import com.hereliesaz.geministrator.resources.compute_workflow
import org.jetbrains.compose.resources.stringResource

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
        Text(stringResource(Res.string.compute_compute), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        Text(
            stringResource(Res.string.compute_coordinate_where_work_runs_assign_the) +
                stringResource(Res.string.compute_assignments_are_enforced_by_the_relay),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )

        ComputeSectionLabel(stringResource(Res.string.compute_fleet))
        AzphaltRecord(
            seed = "compute-fleet-status",
            eyebrow = stringResource(Res.string.compute_compute_pool),
            title = when {
                distributedComputeState.connected ->
                    nodes.size.toString() + stringResource(Res.string.compute_connected_device) + if (nodes.size == 1) "" else "s"
                distributedComputeState.ready -> stringResource(Res.string.compute_configured_offline)
                else -> stringResource(Res.string.compute_compute_pool_not_configured)
            },
            body = distributedComputeState.lastError
                ?: if (distributedComputeState.connected) {
                    stringResource(Res.string.compute_connected_hardware_advertises_capacity_accelerators_models)
                } else {
                    stringResource(Res.string.compute_configure_the_relay_and_this_device)
                },
            endCap = if (distributedComputeState.connected) stringResource(Res.string.compute_online) else stringResource(Res.string.compute_setup),
        )

        nodes.forEach { node ->
            ComputeNodeRecord(node = node, isLocal = node.nodeId == localNodeId)
        }

        if (live == null) {
            ComputeSectionLabel(stringResource(Res.string.compute_assignments))
            AzphaltRecord(
                seed = "compute-no-workflow",
                eyebrow = stringResource(Res.string.compute_no_live_workflow),
                title = stringResource(Res.string.compute_nothing_to_delegate_yet),
                body = stringResource(Res.string.compute_launch_or_select_a_workflow_run),
                endCap = stringResource(Res.string.compute_idle),
            )
            return@Column
        }

        val definition = live.definition
        val delegatableTasks = definition.tasks.filter(TaskDefinition::isComputeDelegatable)
        val remoteWorkers = nodes.filter { node ->
            node.nodeId != localNodeId && node.acceptsWork
        }

        ComputeSectionLabel(stringResource(Res.string.compute_workflow))
        AzphaltRecord(
            seed = "compute-workflow-" + definition.id.value,
            eyebrow = stringResource(Res.string.compute_workflow),
            title = definition.name,
            body = buildString {
                append(delegatableTasks.size)
                append(stringResource(Res.string.compute_delegatable_task))
                if (delegatableTasks.size != 1) append("s")
                append(" · ")
                append(assignmentLabel(delegatableTasks, nodes))
            },
            endCap = stringResource(Res.string.compute_entire_graph),
            well = {
                DelegationTargets(
                    tasks = delegatableTasks,
                    nodes = remoteWorkers,
                    onAssign = { target -> onAssignWorkflow(definition.id, target) },
                )
            },
        )

        ComputeSectionLabel(stringResource(Res.string.compute_roles))
        val rolesById = live.roles.associateBy(RoleDefinition::id)
        val roleIds = definition.tasks
            .mapNotNull { task -> task.roleId }
            .distinct()

        if (roleIds.isEmpty()) {
            Text(
                stringResource(Res.string.compute_this_workflow_has_no_role_backed),
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
                    eyebrow = stringResource(Res.string.compute_role) + roleTasks.size + stringResource(Res.string.compute_task) + if (roleTasks.size == 1) "" else "s",
                    title = role?.name ?: roleId.value,
                    body = assignmentLabel(roleTasks, nodes),
                    endCap = stringResource(Res.string.compute_role_2),
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

        ComputeSectionLabel(stringResource(Res.string.compute_task_overrides))
        definition.tasks.filter(TaskDefinition::isComputeDelegatable).forEach { task ->
            AzphaltRecord(
                seed = "compute-task-" + task.id.value,
                eyebrow = task.roleId?.let { rolesById[it]?.name } ?: stringResource(Res.string.compute_task_2),
                title = task.name,
                body = assignmentLabel(listOf(task), nodes),
                endCap = stringResource(Res.string.compute_override),
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
        eyebrow = node.platform.name + if (isLocal) stringResource(Res.string.compute_this_device) else "",
        title = node.displayName,
        body = buildString {
            append(node.architecture)
            append(" · ")
            append(node.logicalProcessors)
            append(stringResource(Res.string.compute_logical_cpus))
            append(formatMemory(node.memoryMiB))
            if (node.accelerators.isNotEmpty()) {
                append("\n")
                append(node.accelerators.joinToString(" · ") { it.name.uppercase() })
            }
            if (node.installedModelIds.isNotEmpty()) {
                append(" · ")
                append(node.installedModelIds.size)
                append(stringResource(Res.string.compute_model))
                if (node.installedModelIds.size != 1) append("s")
            }
            if (node.supportedExecutorKinds.isNotEmpty()) {
                append("\n")
                append(node.supportedExecutorKinds.sorted().joinToString(" · "))
            }
        },
        endCap = when {
            isLocal -> stringResource(Res.string.compute_origin)
            node.acceptsWork ->
                node.maxParallelLeases.toString() + stringResource(Res.string.compute_slot) + if (node.maxParallelLeases == 1) "" else "s"
            else -> stringResource(Res.string.compute_observe_only)
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
            label = stringResource(Res.string.compute_local),
            seed = "compute-target-local",
            selected = selected == ComputeDelegationTarget.Local.key(),
            onClick = { onAssign(ComputeDelegationTarget.Local) },
        )
        AzphaltPill(
            label = stringResource(Res.string.compute_any_device),
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

@Composable
private fun assignmentLabel(
    tasks: List<TaskDefinition>,
    nodes: List<ComputeNodeDescriptor>,
): String {
    if (tasks.isEmpty()) return stringResource(Res.string.compute_no_delegatable_work)
    val targets = tasks.map(TaskDefinition::computeDelegationTarget).distinctBy { it.key() }
    if (targets.size != 1) return stringResource(Res.string.compute_mixed_assignment)
    return when (val target = targets.single()) {
        ComputeDelegationTarget.Local -> stringResource(Res.string.compute_runs_on_the_originating_device)
        ComputeDelegationTarget.AnyRemote -> stringResource(Res.string.compute_any_eligible_connected_device)
        is ComputeDelegationTarget.Node -> {
            val node = nodes.firstOrNull { it.nodeId == target.nodeId }
            if (node == null) {
                stringResource(Res.string.compute_pinned_to) + target.nodeId + stringResource(Res.string.compute_currently_offline)
            } else {
                stringResource(Res.string.compute_pinned_to) + node.displayName
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
