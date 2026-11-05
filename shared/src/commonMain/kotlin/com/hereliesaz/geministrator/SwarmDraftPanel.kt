package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_draft_done
import com.hereliesaz.geministrator.resources.hive_draft_go
import com.hereliesaz.geministrator.resources.hive_draft_hint
import com.hereliesaz.geministrator.resources.hive_draft_keep
import com.hereliesaz.geministrator.resources.hive_draft_kept
import com.hereliesaz.geministrator.resources.hive_draft_problem_cycle
import com.hereliesaz.geministrator.resources.hive_draft_problem_empty
import com.hereliesaz.geministrator.resources.hive_draft_problem_no_prompt
import com.hereliesaz.geministrator.resources.hive_draft_problem_no_roles
import com.hereliesaz.geministrator.resources.hive_draft_problem_too_many
import com.hereliesaz.geministrator.resources.hive_draft_problem_unknown_role
import com.hereliesaz.geministrator.resources.hive_draft_problem_unreadable
import com.hereliesaz.geministrator.resources.hive_draft_redraft
import com.hereliesaz.geministrator.resources.hive_draft_redraft_description
import com.hereliesaz.geministrator.resources.hive_draft_replace
import com.hereliesaz.geministrator.resources.hive_draft_retry
import com.hereliesaz.geministrator.resources.hive_draft_review
import com.hereliesaz.geministrator.resources.hive_draft_unavailable
import com.hereliesaz.geministrator.resources.hive_draft_working
import com.hereliesaz.geministrator.resources.hive_new_name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Where a draft stands; the screen shows one line for it and the actions that fit. */
private sealed interface DraftPhase {
    data object Idle : DraftPhase
    data object Working : DraftPhase
    data class Done(val creatures: Int, val notes: List<String> = emptyList()) : DraftPhase
    data class Review(val ready: SwarmDraftResult.Ready) : DraftPhase
    data class Failed(val message: String, val raw: String?) : DraftPhase
    data object Kept : DraftPhase
}

/**
 * "Or describe it and let a keeper draft it": a prompt that has the planner propose creatures,
 * strands and jobs, which land in the hive as one edit on the tide (so one Undo takes the draft
 * back out). On a hive that already has creatures ([redraft]) it is a **Redraft** action that asks
 * before replacing them. Drafting only proposes; nothing is launched and no repository is touched.
 *
 * [drafter] null means no keeper is linked and nothing is shown. [onAdoptName] names a hive that
 * still bears the default name after the prompt, and is skipped for a redraft.
 */
