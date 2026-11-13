package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.a2a.A2aAgentConnections
import com.hereliesaz.geministrator.a2a.A2aHiveCard
import com.hereliesaz.geministrator.a2a.HiveCardExport
import com.hereliesaz.geministrator.a2a.HiveCardImport
import com.hereliesaz.geministrator.a2a.HiveCardImportContext
import com.hereliesaz.geministrator.a2a.HiveCardOptions
import com.hereliesaz.geministrator.a2a.HiveCardProblem
import com.hereliesaz.geministrator.a2a.HiveCardSummary
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_card_copied
import com.hereliesaz.geministrator.resources.hive_card_copy
import com.hereliesaz.geministrator.resources.hive_card_copy_failed
import com.hereliesaz.geministrator.resources.hive_card_create
import com.hereliesaz.geministrator.resources.hive_card_drifter
import com.hereliesaz.geministrator.resources.hive_card_err_bad_field
import com.hereliesaz.geministrator.resources.hive_card_err_duplicate
import com.hereliesaz.geministrator.resources.hive_card_err_empty
import com.hereliesaz.geministrator.resources.hive_card_err_invalid
import com.hereliesaz.geministrator.resources.hive_card_err_no_creatures
import com.hereliesaz.geministrator.resources.hive_card_err_not_a_card
import com.hereliesaz.geministrator.resources.hive_card_err_nothing_usable
import com.hereliesaz.geministrator.resources.hive_card_err_too_large
import com.hereliesaz.geministrator.resources.hive_card_err_too_many
import com.hereliesaz.geministrator.resources.hive_card_err_unknown_strand
import com.hereliesaz.geministrator.resources.hive_card_err_unreadable
import com.hereliesaz.geministrator.resources.hive_card_err_version
import com.hereliesaz.geministrator.resources.hive_card_import
import com.hereliesaz.geministrator.resources.hive_card_import_explain
import com.hereliesaz.geministrator.resources.hive_card_label
import com.hereliesaz.geministrator.resources.hive_card_left_out
import com.hereliesaz.geministrator.resources.hive_card_needs_provider
import com.hereliesaz.geministrator.resources.hive_card_needs_role
import com.hereliesaz.geministrator.resources.hive_card_objectives_off
import com.hereliesaz.geministrator.resources.hive_card_objectives_on
import com.hereliesaz.geministrator.resources.hive_card_pinned
import com.hereliesaz.geministrator.resources.hive_card_read
import com.hereliesaz.geministrator.resources.hive_card_ready
import com.hereliesaz.geministrator.resources.hive_card_share
import com.hereliesaz.geministrator.resources.hive_card_share_explain
import com.hereliesaz.geministrator.resources.hive_card_strands_dropped
import com.hereliesaz.geministrator.resources.hive_new_name
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/*
 * Hive cards (docs/A2A.md, "Hive cards"): Share hive card on a hive, Import hive card in the
 * district. Both work on every platform and need no server. An import makes a new hive and
 * connects or fetches nothing.
 */

/** The label of the Share pill, shared by the places it appears. */
@Composable
internal fun hiveCardShareLabel(): String = stringResource(Res.string.hive_card_share)

/** Inside the Share panel: what the card holds, whether it carries objectives, and Copy. */
@Composable
internal fun HiveCardSharePanel(definition: WorkflowDefinition, modifier: Modifier = Modifier) {
    val host = LocalWorkflowLibraryHost.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var roles by remember { mutableStateOf<Collection<RoleDefinition>>(emptyList()) }
    LaunchedEffect(host) { roles = runCatching { host?.installedRoles() }.getOrNull().orEmpty() }
    var objectives by remember { mutableStateOf(false) }
    var message by remember(definition.id) { mutableStateOf<String?>(null) }
    var refused by remember(definition.id) { mutableStateOf<HiveCardProblem?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.hive_card_share_explain), style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltPill(
            label = stringResource(if (objectives) Res.string.hive_card_objectives_on else Res.string.hive_card_objectives_off),
            seed = "hive-card-objectives",
            selected = objectives,
            onClick = { objectives = !objectives },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.hive_card_copy),
            seed = "hive-card-copy",
            tone = PillTone.Primary,
            onClick = {
                scope.launch {
                    val urls = A2aAgentConnections.all().associate { it.providerId to it.cardUrl }
                    refused = null
                    message = when (val card = A2aHiveCard.export(definition, roles, HiveCardOptions(includeObjectives = objectives), urls)) {
                        is HiveCardExport.Ready -> runCatching { clipboard.setText(AnnotatedString(card.text)) }.fold(
                            onSuccess = { getString(Res.string.hive_card_copied) },
                            onFailure = { getString(Res.string.hive_card_copy_failed) },
                        )
                        is HiveCardExport.Refused -> {
                            refused = card.problem
                            null
                        }
                    }
                }
            },
        )
        (message ?: refused?.let { hiveCardProblemText(it) })?.let {
            Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion())
        }
    }
}

/**
 * The district's Import hive card: a pasted card is read, shown in plain words, and only then made
 * into a new hive ([onCreated]). [connectedProviderIds] are the providers linked here.
 */
