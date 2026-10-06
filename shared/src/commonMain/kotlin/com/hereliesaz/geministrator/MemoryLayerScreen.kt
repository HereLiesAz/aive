package com.hereliesaz.geministrator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.memory.MemoryEngineKind
import com.hereliesaz.geministrator.memory.MemoryInspection
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryRawRetention
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryQueueStatus
import com.hereliesaz.geministrator.memory.MemoryStageEngine
import com.hereliesaz.geministrator.memory.parked
import kotlinx.coroutines.launch
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_cancel
import com.hereliesaz.geministrator.resources.common_dismiss
import com.hereliesaz.geministrator.resources.common_remove
import com.hereliesaz.geministrator.resources.common_retry
import com.hereliesaz.geministrator.resources.memory_chunks
import com.hereliesaz.geministrator.resources.memory_episodes
import com.hereliesaz.geministrator.resources.memory_failed
import com.hereliesaz.geministrator.resources.memory_late
import com.hereliesaz.geministrator.resources.memory_link
import com.hereliesaz.geministrator.resources.memory_memory_node
import com.hereliesaz.geministrator.resources.memory_purged_by_your_retention_setting
import com.hereliesaz.geministrator.resources.memory_running_programmatically_instead
import com.hereliesaz.geministrator.resources.memory_sections_memories_links
import com.hereliesaz.geministrator.resources.memory_stage
import com.hereliesaz.geministrator.resources.memory_summaries_tree_nodes
import com.hereliesaz.geministrator.resources.memory_waiting_parked
import com.hereliesaz.geministrator.resources.memory_workflows
import com.hereliesaz.geministrator.resources.memory_a_project_s_workflows_read_each
import com.hereliesaz.geministrator.resources.memory_an_entry_that_fails_times
import com.hereliesaz.geministrator.resources.memory_apply_tuning
import com.hereliesaz.geministrator.resources.memory_attempts_before_parking
import com.hereliesaz.geministrator.resources.memory_by_engine
import com.hereliesaz.geministrator.resources.memory_changes_per_packet
import com.hereliesaz.geministrator.resources.memory_characters_per_packet
import com.hereliesaz.geministrator.resources.memory_choose_a_memory_to_see_its
import com.hereliesaz.geministrator.resources.memory_clerk
import com.hereliesaz.geministrator.resources.memory_consolidated_memory_never_depends_on_it
import com.hereliesaz.geministrator.resources.memory_consolidation
import com.hereliesaz.geministrator.resources.memory_consolidation_paused
import com.hereliesaz.geministrator.resources.memory_consolidation_running
import com.hereliesaz.geministrator.resources.memory_counted_since_this_bank_s_layer
import com.hereliesaz.geministrator.resources.memory_default_provider
import com.hereliesaz.geministrator.resources.memory_defaults
import com.hereliesaz.geministrator.resources.memory_discard
import com.hereliesaz.geministrator.resources.memory_embedding_calls
import com.hereliesaz.geministrator.resources.memory_episode
import com.hereliesaz.geministrator.resources.memory_every_finished_session_is_banked_here
import com.hereliesaz.geministrator.resources.memory_export_memory_to_json
import com.hereliesaz.geministrator.resources.memory_exported_memory_chars
import com.hereliesaz.geministrator.resources.memory_forget
import com.hereliesaz.geministrator.resources.memory_forget_everything
import com.hereliesaz.geministrator.resources.memory_graph
import com.hereliesaz.geministrator.resources.memory_hide_summary_tree
import com.hereliesaz.geministrator.resources.memory_history
import com.hereliesaz.geministrator.resources.memory_import_memory_from_json
import com.hereliesaz.geministrator.resources.memory_imported
import com.hereliesaz.geministrator.resources.memory_include
import com.hereliesaz.geministrator.resources.memory_includes
import com.hereliesaz.geministrator.resources.memory_install
import com.hereliesaz.geministrator.resources.memory_installed
import com.hereliesaz.geministrator.resources.memory_items_per_packet
import com.hereliesaz.geministrator.resources.memory_keep_50_m_characters
import com.hereliesaz.geministrator.resources.memory_keep_90_days
import com.hereliesaz.geministrator.resources.memory_keep_all_raw_history
import com.hereliesaz.geministrator.resources.memory_keep_it
import com.hereliesaz.geministrator.resources.memory_memories_condensed_together
import com.hereliesaz.geministrator.resources.memory_memory
import com.hereliesaz.geministrator.resources.memory_memory_cleared
import com.hereliesaz.geministrator.resources.memory_memory_is_not_available_in_this
import com.hereliesaz.geministrator.resources.memory_memory_off
import com.hereliesaz.geministrator.resources.memory_memory_on
import com.hereliesaz.geministrator.resources.memory_model_blank_for_the_provider_s
import com.hereliesaz.geministrator.resources.memory_model_calls
import com.hereliesaz.geministrator.resources.memory_needed_only_for_stages_set_to
import com.hereliesaz.geministrator.resources.memory_no_error_recorded
import com.hereliesaz.geministrator.resources.memory_no_history_this_memory_replaced_and
import com.hereliesaz.geministrator.resources.memory_no_memories_to_inspect_yet
import com.hereliesaz.geministrator.resources.memory_no_on_device_memory_models_on
import com.hereliesaz.geministrator.resources.memory_no_pair_summary_yet_the_next
import com.hereliesaz.geministrator.resources.memory_no_project_has_a_workflow_with
import com.hereliesaz.geministrator.resources.memory_no_summary_tree_levels_above_this
import com.hereliesaz.geministrator.resources.memory_no_summary_tree_yet_it_is
import com.hereliesaz.geministrator.resources.memory_no_workflow_has_a_memory_bank
import com.hereliesaz.geministrator.resources.memory_not_installed
import com.hereliesaz.geministrator.resources.memory_now_includes
import com.hereliesaz.geministrator.resources.memory_on_device_models
import com.hereliesaz.geministrator.resources.memory_pair_summary_violations_a_link_recalled
import com.hereliesaz.geministrator.resources.memory_parked_at
import com.hereliesaz.geministrator.resources.memory_paste_exported_memory_it_replaces_what
import com.hereliesaz.geministrator.resources.memory_project
import com.hereliesaz.geministrator.resources.memory_projects
import com.hereliesaz.geministrator.resources.memory_queue
import com.hereliesaz.geministrator.resources.memory_raw
import com.hereliesaz.geministrator.resources.memory_raw_global
import com.hereliesaz.geministrator.resources.memory_raw_history_characters_in
import com.hereliesaz.geministrator.resources.memory_replace_memory
import com.hereliesaz.geministrator.resources.memory_retry_all_parked
import com.hereliesaz.geministrator.resources.memory_retry_gives_it_fresh_attempts_discard
import com.hereliesaz.geministrator.resources.memory_runs_on
import com.hereliesaz.geministrator.resources.memory_similarity_needed_to_condense_0_1
import com.hereliesaz.geministrator.resources.memory_stored_memory
import com.hereliesaz.geministrator.resources.memory_summaries
import com.hereliesaz.geministrator.resources.memory_summaries_categories_and_associations_that_agents
import com.hereliesaz.geministrator.resources.memory_summarizer
import com.hereliesaz.geministrator.resources.memory_summary_tree
import com.hereliesaz.geministrator.resources.memory_summary_tree_root_first
import com.hereliesaz.geministrator.resources.memory_this_deletes_every_stored_memory_on
import com.hereliesaz.geministrator.resources.memory_this_platform_has_no_on_device
import com.hereliesaz.geministrator.resources.memory_time_ms
import com.hereliesaz.geministrator.resources.memory_tuning
import com.hereliesaz.geministrator.resources.memory_use_this_model
import com.hereliesaz.geministrator.resources.memory_what_it_replaced_and_was_condensed
import com.hereliesaz.geministrator.resources.memory_why_include_another_project_required
import com.hereliesaz.geministrator.resources.memory_workflow_memory_bank
import com.hereliesaz.geministrator.resources.memory_workflows_to_it_expansions_are_permanent
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The memory layer, whole: the pipeline alive in the terrarium, and every control over it: engines
 * per stage, tuning, the queue, on-device models, and the stored memory itself.
 */