@Composable
internal fun SwarmDraftBox(
    definition: WorkflowDefinition,
    drafter: (suspend (String) -> SwarmDraftResult)?,
    onEdit: (TerrariumDependencyEditResult) -> Unit,
    redraft: Boolean,
    modifier: Modifier = Modifier,
    onAdoptName: (suspend (String) -> Unit)? = null,
) {
    if (drafter == null) return
    val scope = rememberCoroutineScope()
    val key = definition.id.value
    var open by remember(key) { mutableStateOf(!redraft) }
    var prompt by remember(key) { mutableStateOf("") }
    var phase by remember(key) { mutableStateOf<DraftPhase>(DraftPhase.Idle) }
    val defaultName = stringResource(Res.string.hive_new_name)

    fun apply(ready: SwarmDraftResult.Ready) {
        scope.launch {
            if (!redraft && onAdoptName != null && definition.name == defaultName && ready.suggestedName.isNotBlank()) {
                runCatching { onAdoptName(ready.suggestedName) }
            }
            onEdit(TerrariumDependencyEditResult.Applied(redraftedHive(definition, ready)))
            phase = DraftPhase.Done(ready.tasks.size, ready.notes)
        }
    }

    fun draft() {
        if (phase == DraftPhase.Working) return
        if (prompt.isBlank()) {
            scope.launch { phase = DraftPhase.Failed(problemMessage(SwarmDraftProblem.NoPrompt), null) }
            return
        }
        val asked = prompt
        phase = DraftPhase.Working
        scope.launch {
            val result = try {
                drafter(asked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                swarmDraftFailure(failure)
            }
            when (result) {
                is SwarmDraftResult.Ready ->
                    // Nothing of the keeper's to lose: it lands at once. Otherwise they decide.
                    if (redraftReplaces(definition) == 0) apply(result) else phase = DraftPhase.Review(result)
                is SwarmDraftResult.Problem -> phase = DraftPhase.Failed(problemMessage(result.problem), null)
                is SwarmDraftResult.ProviderFailed ->
                    phase = DraftPhase.Failed(result.summary ?: friendlyErrorMessageOutsideComposition(result.raw), result.raw)
                SwarmDraftResult.Unavailable -> phase = DraftPhase.Failed(getString(Res.string.hive_draft_unavailable), null)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!open) {
            val label = stringResource(Res.string.hive_draft_redraft)
            val description = stringResource(Res.string.hive_draft_redraft_description)
            AzphaltThemedSurface(Azphalt.Ink) {
                DraftCapsuleButton(label, description, Azphalt.Ink, onClick = { open = true })
            }
        } else {
            DraftField(
                prompt = prompt,
                onPrompt = { prompt = it },
                busy = phase == DraftPhase.Working,
                onDraft = ::draft,
            )
        }
        when (val now = phase) {
            DraftPhase.Idle -> Unit
            DraftPhase.Working -> Status(stringResource(Res.string.hive_draft_working))
            is DraftPhase.Done -> {
                Status(stringResource(Res.string.hive_draft_done, now.creatures))
                now.notes.forEach { Status(it) }
            }
            DraftPhase.Kept -> Status(stringResource(Res.string.hive_draft_kept))
            is DraftPhase.Review -> {
                Status(stringResource(Res.string.hive_draft_review, now.ready.tasks.size, redraftReplaces(definition)))
                now.ready.notes.forEach { Status(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltThemedSurface(Azphalt.Ink) {
                        val replace = stringResource(Res.string.hive_draft_replace)
                        DraftCapsuleButton(replace, replace, Azphalt.Ink, onClick = { apply(now.ready) })
                        val keep = stringResource(Res.string.hive_draft_keep)
                        DraftCapsuleButton(keep, keep, AzphaltLiftedInk, onClick = { phase = DraftPhase.Kept })
                    }
                }
            }
            is DraftPhase.Failed -> {
                Status(now.message)
                ErrorDetailsToggle(raw = now.raw, seed = "swarm-draft")
                AzphaltThemedSurface(Azphalt.Ink) {
                    val retry = stringResource(Res.string.hive_draft_retry)
                    DraftCapsuleButton(retry, retry, Azphalt.Ink, onClick = ::draft)
                }
            }
        }
    }
}

private suspend fun problemMessage(problem: SwarmDraftProblem): String = when (problem) {
    SwarmDraftProblem.NoPrompt -> getString(Res.string.hive_draft_problem_no_prompt)
    SwarmDraftProblem.NoRoles -> getString(Res.string.hive_draft_problem_no_roles)
    SwarmDraftProblem.Empty -> getString(Res.string.hive_draft_problem_empty)
    SwarmDraftProblem.TooMany -> getString(Res.string.hive_draft_problem_too_many, SWARM_DRAFT_MAX_CREATURES)
    SwarmDraftProblem.UnknownRole -> getString(Res.string.hive_draft_problem_unknown_role)
    SwarmDraftProblem.Cycle -> getString(Res.string.hive_draft_problem_cycle)
    SwarmDraftProblem.Unreadable -> getString(Res.string.hive_draft_problem_unreadable)
}

@Composable
private fun Status(text: String) {
    Text(text, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion())
}

/** The prompt in an Ink well with Draft at its end; Draft is quiet until there is something to draft from. */
@Composable
private fun DraftField(prompt: String, onPrompt: (String) -> Unit, busy: Boolean, onDraft: () -> Unit) {
    val hint = stringResource(Res.string.hive_draft_hint)
    val label = stringResource(Res.string.hive_draft_go)
    AzphaltThemedSurface(Azphalt.Ink) {
        val content = azphaltOnSurface()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Azphalt.Ink)
                .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = { onPrompt(it.take(SWARM_DRAFT_MAX_PROMPT_CHARS)) },
                maxLines = 4,
                enabled = !busy,
                textStyle = AzphaltType.body.copy(color = content),
                cursorBrush = SolidColor(content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f).semantics { contentDescription = hint },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (prompt.isEmpty()) {
                            Text(hint, style = AzphaltType.body.copy(fontWeight = FontWeight.Normal), color = content.copy(alpha = 0.78f))
                        }
                        field()
                    }
                },
            )
            // Draft is the primary action of a new hive: always the strong fill, quiet only while drafting.
            val fill = if (busy) AzphaltLiftedInk else azphaltStrong()
            DraftCapsuleButton(label, label, fill, onClick = { if (!busy) onDraft() })
        }
    }
}

/** A capsule at least 48dp tall: [label] on [fill], read aloud as [description]. */
@Composable
private fun DraftCapsuleButton(label: String, description: String, fill: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .defaultMinSize(minHeight = AzphaltMinTouchTarget, minWidth = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = AzphaltType.capsule.copy(fontSize = 14.sp), color = fill.onOpposite())
    }
}
