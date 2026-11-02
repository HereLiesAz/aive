package com.hereliesaz.geministrator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.agentui.AgentUi
import com.hereliesaz.geministrator.agentui.AgentUiComponent
import com.hereliesaz.geministrator.agentui.AgentUiFieldKind
import com.hereliesaz.geministrator.agentui.AgentUiParse
import com.hereliesaz.geministrator.agentui.AgentUiRequest
import com.hereliesaz.geministrator.agentui.AgentUiTextStyle
import com.hereliesaz.geministrator.agentui.AgentUiValue
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.agent_question_answer
import com.hereliesaz.geministrator.resources.agent_question_not_a_number
import com.hereliesaz.geministrator.resources.agent_question_plain
import com.hereliesaz.geministrator.resources.agent_question_send
import com.hereliesaz.geministrator.resources.agent_question_sent
import com.hereliesaz.geministrator.resources.agent_question_title
import com.hereliesaz.geministrator.resources.inspector_sending
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * A creature's question to the keeper, in its Needs (docs/Agent-UI.md). A valid question is drawn
 * from the host catalog only, every string as plain text; one that is not is shown as its text with
 * a plain answer field. [onAnswer] takes the question's key and the answer message and returns null
 * once the agent's session accepted it, or why not.
 */
@Composable
internal fun AgentQuestionPanel(
    taskId: String,
    question: String,
    onAnswer: suspend (String, String) -> String?,
) {
    val questionKey = remember(question) { AgentUi.questionKey(question) }
    val parsed = remember(question) { AgentUi.parseStored(question) }
    val scope = rememberCoroutineScope()
    val values = remember(questionKey) { mutableStateMapOf<String, AgentUiValue>() }
    var plainAnswer by remember(questionKey) { mutableStateOf("") }
    var sending by remember(questionKey) { mutableStateOf(false) }
    var status by remember(questionKey) { mutableStateOf<String?>(null) }
    var answered by remember(questionKey) { mutableStateOf(false) }

    fun send(answer: () -> String) {
        if (sending || answered) return
        val message = try {
            answer()
        } catch (failure: IllegalArgumentException) {
            status = failure.message
            return
        }
        sending = true
        status = null
        scope.launch {
            val failure = onAnswer(questionKey, message)
            sending = false
            if (failure == null) {
                answered = true
                status = getString(Res.string.agent_question_sent)
            } else {
                status = failure
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.agent_question_title), style = AzphaltType.capsule, color = azphaltOnSurface())
        when (parsed) {
            is AgentUiParse.Request -> {
                val request = parsed.request
                if (request.lead.isNotBlank()) {
                    Text(request.lead, style = AzphaltType.body, color = azphaltOnSurface())
                }
                val numbersValid = request.inputs().all { input ->
                    input !is AgentUiComponent.Field || input.kind != AgentUiFieldKind.Number ||
                        fieldText(input, values).isBlank() || AgentUi.isNumber(fieldText(input, values))
                }
                AgentUiNode(
                    request = request,
                    id = request.rootId,
                    values = values,
                    enabled = !sending && !answered,
                    onAction = { action ->
                        if (numbersValid) {
                            send { AgentUi.encodeAnswer(request, action, values.toMap(), timestamp()) }
                        }
                    },
                    taskId = taskId,
                )
                if (request.actions().isEmpty()) {
                    // A question that names no action gets the host's own Send.
                    val submit = AgentUiComponent.Action(
                        id = "aive-send",
                        childId = "",
                        name = AgentUiRequest.DEFAULT_ACTION,
                        primary = true,
                    )
                    AzphaltPill(
                        label = stringResource(if (sending) Res.string.inspector_sending else Res.string.agent_question_send),
                        seed = "question-send-$taskId",
                        tone = PillTone.Primary,
                        onClick = {
                            if (numbersValid && !answered) {
                                val withSend = request.copy(components = request.components + (submit.id to submit))
                                send { AgentUi.encodeAnswer(withSend, submit, values.toMap(), timestamp()) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!numbersValid) {
                    Text(stringResource(Res.string.agent_question_not_a_number), style = AzphaltType.body, color = azphaltOnSurface())
                }
            }
            is AgentUiParse.Invalid -> {
                Text(stringResource(Res.string.agent_question_plain), style = AzphaltType.body, color = azphaltOnSurface())
                SelectionContainer {
                    Text(parsed.text, style = AzphaltType.body, color = azphaltOnSurface())
                }
                OutlinedTextField(
                    value = plainAnswer,
                    onValueChange = {
                        plainAnswer = it
                        status = null
                    },
                    label = { Text(stringResource(Res.string.agent_question_answer)) },
                    minLines = 2,
                    enabled = !sending && !answered,
                    colors = azphaltTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                AzphaltPill(
                    label = stringResource(if (sending) Res.string.inspector_sending else Res.string.agent_question_send),
                    seed = "question-plain-$taskId",
                    tone = PillTone.Primary,
                    onClick = {
                        if (plainAnswer.isNotBlank()) send { AgentUi.encodePlainAnswer(plainAnswer) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // parseStored never answers None; nothing to draw if it ever did.
            AgentUiParse.None -> Unit
        }
        status?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
    }
}

@OptIn(ExperimentalTime::class)
private fun timestamp(): String = Clock.System.now().toString()

private fun fieldText(field: AgentUiComponent.Field, values: Map<String, AgentUiValue>): String =
    (values[field.id] as? AgentUiValue.Text)?.text ?: field.initial

/** One component of a validated question, drawn from the host catalog; containers recurse. */
@Composable
private fun AgentUiNode(
    request: AgentUiRequest,
    id: String,
    values: MutableMap<String, AgentUiValue>,
    enabled: Boolean,
    onAction: (AgentUiComponent.Action) -> Unit,
    taskId: String,
) {
    when (val component = request.components[id] ?: return) {
        is AgentUiComponent.Text -> Text(
            component.text,
            style = when (component.style) {
                AgentUiTextStyle.Heading -> AzphaltType.lead
                AgentUiTextStyle.Body -> AzphaltType.body
                AgentUiTextStyle.Caption -> AzphaltType.endCap
            },
            color = azphaltOnSurface(),
        )
        is AgentUiComponent.Column -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            component.children.forEach { child ->
                AgentUiNode(request, child, values, enabled, onAction, taskId)
            }
        }
        is AgentUiComponent.Row -> Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            component.children.forEach { child ->
                AgentUiNode(request, child, values, enabled, onAction, taskId)
            }
        }
        is AgentUiComponent.Choice -> {
            val chosen = (values[component.id] as? AgentUiValue.Choices)?.values ?: component.initial
            val single = component.maxSelections == 1
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                component.options.forEach { option ->
                    val selected = option.value in chosen
                    AzphaltPill(
                        label = option.label,
                        seed = "question-$taskId-${component.id}-${option.value}",
                        selected = selected,
                        role = if (single) Role.RadioButton else Role.Checkbox,
                        onClick = {
                            if (!enabled) return@AzphaltPill
                            val next = when {
                                single -> listOf(option.value)
                                selected -> chosen - option.value
                                chosen.size < component.maxSelections -> chosen + option.value
                                else -> chosen
                            }
                            values[component.id] = AgentUiValue.Choices(next)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is AgentUiComponent.Field -> OutlinedTextField(
            value = fieldText(component, values),
            onValueChange = { values[component.id] = AgentUiValue.Text(it.take(AgentUi.MAX_TEXT_CHARS)) },
            label = { Text(component.label) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (component.kind) {
                    AgentUiFieldKind.ShortText -> KeyboardType.Text
                    AgentUiFieldKind.Number -> KeyboardType.Decimal
                },
            ),
            colors = azphaltTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        is AgentUiComponent.Action -> {
            val label = (request.components[component.childId] as? AgentUiComponent.Text)?.text.orEmpty()
            AzphaltPill(
                label = label,
                seed = "question-$taskId-${component.id}",
                tone = if (component.primary) PillTone.Primary else PillTone.Quiet,
                onClick = { if (enabled) onAction(component) },
            )
        }
        is AgentUiComponent.Table -> Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                component.columns.forEach { header ->
                    Text(header, style = AzphaltType.capsule, color = azphaltOnSurface(), modifier = Modifier.widthIn(min = 72.dp, max = 220.dp))
                }
            }
            component.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { cell ->
                        Text(cell, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.widthIn(min = 72.dp, max = 220.dp))
                    }
                }
            }
        }
    }
}