@Composable
internal fun MemoryLayerScreen(
    controller: MemoryLayerController?,
    connectedProviderIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.memory_memory), style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        if (controller == null) {
            Text(
                stringResource(Res.string.memory_memory_is_not_available_in_this),
                style = AzphaltType.body,
                color = Azphalt.currentGround.onPage,
            )
            return@Column
        }
        MemoryLayerControls(controller, connectedProviderIds)
    }
}

@Composable
private fun MemoryLayerControls(controller: MemoryLayerController, connectedProviderIds: Set<String>) {
    val scope = rememberCoroutineScope()
    val settings by controller.settings.collectAsState()
    val engines by controller.engines.collectAsState()
    val snapshot by controller.snapshot.collectAsState()
    val activity by controller.activity.collectAsState()
    var selectedStage by remember { mutableStateOf(MemoryTerrariumStage.Sectioning) }
    fun update(transform: (MemoryLayerSettings) -> MemoryLayerSettings) {
        scope.launch { controller.updateSettings(transform) }
    }

    Text(
        stringResource(Res.string.memory_every_finished_session_is_banked_here) +
            stringResource(Res.string.memory_summaries_categories_and_associations_that_agents),
        style = AzphaltType.body,
        color = Azphalt.currentGround.onPage,
    )
    // Raw history (full session context) is kept until the user says otherwise.
    val rawUsage by controller.rawUsage.collectAsState()
    Text(
        stringResource(Res.string.memory_raw_history_characters_in, rawUsage.characters, rawUsage.episodes) +
            (if (rawUsage.purgedEpisodes > 0) stringResource(Res.string.memory_purged_by_your_retention_setting, rawUsage.purgedEpisodes) else "") +
            stringResource(Res.string.memory_consolidated_memory_never_depends_on_it),
        style = AzphaltType.body,
        color = Azphalt.currentGround.onPage,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            stringResource(Res.string.memory_keep_all_raw_history) to com.hereliesaz.geministrator.memory.MemoryRawRetention.KeepAll,
            stringResource(Res.string.memory_keep_50_m_characters) to com.hereliesaz.geministrator.memory.MemoryRawRetention(
                com.hereliesaz.geministrator.memory.MemoryRawRetention.Mode.CapBySize, maxCharacters = 50_000_000L,
            ),
            stringResource(Res.string.memory_keep_90_days) to com.hereliesaz.geministrator.memory.MemoryRawRetention(
                com.hereliesaz.geministrator.memory.MemoryRawRetention.Mode.CapByAge, maxAgeMillis = 90L * 86_400_000L,
            ),
        ).forEach { (label, retention) ->
            AzphaltPill(
                label,
                "memory-retention-${retention.mode.name}",
                selected = settings.rawRetention == retention,
                onClick = { update { it.copy(rawRetention = retention) } },
            )
        }
    }
    // Every workflow run has its own memory bank (reading through its ancestors'); this screen shows one at a time.
    val knownBanks by controller.knownBanks.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    if (knownBanks.isEmpty()) {
        Text(stringResource(Res.string.memory_no_workflow_has_a_memory_bank), style = AzphaltType.body, color = Azphalt.currentGround.onPage)
    } else {
        Text(stringResource(Res.string.memory_workflow_memory_bank), style = AzphaltType.body, color = Azphalt.currentGround.onPage)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            knownBanks.forEach { bank ->
                AzphaltPill(
                    bank,
                    "memory-bank-$bank",
                    selected = bank == (selectedBank ?: knownBanks.first()),
                    onClick = { scope.launch { controller.selectBank(bank) } },
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            if (settings.enabled) stringResource(Res.string.memory_memory_on) else stringResource(Res.string.memory_memory_off),
            "memory-enabled",
            selected = settings.enabled,
            onClick = { update { it.copy(enabled = !it.enabled) } },
        )
        AzphaltPill(
            if (settings.consolidationPaused) stringResource(Res.string.memory_consolidation_paused) else stringResource(Res.string.memory_consolidation_running),
            "memory-paused",
            selected = !settings.consolidationPaused,
            onClick = { update { it.copy(consolidationPaused = !it.consolidationPaused) } },
        )
    }

    val projection = remember(snapshot, settings, engines, activity) {
        projectMemoryTerrarium(snapshot, settings, engines, activity)
    }
    // No selection passed in: selecting would zoom onto one creature and hide the pipeline.
    Box(Modifier.fillMaxWidth().height(440.dp).clipToBounds()) {
        PlatformNodeTerrarium(
            subjects = projection.subjects,
            relationships = projection.relationships,
            adornments = emptyMap(),
            serviceVisits = emptyList(),
            selectedId = null,
            onNodeSelected = { node -> MemoryTerrariumStage.entries.firstOrNull { it.id == node.id }?.let { selectedStage = it } },
            onNodeMoved = { _, _ -> },
            onNodeDroppedOn = { _, _ -> },
            modifier = Modifier.fillMaxWidth().height(440.dp),
            editable = false,
        )
    }

    MemorySectionLabel(stringResource(Res.string.memory_stage, selectedStage.stage.name))
    selectedStage.roles.forEach { role ->
        StageEngineEditor(
            role = role,
            engine = settings.engineFor(role),
            resolved = engines.resolved[role] ?: MemoryEngineKind.Programmatic,
            fallback = engines.fallbacks[role],
            localAvailable = controller.localModels != null,
            connectedProviderIds = connectedProviderIds,
            onChange = { engine -> update { it.withEngine(role, engine) } },
        )
    }

    QueueSection(controller, settings)
    SummariesSection(controller)
    ProjectsSection(controller, settings)
    TuningSection(settings, onApply = { policy -> update { it.copy(policy = policy) } })
    ModelsSection(controller)
    DataSection(controller)
}

