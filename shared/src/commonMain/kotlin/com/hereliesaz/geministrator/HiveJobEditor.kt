package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.distributed.ComputeDelegationTarget
import com.hereliesaz.geministrator.distributed.computeDelegationTarget
import com.hereliesaz.geministrator.distributed.isComputeDelegatable
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.job_add
import com.hereliesaz.geministrator.resources.job_add_criterion
import com.hereliesaz.geministrator.resources.job_apply
import com.hereliesaz.geministrator.resources.job_approval
import com.hereliesaz.geministrator.resources.job_approval_none
import com.hereliesaz.geministrator.resources.job_approval_person
import com.hereliesaz.geministrator.resources.job_approval_role
import com.hereliesaz.geministrator.resources.job_criteria
import com.hereliesaz.geministrator.resources.job_job
import com.hereliesaz.geministrator.resources.job_objective
import com.hereliesaz.geministrator.resources.job_remove
import com.hereliesaz.geministrator.resources.job_retries_label
import com.hereliesaz.geministrator.resources.job_retries_value
import com.hereliesaz.geministrator.resources.job_runs_any
import com.hereliesaz.geministrator.resources.job_runs_chosen
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.job_approval_off_action
import com.hereliesaz.geministrator.resources.job_approval_off_body
import com.hereliesaz.geministrator.resources.job_approval_off_title
import com.hereliesaz.geministrator.resources.job_switch_off
import com.hereliesaz.geministrator.resources.job_switch_on
import com.hereliesaz.geministrator.resources.job_runs_here
import com.hereliesaz.geministrator.resources.job_runs_on
import com.hereliesaz.geministrator.resources.job_then_ask
import com.hereliesaz.geministrator.resources.job_then_reassign
import com.hereliesaz.geministrator.resources.job_then_stop
import com.hereliesaz.geministrator.resources.job_tries
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A creature's job (docs/Swarm-OS.md, mockup H4): the task fields that lived in the Workflows form
 * editor, on the creature. Its objective sits in a well; its acceptance criteria are rows, each
 * removable, with a row to add one; then its approval, retries and where it runs, each a row with
 * its value on the right. Text is drafted locally and applied as one edit, so the tide records it once.
 *
 * [onEdit] null shows the job without changing it. [remoteAvailable]: paired devices exist, so it
 * may be sent off this device; without them a job already sent elsewhere can still come back.
 */
