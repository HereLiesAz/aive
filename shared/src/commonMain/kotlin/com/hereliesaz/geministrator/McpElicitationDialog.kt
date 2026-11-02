package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.mcp.McpElicitationAction
import com.hereliesaz.geministrator.mcp.McpElicitationField
import com.hereliesaz.geministrator.mcp.McpElicitationFieldKind
import com.hereliesaz.geministrator.mcp.McpElicitationRequest
import com.hereliesaz.geministrator.mcp.McpElicitationResponse
import com.hereliesaz.geministrator.mcp.McpFieldInput
import com.hereliesaz.geministrator.mcp.McpFieldProblem
import com.hereliesaz.geministrator.mcp.PromptingMcpElicitationGate
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.mcp_elicit_cancel
import com.hereliesaz.geministrator.resources.mcp_elicit_decline
import com.hereliesaz.geministrator.resources.mcp_elicit_field_secret
import com.hereliesaz.geministrator.resources.mcp_elicit_form_lead
import com.hereliesaz.geministrator.resources.mcp_elicit_no_secrets
import com.hereliesaz.geministrator.resources.mcp_elicit_off
import com.hereliesaz.geministrator.resources.mcp_elicit_on
import com.hereliesaz.geministrator.resources.mcp_elicit_required
import com.hereliesaz.geministrator.resources.mcp_elicit_send
import com.hereliesaz.geministrator.resources.mcp_elicit_title
import com.hereliesaz.geministrator.resources.mcp_elicit_url_continue
import com.hereliesaz.geministrator.resources.mcp_elicit_url_lead
import com.hereliesaz.geministrator.resources.mcp_elicit_url_note
import com.hereliesaz.geministrator.resources.mcp_elicit_url_open
import com.hereliesaz.geministrator.resources.mcp_elicit_url_site
import com.hereliesaz.geministrator.resources.mcp_elicit_url_suspicious
import com.hereliesaz.geministrator.resources.mcp_elicit_url_title
import com.hereliesaz.geministrator.resources.mcp_field_above_maximum
import com.hereliesaz.geministrator.resources.mcp_field_bad_date
import com.hereliesaz.geministrator.resources.mcp_field_bad_date_time
import com.hereliesaz.geministrator.resources.mcp_field_bad_email
import com.hereliesaz.geministrator.resources.mcp_field_bad_uri
import com.hereliesaz.geministrator.resources.mcp_field_below_minimum
import com.hereliesaz.geministrator.resources.mcp_field_not_a_choice
import com.hereliesaz.geministrator.resources.mcp_field_not_a_number
import com.hereliesaz.geministrator.resources.mcp_field_not_an_integer
import com.hereliesaz.geministrator.resources.mcp_field_required
import com.hereliesaz.geministrator.resources.mcp_field_too_few
import com.hereliesaz.geministrator.resources.mcp_field_too_long
import com.hereliesaz.geministrator.resources.mcp_field_too_many
import com.hereliesaz.geministrator.resources.mcp_field_too_short
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A server's request for input during a tool call (docs/architecture/MCP_HOST.md, Elicitation).
 * It says which server asks and warns against typing secrets. A form is sent only on **Send**,
 * after it validates; a URL is shown as plain text and opened only on **Open in browser**, never
 * automatically. Dismissing is a cancel.
 */
@Composable
internal fun McpElicitationDialog(gate: PromptingMcpElicitationGate) {
    val request by gate.pending.collectAsState()
    when (val current = request ?: return) {
        is McpElicitationRequest.Form -> FormElicitation(current, gate::answer)
        is McpElicitationRequest.Link -> LinkElicitation(current, gate::answer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElicitationFrame(
    title: String,
    onCancel: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    body: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = GeministratorColors) {
        AlertDialog(
            onDismissRequest = onCancel,
            containerColor = Azphalt.Ink,
            titleContentColor = Azphalt.Ink.onOpposite(),
            textContentColor = Azphalt.Ink.onOpposite(),
            shape = RoundedCornerShape(AzphaltShapes.dialogCorner),
            title = { Text(title, fontWeight = FontWeight.Black) },
            text = {
                AzphaltThemedSurface(Azphalt.Ink) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { body() }
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Azphalt.Yellow,
                        contentColor = Azphalt.Yellow.onOpposite(),
                    ),
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                FlowRow {
                    TextButton(
                        onClick = onDecline,
                        colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.Ink.onOpposite()),
                    ) { Text(stringResource(Res.string.mcp_elicit_decline)) }
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = Azphalt.Ink.onOpposite()),
                    ) { Text(stringResource(Res.string.mcp_elicit_cancel)) }
                }
            },
        )
    }
}