@Composable
internal fun HiveCardImportPanel(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    connectedProviderIds: Set<String>,
    onLoadDefinitions: suspend () -> List<WorkflowDefinition>,
    onCreated: (WorkflowDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = LocalWorkflowLibraryHost.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<HiveCardImport?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val defaultName = stringResource(Res.string.hive_new_name)

    suspend fun read(): HiveCardImport {
        val roles = runCatching { host?.installedRoles() }.getOrNull().orEmpty()
        val taken = runCatching { onLoadDefinitions() }.getOrDefault(emptyList()).mapTo(hashSetOf()) { it.id.value }
        return A2aHiveCard.import(draft, HiveCardImportContext(connectedProviderIds, roles, taken, defaultName))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.hive_card_import),
            seed = "hive-card-import",
            selected = open,
            onClick = { onOpenChange(!open) },
        )
        if (!open) return@Column
        Text(stringResource(Res.string.hive_card_import_explain), style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltTextField(
            value = draft,
            onValueChange = {
                draft = it
                result = null
                failure = null
            },
            label = stringResource(Res.string.hive_card_label),
            singleLine = false,
        )
        AzphaltPill(
            label = stringResource(Res.string.hive_card_read),
            seed = "hive-card-read",
            tone = if (draft.isNotBlank() && result == null) PillTone.Primary else PillTone.Quiet,
            onClick = { scope.launch { result = read() } },
        )
        when (val current = result) {
            null -> Unit
            is HiveCardImport.Refused -> Text(
                hiveCardProblemText(current.problem),
                style = AzphaltType.body,
                color = azphaltOnSurface(),
                modifier = Modifier.azphaltLiveRegion(),
            )
            is HiveCardImport.Ready -> {
                HiveCardSummaryLines(current.summary)
                AzphaltPill(
                    label = stringResource(Res.string.hive_card_create),
                    seed = "hive-card-create",
                    tone = PillTone.Primary,
                    onClick = {
                        scope.launch {
                            // Read again just before saving, so the new hive's id is still free.
                            val fresh = read()
                            val ready = fresh as? HiveCardImport.Ready
                            if (ready == null || host == null) {
                                result = fresh
                                return@launch
                            }
                            runCatching { host.saveAuthored(ready.definition) }.fold(
                                onSuccess = {
                                    draft = ""
                                    result = null
                                    onOpenChange(false)
                                    onCreated(it)
                                },
                                onFailure = { failure = it.message ?: getString(Res.string.hive_card_err_unreadable) },
                            )
                        }
                    },
                )
                failure?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
            }
        }
    }
}

/** What an import will do, in plain words: counts, what is pinned, and what this device lacks. */
@Composable
private fun HiveCardSummaryLines(summary: HiveCardSummary) {
    // Every line that names a source or an orphan is a link to where it is set up: the source's own screen, or
    // Connect remote agent with the orphan's address filled in. A screen without the links shows plain lines.
    val links = LocalPlaceLinks.current
    val openSource = links.openSource
    val openConnections = links.openConnections
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.azphaltLiveRegion()) {
        Text(
            stringResource(Res.string.hive_card_ready, summary.hiveName, summary.creatures, summary.strandsKept),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
        summary.pinned.take(MAX_SUMMARY_LINES).forEach { pin ->
            LinkText(
                stringResource(Res.string.hive_card_pinned, pin.creatureName, providerName(pin.providerId)),
                onClick = openSource?.let { open -> { open(pin.providerId) } },
            )
        }
        summary.needsProviders.take(MAX_SUMMARY_LINES).forEach { id ->
            LinkText(
                stringResource(Res.string.hive_card_needs_provider, providerName(id)),
                onClick = openSource?.let { open -> { open(id) } },
            )
        }
        summary.drifterCards.take(MAX_SUMMARY_LINES).forEach { url ->
            LinkText(
                stringResource(Res.string.hive_card_drifter, url),
                onClick = openConnections?.let { open -> { open(url) } },
            )
        }
        summary.needsRoles.take(MAX_SUMMARY_LINES).forEach {
            Text(stringResource(Res.string.hive_card_needs_role, it), style = AzphaltType.body, color = azphaltOnSurface())
        }
        if (summary.leftOut > 0) {
            Text(stringResource(Res.string.hive_card_left_out, summary.leftOut), style = AzphaltType.body, color = azphaltOnSurface())
        }
        if (summary.strandsDropped > 0) {
            Text(stringResource(Res.string.hive_card_strands_dropped, summary.strandsDropped), style = AzphaltType.body, color = azphaltOnSurface())
        }
    }
}

private fun providerName(id: String): String = ProviderCatalog.entry(id)?.displayName ?: id

private const val MAX_SUMMARY_LINES = 8

/** A refused card in plain words. */
@Composable
internal fun hiveCardProblemText(problem: HiveCardProblem): String = when (problem) {
    HiveCardProblem.Empty -> stringResource(Res.string.hive_card_err_empty)
    HiveCardProblem.NotAHiveCard -> stringResource(Res.string.hive_card_err_not_a_card)
    HiveCardProblem.Unreadable -> stringResource(Res.string.hive_card_err_unreadable)
    HiveCardProblem.TooLarge -> stringResource(Res.string.hive_card_err_too_large)
    HiveCardProblem.NoCreatures -> stringResource(Res.string.hive_card_err_no_creatures)
    is HiveCardProblem.UnsupportedVersion -> stringResource(Res.string.hive_card_err_version, problem.found.ifBlank { "?" })
    is HiveCardProblem.TooManyCreatures -> stringResource(Res.string.hive_card_err_too_many, problem.count)
    is HiveCardProblem.BadField -> stringResource(Res.string.hive_card_err_bad_field, problem.field)
    is HiveCardProblem.DuplicateCreature -> stringResource(Res.string.hive_card_err_duplicate, problem.id)
    is HiveCardProblem.UnknownStrand -> stringResource(Res.string.hive_card_err_unknown_strand, problem.creature, problem.missing)
    is HiveCardProblem.NothingUsable -> stringResource(Res.string.hive_card_err_nothing_usable, problem.needsRoles.take(MAX_SUMMARY_LINES).joinToString(", ").ifBlank { "-" }, problem.leftOut)
    is HiveCardProblem.Invalid -> stringResource(Res.string.hive_card_err_invalid, problem.reason)
}
