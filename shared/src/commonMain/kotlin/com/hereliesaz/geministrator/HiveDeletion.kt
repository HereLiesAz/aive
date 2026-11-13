package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_delete
import com.hereliesaz.geministrator.resources.hive_delete_body
import com.hereliesaz.geministrator.resources.hive_delete_failed
import com.hereliesaz.geministrator.resources.hive_delete_keep
import com.hereliesaz.geministrator.resources.hive_delete_refused_circuit
import com.hereliesaz.geministrator.resources.hive_delete_refused_live
import com.hereliesaz.geministrator.resources.hive_delete_refused_package
import com.hereliesaz.geministrator.resources.hive_delete_refused_unknown
import com.hereliesaz.geministrator.resources.hive_delete_title
import com.hereliesaz.geministrator.resources.hive_delete_understood
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.withoutPlacement

/** Why a hive cannot be deleted right now. Each has a plain sentence in the UI (hive_delete_refused_*). */
internal enum class HiveDeleteRefusal {
    /** A run of the hive is live. */
    LiveRun,

    /** Another hive uses it as a circuit. */
    UsedAsCircuit,

    /** A package from the Store put it there; the package is removed in the Store. */
    FromPackage,

    /** Not in the hive list at all. */
    Unknown,
}

internal sealed interface HiveDeleteVerdict {
    data object Allowed : HiveDeleteVerdict
    data class Refused(val reason: HiveDeleteRefusal) : HiveDeleteVerdict
}

/** What a hive page can do about deleting itself: look first, then delete (which looks again). */
internal class HiveDeletionActions(
    val check: suspend () -> HiveDeleteVerdict,
    val delete: suspend () -> HiveDeleteVerdict,
)

/**
 * Whether the hive [id] may be deleted (docs/Keepers-Room.md, Delete a hive). Pure: the caller
 * passes every hive, every stored run, and the ids of hives that came from installed packages.
 * Only the one hive is ever in question; nothing here names a run, a memory or a repository.
 */
internal fun hiveDeleteVerdict(
    id: WorkflowDefinitionId,
    definitions: Collection<WorkflowDefinition>,
    runs: Collection<WorkflowRun>,
    packageDefinitionIds: Set<String> = emptySet(),
): HiveDeleteVerdict {
    if (definitions.none { it.id == id }) return HiveDeleteVerdict.Refused(HiveDeleteRefusal.Unknown)
    val own = runs.filter { it.workflowDefinitionId == id }
    if (own.any { it.status == WorkflowRunStatus.Running || it.status == WorkflowRunStatus.AwaitingHuman }) {
        return HiveDeleteVerdict.Refused(HiveDeleteRefusal.LiveRun)
    }
    if (id.value in packageDefinitionIds) return HiveDeleteVerdict.Refused(HiveDeleteRefusal.FromPackage)
    val usedAsCircuit = definitions.any { other ->
        other.id != id && other.tasks.any { task ->
            (task.executor.withoutPlacement() as? TaskExecutor.NestedWorkflow)?.workflowDefinitionId == id
        }
    }
    if (usedAsCircuit) return HiveDeleteVerdict.Refused(HiveDeleteRefusal.UsedAsCircuit)
    return HiveDeleteVerdict.Allowed
}

/**
 * Delete hive, on the hive's page in Build: a red pill, then a dialog naming the hive. A refusal says
 * why in a plain line and deletes nothing. [name] is the hive as saved, not as staged.
 */
@Composable
internal fun DeleteHiveControl(
    name: String,
    creatures: Int,
    actions: HiveDeletionActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val refusalText: suspend (HiveDeleteRefusal) -> String = { reason ->
        getString(
            when (reason) {
                HiveDeleteRefusal.LiveRun -> Res.string.hive_delete_refused_live
                HiveDeleteRefusal.UsedAsCircuit -> Res.string.hive_delete_refused_circuit
                HiveDeleteRefusal.FromPackage -> Res.string.hive_delete_refused_package
                HiveDeleteRefusal.Unknown -> Res.string.hive_delete_refused_unknown
            },
        )
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.hive_delete),
            seed = "delete-hive",
            tone = PillTone.Danger,
            onClick = {
                message = null
                scope.launch {
                    runCatching { actions.check() }
                        .onSuccess { verdict ->
                            when (verdict) {
                                HiveDeleteVerdict.Allowed -> confirming = true
                                is HiveDeleteVerdict.Refused -> message = refusalText(verdict.reason)
                            }
                        }
                        .onFailure { message = getString(Res.string.hive_delete_failed) }
                }
            },
        )
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
    }
    if (confirming) {
        AzphaltAlertDialog(
            title = stringResource(Res.string.hive_delete_title, name),
            text = stringResource(Res.string.hive_delete_body, creatures),
            confirmLabel = stringResource(Res.string.hive_delete),
            dismissLabel = stringResource(Res.string.hive_delete_keep),
            onDismissRequest = { confirming = false },
            onConfirm = {
                confirming = false
                scope.launch {
                    runCatching { actions.delete() }
                        .onSuccess { verdict ->
                            if (verdict is HiveDeleteVerdict.Refused) message = refusalText(verdict.reason)
                        }
                        .onFailure { message = getString(Res.string.hive_delete_failed) }
                }
            },
        )
    }
}

