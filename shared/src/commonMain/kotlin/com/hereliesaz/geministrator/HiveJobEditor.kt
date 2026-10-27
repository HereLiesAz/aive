package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.job_add_criterion
import com.hereliesaz.geministrator.resources.job_apply
import com.hereliesaz.geministrator.resources.job_approval_none
import com.hereliesaz.geministrator.resources.job_approval_person
import com.hereliesaz.geministrator.resources.job_criteria
import com.hereliesaz.geministrator.resources.job_job
import com.hereliesaz.geministrator.resources.job_objective
import com.hereliesaz.geministrator.resources.job_remove
import com.hereliesaz.geministrator.resources.job_retries
import org.jetbrains.compose.resources.stringResource

/**
 * A creature's job (docs/Swarm-OS.md, mockup H4): the task fields that lived in the Workflows form
 * editor, on the creature. Text is drafted locally and applied as one edit, so the tide records it once.
 */
@Composable
internal fun HiveJobEditor(
    definition: WorkflowDefinition,
    task: TaskDefinition,
    onEdit: (TerrariumDependencyEditResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var objective by remember(task.id, task.objective) { mutableStateOf(task.objective) }
    var newCriterion by remember(task.id) { mutableStateOf("") }
    val criteria = task.acceptanceCriteria.map { it.description }
    val asksPerson = task.approvalPolicy == ApprovalPolicy.HumanApproval
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.job_job), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        OutlinedTextField(
            value = objective,
            onValueChange = { objective = it },
            label = { Text(stringResource(Res.string.job_objective)) },
            minLines = 2,
            colors = azphaltTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (objective != task.objective) {
            AzphaltPill(
                label = stringResource(Res.string.job_apply),
                seed = "job-objective-${task.id.value}",
                onClick = { onEdit(setTerrariumJob(definition, task.id, objective = objective)) },
            )
        }
        Text(stringResource(Res.string.job_criteria), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        criteria.forEachIndexed { index, criterion ->
            AzphaltPill(
                label = criterion,
                seed = "job-criterion-$index",
                endCap = stringResource(Res.string.job_remove),
                onClick = { onEdit(setTerrariumJob(definition, task.id, criteria = criteria.filterIndexed { i, _ -> i != index })) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = newCriterion,
            onValueChange = { newCriterion = it },
            label = { Text(stringResource(Res.string.job_add_criterion)) },
            singleLine = true,
            colors = azphaltTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (newCriterion.isNotBlank()) {
            AzphaltPill(
                label = stringResource(Res.string.job_apply),
                seed = "job-criterion-add",
                onClick = {
                    onEdit(setTerrariumJob(definition, task.id, criteria = criteria + newCriterion))
                    newCriterion = ""
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                label = stringResource(if (asksPerson) Res.string.job_approval_person else Res.string.job_approval_none),
                seed = "job-approval",
                selected = asksPerson,
                onClick = { onEdit(setTerrariumJob(definition, task.id, humanApproval = !asksPerson)) },
            )
            AzphaltPill(
                label = stringResource(Res.string.job_retries, task.retryPolicy.maxAttempts),
                seed = "job-retries",
                // Tapping steps through 1..5 attempts.
                onClick = { onEdit(setTerrariumJob(definition, task.id, maxAttempts = task.retryPolicy.maxAttempts % 5 + 1)) },
            )
        }
    }
}