@Composable
internal fun HiveJobEditor(
    definition: WorkflowDefinition,
    task: TaskDefinition,
    onEdit: ((TerrariumDependencyEditResult) -> Unit)?,
    modifier: Modifier = Modifier,
    titled: Boolean = true,
    remoteAvailable: Boolean = false,
) {
    var objective by remember(task.id, task.objective) { mutableStateOf(task.objective) }
    var newCriterion by remember(task.id) { mutableStateOf("") }
    val criteria = task.acceptanceCriteria.map { it.description }
    val asksPerson = task.approvalPolicy == ApprovalPolicy.HumanApproval
    val addCriterion = {
        if (newCriterion.isNotBlank()) {
            onEdit?.invoke(setTerrariumJob(definition, task.id, criteria = criteria + newCriterion))
            newCriterion = ""
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (titled) Text(stringResource(Res.string.job_job), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        val objectiveLabel = stringResource(Res.string.job_objective)
        JobLabel(objectiveLabel.uppercase())
        JobWell {
            BasicTextField(
                value = objective,
                onValueChange = { objective = it },
                readOnly = onEdit == null,
                textStyle = AzphaltType.body.copy(color = azphaltOnSurface()),
                cursorBrush = SolidColor(azphaltOnSurface()),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = objectiveLabel },
            )
        }
        if (onEdit != null && objective != task.objective) {
            AzphaltPill(
                label = stringResource(Res.string.job_apply),
                seed = "job-objective-${task.id.value}",
                tone = PillTone.Primary,
                onClick = { onEdit(setTerrariumJob(definition, task.id, objective = objective)) },
            )
        }
        JobLabel(stringResource(Res.string.job_criteria))
        val remove = stringResource(Res.string.job_remove)
        criteria.forEachIndexed { index, criterion ->
            JobRow(trailing = onEdit != null) {
                Text(criterion, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.weight(1f))
                if (onEdit != null) {
                    JobRowButton(
                        glyph = "✕",
                        description = "$remove: $criterion",
                        onClick = { onEdit(setTerrariumJob(definition, task.id, criteria = criteria.filterIndexed { i, _ -> i != index })) },
                    )
                }
            }
        }
        if (onEdit != null) {
            val addLabel = stringResource(Res.string.job_add_criterion)
            JobRow(trailing = true) {
                Box(Modifier.weight(1f)) {
                    if (newCriterion.isEmpty()) {
                        Text(addLabel, style = AzphaltType.body, color = azphaltMutedOnSurface(), modifier = Modifier.clearAndSetSemantics {})
                    }
                    BasicTextField(
                        value = newCriterion,
                        onValueChange = { newCriterion = it },
                        singleLine = true,
                        textStyle = AzphaltType.body.copy(color = azphaltOnSurface()),
                        cursorBrush = SolidColor(azphaltOnSurface()),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addCriterion() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = addLabel },
                    )
                }
                JobRowButton(
                    glyph = "＋",
                    description = stringResource(Res.string.job_add),
                    enabled = newCriterion.isNotBlank(),
                    onClick = addCriterion,
                )
            }
        }
        // Approval is a switch: the safety setting of the step, so it says it is on or off and asks before going off.
        var confirmingApprovalOff by remember(task.id) { mutableStateOf(false) }
        if (confirmingApprovalOff) {
            AzphaltAlertDialog(
                title = stringResource(Res.string.job_approval_off_title),
                text = stringResource(Res.string.job_approval_off_body),
                confirmLabel = stringResource(Res.string.job_approval_off_action),
                onConfirm = {
                    confirmingApprovalOff = false
                    onEdit?.invoke(setTerrariumJob(definition, task.id, humanApproval = false))
                },
                onDismissRequest = { confirmingApprovalOff = false },
                dismissLabel = stringResource(Res.string.confirm_cancel),
            )
        }
        val approvalValue = stringResource(
            when (task.approvalPolicy) {
                ApprovalPolicy.HumanApproval -> Res.string.job_approval_person
                is ApprovalPolicy.RoleApproval -> Res.string.job_approval_role
                ApprovalPolicy.None -> Res.string.job_approval_none
            },
        )
        JobControlRow(label = stringResource(Res.string.job_approval)) {
            AzphaltPill(
                label = approvalValue,
                seed = "job-approval-${task.id.value}",
                selected = asksPerson,
                endCap = stringResource(if (asksPerson) Res.string.job_switch_on else Res.string.job_switch_off),
                role = Role.Switch,
                enabled = onEdit != null,
                onClick = {
                    if (approvalChangeNeedsConfirm(asksPerson)) {
                        confirmingApprovalOff = true
                    } else {
                        onEdit?.invoke(setTerrariumJob(definition, task.id, humanApproval = true))
                    }
                },
            )
        }
        val tries = task.retryPolicy.maxAttempts
        JobValueRow(
            label = stringResource(Res.string.job_retries_label),
            value = stringResource(
                Res.string.job_retries_value,
                pluralStringResource(Res.plurals.job_tries, tries, tries),
                stringResource(
                    when (task.escalationPolicy) {
                        EscalationPolicy.FailWorkflow -> Res.string.job_then_stop
                        EscalationPolicy.RequireHumanDecision -> Res.string.job_then_ask
                        is EscalationPolicy.Reassign -> Res.string.job_then_reassign
                    },
                ),
            ),
            onClick = null,
        )
        if (onEdit != null) {
            // Choosing the number of tries: one pill each, the current one filled.
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JOB_TRY_CHOICES.forEach { choice ->
                    AzphaltPill(
                        label = choice.toString(),
                        seed = "job-tries-${task.id.value}-$choice",
                        selected = choice == tries,
                        role = Role.RadioButton,
                        description = pluralStringResource(Res.plurals.job_tries, choice, choice),
                        onClick = { if (choice != tries) onEdit(setTerrariumJob(definition, task.id, maxAttempts = choice)) },
                    )
                }
            }
        }
        val runsOn = task.computeDelegationTarget()
        JobValueRow(
            label = stringResource(Res.string.job_runs_on),
            value = stringResource(
                when (runsOn) {
                    ComputeDelegationTarget.Local -> Res.string.job_runs_here
                    ComputeDelegationTarget.AnyRemote -> Res.string.job_runs_any
                    is ComputeDelegationTarget.Node -> Res.string.job_runs_chosen
                },
            ),
            onClick = null,
        )
        // Where it runs, as two choices; offered only when there is somewhere else to go (or back from).
        if (onEdit != null && task.isComputeDelegatable() && (remoteAvailable || runsOn != ComputeDelegationTarget.Local)) {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(
                    label = stringResource(Res.string.job_runs_here),
                    seed = "job-runs-local-${task.id.value}",
                    selected = runsOn == ComputeDelegationTarget.Local,
                    role = Role.RadioButton,
                    onClick = { if (runsOn != ComputeDelegationTarget.Local) onEdit(setTerrariumJob(definition, task.id, runsOn = ComputeDelegationTarget.Local)) },
                )
                AzphaltPill(
                    label = stringResource(Res.string.job_runs_any),
                    seed = "job-runs-any-${task.id.value}",
                    selected = runsOn == ComputeDelegationTarget.AnyRemote,
                    role = Role.RadioButton,
                    enabled = remoteAvailable,
                    onClick = { if (runsOn != ComputeDelegationTarget.AnyRemote) onEdit(setTerrariumJob(definition, task.id, runsOn = ComputeDelegationTarget.AnyRemote)) },
                )
            }
        }
    }
}