@Composable
private fun FormElicitation(request: McpElicitationRequest.Form, answer: (McpElicitationResponse) -> Unit) {
    var inputs by remember(request) { mutableStateOf(request.initialInputs()) }
    var tried by remember(request) { mutableStateOf(false) }
    val problems = request.problems(inputs)
    ElicitationFrame(
        title = stringResource(Res.string.mcp_elicit_title, request.serverName),
        onCancel = { answer(McpElicitationResponse.Cancel) },
        confirmLabel = stringResource(Res.string.mcp_elicit_send),
        onConfirm = {
            tried = true
            request.content(inputs)?.let { answer(McpElicitationResponse(McpElicitationAction.Accept, it)) }
        },
        onDecline = { answer(McpElicitationResponse.Decline) },
    ) {
        Text(stringResource(Res.string.mcp_elicit_form_lead, request.serverName, request.serverLocation, request.toolName))
        if (request.message.isNotBlank()) Text("“${request.message}”", fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.mcp_elicit_no_secrets), fontWeight = FontWeight.Bold)
        request.fields.forEach { field ->
            val problem = problems[field.key]?.takeIf { tried }
            ElicitationField(
                field = field,
                input = inputs[field.key],
                problem = problem?.let { stringResource(it.label()) },
                onChange = { inputs = inputs + (field.key to it) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ElicitationField(
    field: McpElicitationField,
    input: McpFieldInput?,
    problem: String?,
    onChange: (McpFieldInput) -> Unit,
) {
    val label = if (field.required) stringResource(Res.string.mcp_elicit_required, field.title) else field.title
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (field.kind) {
            McpElicitationFieldKind.Text, McpElicitationFieldKind.Number, McpElicitationFieldKind.Integer -> AzphaltTextField(
                value = (input as? McpFieldInput.Text)?.text.orEmpty(),
                onValueChange = { onChange(McpFieldInput.Text(it.take(McpElicitationRequest.MAX_TEXT_CHARS))) },
                label = label,
                errorMessage = problem,
            )
            McpElicitationFieldKind.Flag -> {
                val on = (input as? McpFieldInput.Flag)?.on ?: false
                AzphaltPill(
                    label = label,
                    seed = "mcp-elicit-${field.key}",
                    selected = on,
                    role = Role.Checkbox,
                    stateDescription = stringResource(if (on) Res.string.mcp_elicit_on else Res.string.mcp_elicit_off),
                    onClick = { onChange(McpFieldInput.Flag(!on)) },
                )
            }
            McpElicitationFieldKind.SingleChoice, McpElicitationFieldKind.MultiChoice -> {
                val chosen = (input as? McpFieldInput.Choices)?.values.orEmpty()
                val multi = field.kind == McpElicitationFieldKind.MultiChoice
                Text(label, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.choices.forEach { choice ->
                        val selected = choice.value in chosen
                        AzphaltPill(
                            label = choice.label,
                            seed = "mcp-elicit-${field.key}-${choice.value}",
                            selected = selected,
                            role = if (multi) Role.Checkbox else Role.RadioButton,
                            onClick = {
                                val next = when {
                                    multi && selected -> chosen - choice.value
                                    multi -> chosen + choice.value
                                    selected && !field.required -> emptySet()
                                    else -> setOf(choice.value)
                                }
                                onChange(McpFieldInput.Choices(next))
                            },
                        )
                    }
                }
                problem?.let { Text(it, modifier = Modifier.azphaltLiveRegion()) }
            }
        }
        field.description?.takeIf(String::isNotBlank)?.let { Text(it, color = azphaltMutedOnSurface()) }
        if (field.looksSecret) Text(stringResource(Res.string.mcp_elicit_field_secret), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinkElicitation(request: McpElicitationRequest.Link, answer: (McpElicitationResponse) -> Unit) {
    val uriHandler = LocalUriHandler.current
    var opened by remember(request) { mutableStateOf(false) }
    ElicitationFrame(
        title = stringResource(Res.string.mcp_elicit_url_title, request.serverName),
        onCancel = { answer(McpElicitationResponse.Cancel) },
        confirmLabel = stringResource(if (opened) Res.string.mcp_elicit_url_continue else Res.string.mcp_elicit_url_open),
        onConfirm = {
            if (!opened) {
                // Only on the keeper's explicit tap, in the system browser; never fetched or read here.
                runCatching { uriHandler.openUri(request.url) }
                opened = true
            } else {
                answer(McpElicitationResponse(McpElicitationAction.Accept))
            }
        },
        onDecline = { answer(McpElicitationResponse.Decline) },
    ) {
        Text(stringResource(Res.string.mcp_elicit_url_lead, request.serverName, request.serverLocation, request.toolName))
        if (request.message.isNotBlank()) Text("“${request.message}”", fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.mcp_elicit_url_site, request.host), fontWeight = FontWeight.Black)
        // The full address as plain, unlinked text, with the host set apart against look-alikes.
        Text(
            buildAnnotatedString {
                val at = request.url.indexOf(request.host)
                if (at < 0) {
                    append(request.url)
                } else {
                    append(request.url.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight.Black)) { append(request.host) }
                    append(request.url.substring(at + request.host.length))
                }
            },
        )
        if (request.suspicious) Text(stringResource(Res.string.mcp_elicit_url_suspicious), fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.mcp_elicit_url_note), color = azphaltMutedOnSurface())
    }
}

private fun McpFieldProblem.label(): StringResource = when (this) {
    McpFieldProblem.Required -> Res.string.mcp_field_required
    McpFieldProblem.TooShort -> Res.string.mcp_field_too_short
    McpFieldProblem.TooLong -> Res.string.mcp_field_too_long
    McpFieldProblem.NotANumber -> Res.string.mcp_field_not_a_number
    McpFieldProblem.NotAnInteger -> Res.string.mcp_field_not_an_integer
    McpFieldProblem.BelowMinimum -> Res.string.mcp_field_below_minimum
    McpFieldProblem.AboveMaximum -> Res.string.mcp_field_above_maximum
    McpFieldProblem.NotAChoice -> Res.string.mcp_field_not_a_choice
    McpFieldProblem.TooFewChoices -> Res.string.mcp_field_too_few
    McpFieldProblem.TooManyChoices -> Res.string.mcp_field_too_many
    McpFieldProblem.BadEmail -> Res.string.mcp_field_bad_email
    McpFieldProblem.BadUri -> Res.string.mcp_field_bad_uri
    McpFieldProblem.BadDate -> Res.string.mcp_field_bad_date
    McpFieldProblem.BadDateTime -> Res.string.mcp_field_bad_date_time
}