@Composable
private fun StageEngineEditor(
    role: MemoryMicroAgentRole,
    engine: MemoryStageEngine,
    resolved: MemoryEngineKind,
    fallback: String?,
    localAvailable: Boolean,
    connectedProviderIds: Set<String>,
    onChange: (MemoryStageEngine) -> Unit,
) {
    var modelDraft by remember(role, engine.model) { mutableStateOf(engine.model.orEmpty()) }
    AzphaltRecord(
        seed = "memory-engine-${role.name}",
        eyebrow = stringResource(Res.string.memory_clerk),
        title = role.name,
        body = buildString {
            append(stringResource(Res.string.memory_runs_on, resolved.shortLabel))
            if (engine.kind == MemoryEngineKind.HostedModel && resolved == MemoryEngineKind.HostedModel) {
                append(" · ${engine.providerId ?: "default provider"}")
                engine.model?.let { append(" · $it") }
            }
            fallback?.let { append(stringResource(Res.string.memory_running_programmatically_instead, it)) }
        },
        endCap = resolved.shortLabel,
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    MemoryLayerSettings.supportedEngines(role).forEach { kind ->
                        AzphaltPill(
                            kind.shortLabel,
                            "memory-engine-${role.name}-${kind.name}",
                            selected = engine.kind == kind,
                            onClick = { onChange(engine.copy(kind = kind)) },
                        )
                    }
                }
                if (engine.kind == MemoryEngineKind.LocalModel && !localAvailable) {
                    Text(
                        stringResource(Res.string.memory_no_on_device_memory_models_on),
                        style = AzphaltType.body,
                        color = Azphalt.currentGround.onPage,
                    )
                }
                if (engine.kind == MemoryEngineKind.HostedModel) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        AzphaltPill(
                            stringResource(Res.string.memory_default_provider),
                            "memory-provider-${role.name}-default",
                            selected = engine.providerId == null,
                            onClick = { onChange(engine.copy(providerId = null)) },
                        )
                        ProviderCatalog.entries
                            .filter { it.id in connectedProviderIds || it.keyOptional }
                            .forEach { entry ->
                                AzphaltPill(
                                    entry.displayName,
                                    "memory-provider-${role.name}-${entry.id}",
                                    selected = engine.providerId == entry.id,
                                    onClick = { onChange(engine.copy(providerId = entry.id)) },
                                )
                            }
                    }
                    OutlinedTextField(
                        value = modelDraft,
                        onValueChange = { modelDraft = it },
                        label = { Text(stringResource(Res.string.memory_model_blank_for_the_provider_s)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AzphaltPill(
                        stringResource(Res.string.memory_use_this_model),
                        "memory-model-${role.name}",
                        onClick = { onChange(engine.copy(model = modelDraft.trim().ifEmpty { null })) },
                    )
                }
            }
        },
    )
}