/**
 * Delete hive, asked for from somewhere other than the hive's own page (the district's long-press menu): it looks
 * first, then asks with the same dialog naming the hive, or says in a plain line why it cannot and deletes nothing.
 * [onFinished] says whether the hive was deleted; it is called once, when the dialog closes.
 */
@Composable
internal fun HiveDeleteRequest(
    name: String,
    creatures: Int,
    actions: HiveDeletionActions,
    onFinished: (deleted: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val refusalText: suspend (HiveDeleteRefusal) -> String = { reason ->
        getString(
            when (reason) {
                HiveDeleteRefusal.LiveRun -> Res.string.hive_delete_refused_live
                HiveDeleteRefusal.UsedAsCircuit -> Res.string.hive_delete_refused_circuit
                HiveDeleteRefusal.FromPackage -> Res.string.hive_delete_refused_package
                HiveDeleteRefusal.Unknown -> Res.string.hive_delete_refused_unknown
            },
        )
    }
    LaunchedEffect(Unit) {
        runCatching { actions.check() }
            .onSuccess { verdict ->
                when (verdict) {
                    HiveDeleteVerdict.Allowed -> confirming = true
                    is HiveDeleteVerdict.Refused -> message = refusalText(verdict.reason)
                }
            }
            .onFailure { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                message = getString(Res.string.hive_delete_failed)
            }
    }
    if (confirming) {
        AzphaltAlertDialog(
            title = stringResource(Res.string.hive_delete_title, name),
            text = stringResource(Res.string.hive_delete_body, creatures),
            confirmLabel = stringResource(Res.string.hive_delete),
            dismissLabel = stringResource(Res.string.hive_delete_keep),
            onDismissRequest = { confirming = false; onFinished(false) },
            onConfirm = {
                confirming = false
                scope.launch {
                    runCatching { actions.delete() }
                        .onSuccess { verdict ->
                            when (verdict) {
                                HiveDeleteVerdict.Allowed -> onFinished(true)
                                is HiveDeleteVerdict.Refused -> message = refusalText(verdict.reason)
                            }
                        }
                        .onFailure { failure ->
                            if (failure is kotlinx.coroutines.CancellationException) throw failure
                            message = getString(Res.string.hive_delete_failed)
                        }
                }
            },
        )
    }
    message?.let { text ->
        AzphaltAlertDialog(
            title = name,
            text = text,
            confirmLabel = stringResource(Res.string.hive_delete_understood),
            onDismissRequest = { message = null; onFinished(false) },
            onConfirm = { message = null; onFinished(false) },
        )
    }
}

/**
 * What a run of a deleted hive is shown against, read-only: the run's own recorded tasks (name is
 * the task id; role and executor as recorded), no strands, nothing saved. The hive's name is gone,
 * so the title says so plainly.
 */
internal fun deletedHiveStandIn(run: WorkflowRun): WorkflowDefinition = WorkflowDefinition(
    id = run.workflowDefinitionId,
    name = DELETED_HIVE_NAME,
    description = "This swarm was deleted. Its runs stay in history and can be viewed, not resumed.",
    tasks = run.taskRuns.values.map { taskRun ->
        com.hereliesaz.geministrator.domain.TaskDefinition(
            id = taskRun.taskDefinitionId,
            name = taskRun.taskDefinitionId.value,
            objective = "",
            roleId = taskRun.assignedRoleId,
            executor = taskRun.executor,
        )
    },
)

internal const val DELETED_HIVE_NAME = "Deleted swarm"