/** Turning a person's approval off removes the safety check on a step, so it asks first; turning it on does not. */
internal fun approvalChangeNeedsConfirm(asksPersonNow: Boolean): Boolean = asksPersonNow

private val JOB_TRY_CHOICES = 1..5

/** A label on the left and its control on the right. */
@Composable
private fun JobControlRow(label: String, control: @Composable () -> Unit) {
    JobRow {
        Text(label, style = AzphaltType.body, color = azphaltMutedOnSurface(), modifier = Modifier.weight(1f))
        control()
    }
}

/**
 * Where a tap on "Runs on" sends a job next: off this device to any paired one when there are paired
 * devices, and back to this device from anywhere else. Null when there is nowhere else to go.
 */
internal fun nextRunsOn(current: ComputeDelegationTarget, remoteAvailable: Boolean): ComputeDelegationTarget? = when (current) {
    ComputeDelegationTarget.Local -> if (remoteAvailable) ComputeDelegationTarget.AnyRemote else null
    ComputeDelegationTarget.AnyRemote, is ComputeDelegationTarget.Node -> ComputeDelegationTarget.Local
}

@Composable
private fun JobLabel(text: String) {
    Text(text, style = AzphaltType.eyebrow, color = azphaltMutedOnSurface())
}

/** A row's fill: Ink one step lighter on an Ink surface, else Ink (as a record's card). */
@Composable
private fun jobRowFill(): Color {
    val surface = azphaltSurface()
    return if (surface == Azphalt.Ink || surface == AzphaltLiftedInk) AzphaltLiftedInk else Azphalt.Ink
}

@Composable
private fun JobWell(content: @Composable () -> Unit) {
    val fill = jobRowFill()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        AzphaltSurface(fill) { content() }
    }
}

/** A pill-shaped row, one touch target tall. [trailing]: it ends in its own 48dp button. */
@Composable
private fun JobRow(
    trailing: Boolean = false,
    onClick: (() -> Unit)? = null,
    semantics: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val fill = jobRowFill()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(24.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(semantics)
            .background(fill)
            .padding(start = 16.dp, end = if (trailing) 0.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AzphaltSurface(fill) { content() }
    }
}

@Composable
private fun JobRowButton(glyph: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(AzphaltMinTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
            },
    ) {
        Text(glyph, style = AzphaltType.body, color = if (enabled) azphaltOnSurface() else azphaltMutedOnSurface())
    }
}

/** A label on the left and its value on the right; tapping steps the value on when [onClick] is given. */
@Composable
private fun JobValueRow(label: String, value: String, onClick: (() -> Unit)?) {
    JobRow(
        onClick = onClick,
        semantics = Modifier.clearAndSetSemantics {
            contentDescription = "$label: $value"
            if (onClick != null) role = Role.Button
        },
    ) {
        Text(label, style = AzphaltType.body, color = azphaltMutedOnSurface(), modifier = Modifier.weight(1f))
        Text(value, style = AzphaltType.body.copy(fontWeight = FontWeight.ExtraBold), color = azphaltOnSurface())
    }
}