@Composable
private fun QueueSection(controller: MemoryLayerController, settings: MemoryLayerSettings) {
    val scope = rememberCoroutineScope()
    val snapshot by controller.snapshot.collectAsState()
    val parked = snapshot.parked(settings.policy)
    val waiting = snapshot.queue.count { it.status == MemoryQueueStatus.Pending || it.status == MemoryQueueStatus.Processing }
    MemorySectionLabel(stringResource(Res.string.memory_queue))
    AzphaltRecord(
        seed = "memory-queue",
        eyebrow = stringResource(Res.string.memory_consolidation),
        title = stringResource(Res.string.memory_waiting_parked, waiting, parked.size),
        body = stringResource(Res.string.memory_an_entry_that_fails_times, settings.policy.maxAttempts) +
            stringResource(Res.string.memory_retry_gives_it_fresh_attempts_discard),
    )
    if (parked.size > 1) {
        AzphaltPill(stringResource(Res.string.memory_retry_all_parked), "memory-retry-all", onClick = { scope.launch { controller.retryAllParked() } })
    }
    parked.forEach { entry ->
        AzphaltRecord(
            seed = "memory-parked-${entry.id.value}",
            eyebrow = stringResource(Res.string.memory_parked_at, entry.stage.name),
            title = entry.episodeId.value,
            body = entry.lastError ?: stringResource(Res.string.memory_no_error_recorded),
            well = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltPill(stringResource(Res.string.common_retry), "memory-retry-${entry.id.value}", onClick = { scope.launch { controller.retry(entry.id) } })
                    AzphaltPill(stringResource(Res.string.memory_discard), "memory-discard-${entry.id.value}", onClick = { scope.launch { controller.discard(entry.id) } })
                }
            },
        )
    }
}

@Composable
private fun TuningSection(settings: MemoryLayerSettings, onApply: (com.hereliesaz.geministrator.memory.MemoryConsolidationPolicy) -> Unit) {
    val policy = settings.policy
    var attempts by remember(policy) { mutableStateOf(policy.maxAttempts.toString()) }
    var packetItems by remember(policy) { mutableStateOf(policy.maxPacketItems.toString()) }
    var packetChars by remember(policy) { mutableStateOf(policy.maxPacketChars.toString()) }
    var mutations by remember(policy) { mutableStateOf(policy.maxMutationsPerPacket.toString()) }
    var similarity by remember(policy) { mutableStateOf(policy.minimumSimilarityWeight.toString()) }
    var batch by remember(policy) { mutableStateOf(policy.condensationBatchSize.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    MemorySectionLabel(stringResource(Res.string.memory_tuning))
    @Composable
    fun field(label: String, value: String, onValue: (String) -> Unit) = OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    field(stringResource(Res.string.memory_attempts_before_parking), attempts) { attempts = it }
    field(stringResource(Res.string.memory_items_per_packet), packetItems) { packetItems = it }
    field(stringResource(Res.string.memory_characters_per_packet), packetChars) { packetChars = it }
    field(stringResource(Res.string.memory_changes_per_packet), mutations) { mutations = it }
    field(stringResource(Res.string.memory_similarity_needed_to_condense_0_1), similarity) { similarity = it }
    field(stringResource(Res.string.memory_memories_condensed_together), batch) { batch = it }
    error?.let { Text(it, style = AzphaltType.body, color = Azphalt.currentGround.onPage) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(stringResource(Res.string.memory_apply_tuning), "memory-tuning-apply", onClick = {
            val next = runCatching {
                policy.copy(
                    maxAttempts = attempts.trim().toInt(),
                    maxPacketItems = packetItems.trim().toInt(),
                    maxPacketChars = packetChars.trim().toInt(),
                    maxMutationsPerPacket = mutations.trim().toInt(),
                    minimumSimilarityWeight = similarity.trim().toFloat(),
                    condensationBatchSize = batch.trim().toInt(),
                )
            }
            error = next.exceptionOrNull()?.let { "Not applied: ${it.message ?: "check the values"}" }
            next.getOrNull()?.let(onApply)
        })
        AzphaltPill(stringResource(Res.string.memory_defaults), "memory-tuning-defaults", onClick = {
            error = null
            onApply(com.hereliesaz.geministrator.memory.MemoryConsolidationPolicy())
        })
    }
}

@Composable
private fun ModelsSection(controller: MemoryLayerController) {
    MemorySectionLabel(stringResource(Res.string.memory_on_device_models))
    val manager = controller.localModels
    if (manager == null) {
        Text(
            stringResource(Res.string.memory_this_platform_has_no_on_device),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        return
    }
    val scope = rememberCoroutineScope()
    val models by manager.models.collectAsState()
    Text(
        stringResource(Res.string.memory_needed_only_for_stages_set_to),
        style = AzphaltType.body,
        color = Azphalt.currentGround.onPage,
    )
    models.forEach { model ->
        AzphaltRecord(
            seed = "memory-model-${model.role.name}",
            eyebrow = model.role.name,
            title = model.name,
            body = model.error ?: if (model.installed) stringResource(Res.string.memory_installed) else stringResource(Res.string.memory_not_installed),
            endCap = when {
                model.installing -> "…"
                model.installed -> stringResource(Res.string.memory_installed)
                else -> null
            },
            well = {
                if (!model.installing) {
                    AzphaltPill(
                        if (model.installed) stringResource(Res.string.common_remove) else stringResource(Res.string.memory_install),
                        "memory-model-action-${model.role.name}",
                        onClick = {
                            scope.launch { if (model.installed) manager.remove(model.role) else manager.install(model.role) }
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun DataSection(controller: MemoryLayerController) {
    val scope = rememberCoroutineScope()
    val snapshot by controller.snapshot.collectAsState()
    var exported by remember { mutableStateOf<String?>(null) }
    var importDraft by remember { mutableStateOf<String?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var openTree by remember { mutableStateOf<com.hereliesaz.geministrator.memory.MemoryEpisodeId?>(null) }

    MemorySectionLabel(stringResource(Res.string.memory_stored_memory))
    AzphaltRecord(
        seed = "memory-counts",
        eyebrow = stringResource(Res.string.memory_graph),
        title = stringResource(Res.string.memory_episodes, snapshot.episodes.size),
        body = stringResource(Res.string.memory_sections_memories_links, snapshot.sections.size, snapshot.nodes.size, snapshot.edges.size),
    )
    snapshot.episodes.sortedByDescending { it.createdAtEpochMillis }.take(RECENT_EPISODES).forEach { episode ->
        AzphaltRecord(
            seed = "memory-episode-${episode.id.value}",
            eyebrow = episode.projectId ?: stringResource(Res.string.memory_episode),
            title = episode.userPrompt.lineSequence().firstOrNull().orEmpty().take(120).ifBlank { episode.id.value },
            body = stringResource(Res.string.memory_chunks, episode.chunks.size),
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            if (episode.id == openTree) stringResource(Res.string.memory_hide_summary_tree) else stringResource(Res.string.memory_summary_tree),
                            "memory-tree-${episode.id.value}",
                            selected = episode.id == openTree,
                            onClick = { openTree = if (episode.id == openTree) null else episode.id },
                        )
                        AzphaltPill(stringResource(Res.string.memory_forget), "memory-forget-${episode.id.value}", onClick = {
                            scope.launch { controller.forgetEpisode(episode.id) }
                        })
                    }
                    if (episode.id == openTree) {
                        val rows = MemoryInspection.outline(snapshot, episode.id)
                        if (rows.isEmpty()) {
                            MemoryBody(stringResource(Res.string.memory_no_summary_tree_yet_it_is))
                        }
                        rows.forEach { row ->
                            MemoryBody(
                                "  ".repeat(row.level) + (if (row.leaf) "· " else "▸ ") + row.node.text.replace('\n', ' ').take(OUTLINE_ROW_CHARS),
                            )
                        }
                    }
                }
            },
        )
    }

    val currentExport = exported
    if (currentExport == null) {
        AzphaltPill(stringResource(Res.string.memory_export_memory_to_json), "memory-export", onClick = {
            scope.launch { exported = controller.exportJson() }
        }, modifier = Modifier.fillMaxWidth())
    } else {
        OutlinedTextField(
            value = currentExport,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(Res.string.memory_exported_memory_chars, currentExport.length)) },
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        AzphaltPill(stringResource(Res.string.common_dismiss), "memory-export-dismiss", onClick = { exported = null })
    }

    val draft = importDraft
    if (draft == null) {
        AzphaltPill(stringResource(Res.string.memory_import_memory_from_json), "memory-import", onClick = { importDraft = "" }, modifier = Modifier.fillMaxWidth())
    } else {
        OutlinedTextField(
            value = draft,
            onValueChange = { importDraft = it },
            label = { Text(stringResource(Res.string.memory_paste_exported_memory_it_replaces_what)) },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(stringResource(Res.string.memory_replace_memory), "memory-import-confirm", onClick = {
                scope.launch {
                    message = runCatching { controller.importJson(draft) }
                        .fold({ getString(Res.string.memory_imported) }, { "Not imported: ${it.message ?: "unreadable JSON"}" })
                    importDraft = null
                }
            })
            AzphaltPill(stringResource(Res.string.common_cancel), "memory-import-cancel", onClick = { importDraft = null })
        }
    }

    if (!clearConfirm) {
        AzphaltPill(stringResource(Res.string.memory_forget_everything), "memory-clear", onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            stringResource(Res.string.memory_this_deletes_every_stored_memory_on),
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(stringResource(Res.string.memory_forget_everything), "memory-clear-confirm", onClick = {
                scope.launch {
                    controller.clearAll()
                    clearConfirm = false
                    message = getString(Res.string.memory_memory_cleared)
                }
            })
            AzphaltPill(stringResource(Res.string.memory_keep_it), "memory-clear-cancel", onClick = { clearConfirm = false })
        }
    }
    message?.let { Text(it, style = AzphaltType.body, color = Azphalt.currentGround.onPage) }
}

/** Summarizer cost, and one memory's summary-tree levels, pair summaries and explicit history. */
@Composable
private fun SummariesSection(controller: MemoryLayerController) {
    val scope = rememberCoroutineScope()
    val snapshot by controller.snapshot.collectAsState()
    val metrics by controller.summaryMetrics.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    var selected by remember { mutableStateOf<MemoryNodeId?>(null) }
    var history by remember { mutableStateOf<List<MemoryNode>?>(null) }

    MemorySectionLabel(stringResource(Res.string.memory_summaries))
    AzphaltRecord(
        seed = "memory-summary-metrics",
        eyebrow = stringResource(Res.string.memory_summarizer),
        title = stringResource(Res.string.memory_summaries_tree_nodes, metrics.summaries, metrics.treeNodes, metrics.pairSummaries),
        body = buildString {
            append(stringResource(Res.string.memory_model_calls, metrics.modelCalls))
            if (metrics.modelFailures > 0) append(stringResource(Res.string.memory_failed, metrics.modelFailures))
            append(stringResource(Res.string.memory_embedding_calls, metrics.embeddingCalls))
            if (metrics.embeddingFailures > 0) append(stringResource(Res.string.memory_failed, metrics.embeddingFailures))
            append(stringResource(Res.string.memory_time_ms, metrics.millis))
            append(stringResource(Res.string.memory_pair_summary_violations_a_link_recalled, metrics.pairSummaryViolations))
            if (metrics.byEngine.isNotEmpty()) {
                append(stringResource(Res.string.memory_by_engine)).append(metrics.byEngine.entries.joinToString(" · ") { "${it.key} ${it.value}" })
            }
            append(stringResource(Res.string.memory_counted_since_this_bank_s_layer))
        },
        endCap = if (metrics.pairSummaryViolations > 0) stringResource(Res.string.memory_late, metrics.pairSummaryViolations) else null,
    )

    val memories = remember(snapshot) { MemoryInspection.inspectable(snapshot) }
    if (memories.isEmpty()) {
        MemoryBody(stringResource(Res.string.memory_no_memories_to_inspect_yet))
        return
    }
    MemoryBody(stringResource(Res.string.memory_choose_a_memory_to_see_its))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        memories.forEach { node ->
            AzphaltPill(
                node.text.lineSequence().firstOrNull().orEmpty().take(40).ifBlank { node.id.value },
                "memory-inspect-${node.id.value}",
                selected = node.id == selected,
                onClick = {
                    selected = if (node.id == selected) null else node.id
                    history = null
                },
            )
        }
    }
    val node = memories.firstOrNull { it.id == selected } ?: return
    val outline = remember(snapshot, node) { MemoryInspection.outlineAbove(snapshot, node) }
    val pairs = remember(snapshot, node) { MemoryInspection.pairs(snapshot, node.id) }
    AzphaltRecord(
        seed = "memory-inspected-${node.id.value}",
        eyebrow = stringResource(Res.string.memory_memory_node, node.kind.name, node.id.value),
        title = node.text.take(INSPECTED_CHARS),
        body = if (outline.isEmpty()) stringResource(Res.string.memory_no_summary_tree_levels_above_this) else
            stringResource(Res.string.memory_summary_tree_root_first) + outline.mapIndexed { level, it -> "  ".repeat(level) + "▸ " + it.text.replace('\n', ' ').take(OUTLINE_ROW_CHARS) }.joinToString("\n"),
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(stringResource(Res.string.memory_history), "memory-history-${node.id.value}", onClick = {
                    val bank = selectedBank ?: return@AzphaltPill
                    scope.launch { history = controller.history(bank, node.id) }
                })
                history?.let { past ->
                    MemoryBody(
                        if (past.isEmpty()) stringResource(Res.string.memory_no_history_this_memory_replaced_and) else
                            stringResource(Res.string.memory_what_it_replaced_and_was_condensed) + past.joinToString("\n") { "- ${it.kind.name}: ${it.text.replace('\n', ' ').take(OUTLINE_ROW_CHARS)}" },
                    )
                }
            }
        },
    )
    pairs.forEach { pair ->
        AzphaltRecord(
            seed = "memory-pair-${pair.edge.id.value}",
            eyebrow = stringResource(Res.string.memory_link, pair.edge.relation.name),
            title = pair.partner?.text?.replace('\n', ' ')?.take(OUTLINE_ROW_CHARS) ?: pair.edge.id.value,
            body = pair.summary?.text ?: stringResource(Res.string.memory_no_pair_summary_yet_the_next),
        )
    }
}

/** Per-project raw retention and project expansion (add-only). */
@Composable
private fun ProjectsSection(controller: MemoryLayerController, settings: MemoryLayerSettings) {
    val scope = rememberCoroutineScope()
    val log by controller.lineageLog.collectAsState()
    val projects = remember(log) { log.workflows.mapNotNull { it.projectId }.distinct().sorted() }
    MemorySectionLabel(stringResource(Res.string.memory_projects))
    if (projects.isEmpty()) {
        MemoryBody(stringResource(Res.string.memory_no_project_has_a_workflow_with))
        return
    }
    MemoryBody(
        stringResource(Res.string.memory_a_project_s_workflows_read_each) +
            stringResource(Res.string.memory_workflows_to_it_expansions_are_permanent),
    )
    projects.forEach { project ->
        var reason by remember(project) { mutableStateOf("") }
        var message by remember(project) { mutableStateOf<String?>(null) }
        val includes = log.expansions.filter { it.projectId == project }
        AzphaltRecord(
            seed = "memory-project-$project",
            eyebrow = stringResource(Res.string.memory_project),
            title = project,
            body = buildString {
                append(stringResource(Res.string.memory_workflows, log.workflows.count { it.projectId == project }))
                if (includes.isNotEmpty()) {
                    append(stringResource(Res.string.memory_includes)).append(includes.joinToString(" · ") { "${it.incorporatesProjectId} (${it.reason})" })
                }
            },
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = settings.rawRetentionByProject[project]
                        AzphaltPill(stringResource(Res.string.memory_raw_global), "memory-project-retention-$project-global", selected = current == null, onClick = {
                            scope.launch { controller.setProjectRawRetention(project, null) }
                        })
                        RETENTION_CHOICES.forEach { (label, retention) ->
                            AzphaltPill(stringResource(Res.string.memory_raw, label), "memory-project-retention-$project-${retention.mode.name}", selected = current == retention, onClick = {
                                scope.launch { controller.setProjectRawRetention(project, retention) }
                            })
                        }
                    }
                    val others = projects.filter { other -> other != project && includes.none { it.incorporatesProjectId == other } }
                    if (others.isNotEmpty()) {
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            label = { Text(stringResource(Res.string.memory_why_include_another_project_required)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            others.forEach { other ->
                                AzphaltPill(stringResource(Res.string.memory_include, other), "memory-project-expand-$project-$other", onClick = {
                                    if (reason.isBlank()) {
                                        message = "Give a reason first."
                                    } else {
                                        scope.launch {
                                            message = runCatching { controller.expandProject(project, other, by = "user", reason = reason.trim()) }
                                                .fold({ getString(Res.string.memory_now_includes, other) }, { "Not expanded: ${it.message}" })
                                            reason = ""
                                        }
                                    }
                                })
                            }
                        }
                    }
                    message?.let { MemoryBody(it) }
                }
            },
        )
    }
}

@Composable
private fun MemoryBody(text: String) {
    Text(text, style = AzphaltType.body, color = Azphalt.currentGround.onPage)
}

@Composable
private fun MemorySectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}

private const val RECENT_EPISODES = 8
private const val OUTLINE_ROW_CHARS = 160
private const val INSPECTED_CHARS = 240

private val RETENTION_CHOICES = listOf(
    "keep all" to MemoryRawRetention.KeepAll,
    "50 M characters" to MemoryRawRetention(MemoryRawRetention.Mode.CapBySize, maxCharacters = 50_000_000L),
    "90 days" to MemoryRawRetention(MemoryRawRetention.Mode.CapByAge, maxAgeMillis = 90L * 86_400_000L),
)
