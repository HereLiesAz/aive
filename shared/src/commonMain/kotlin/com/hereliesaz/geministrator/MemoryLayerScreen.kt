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
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryQueueStatus
import com.hereliesaz.geministrator.memory.MemoryStageEngine
import com.hereliesaz.geministrator.memory.parked
import kotlinx.coroutines.launch

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
        Text("MEMORY", style = AzphaltType.hero, color = Azphalt.currentGround.onPage)
        if (controller == null) {
            Text(
                "Memory is not available in this build.",
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
        "Every finished session is banked here, then worked through these stages into tags, phrases, " +
            "summaries, categories and associations that agents recall. Tap a creature to choose what runs it.",
        style = AzphaltType.body,
        color = Azphalt.currentGround.onPage,
    )
    // Every workflow run has its own memory bank (reading through its ancestors'); this screen shows one at a time.
    val knownBanks by controller.knownBanks.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    if (knownBanks.isEmpty()) {
        Text("No workflow has a memory bank yet.", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
    } else {
        Text("Workflow memory bank", style = AzphaltType.body, color = Azphalt.currentGround.onPage)
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
            if (settings.enabled) "Memory: on" else "Memory: off",
            "memory-enabled",
            selected = settings.enabled,
            onClick = { update { it.copy(enabled = !it.enabled) } },
        )
        AzphaltPill(
            if (settings.consolidationPaused) "Consolidation: paused" else "Consolidation: running",
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

    MemorySectionLabel("${selectedStage.stage.name} stage")
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
        eyebrow = "Clerk",
        title = role.name,
        body = buildString {
            append("Runs on: ${resolved.shortLabel}")
            if (engine.kind == MemoryEngineKind.HostedModel && resolved == MemoryEngineKind.HostedModel) {
                append(" · ${engine.providerId ?: "default provider"}")
                engine.model?.let { append(" · $it") }
            }
            fallback?.let { append("\n$it; running programmatically instead.") }
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
                        "No on-device memory models on this platform.",
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
                            "Default provider",
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
                        label = { Text("Model (blank for the provider's default)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AzphaltPill(
                        "Use this model",
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
    MemorySectionLabel("Queue")
    AzphaltRecord(
        seed = "memory-queue",
        eyebrow = "Consolidation",
        title = "$waiting waiting · ${parked.size} parked",
        body = "An entry that fails ${settings.policy.maxAttempts} times is parked so the rest keep moving. " +
            "Retry gives it fresh attempts; Discard stops consolidating it and keeps what it already produced.",
    )
    if (parked.size > 1) {
        AzphaltPill("Retry all parked", "memory-retry-all", onClick = { scope.launch { controller.retryAllParked() } })
    }
    parked.forEach { entry ->
        AzphaltRecord(
            seed = "memory-parked-${entry.id.value}",
            eyebrow = "Parked at ${entry.stage.name}",
            title = entry.episodeId.value,
            body = entry.lastError ?: "No error recorded",
            well = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AzphaltPill("Retry", "memory-retry-${entry.id.value}", onClick = { scope.launch { controller.retry(entry.id) } })
                    AzphaltPill("Discard", "memory-discard-${entry.id.value}", onClick = { scope.launch { controller.discard(entry.id) } })
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

    MemorySectionLabel("Tuning")
    @Composable
    fun field(label: String, value: String, onValue: (String) -> Unit) = OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    field("Attempts before parking", attempts) { attempts = it }
    field("Items per packet", packetItems) { packetItems = it }
    field("Characters per packet", packetChars) { packetChars = it }
    field("Changes per packet", mutations) { mutations = it }
    field("Similarity needed to condense (0–1)", similarity) { similarity = it }
    field("Memories condensed together", batch) { batch = it }
    error?.let { Text(it, style = AzphaltType.body, color = Azphalt.currentGround.onPage) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill("Apply tuning", "memory-tuning-apply", onClick = {
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
        AzphaltPill("Defaults", "memory-tuning-defaults", onClick = {
            error = null
            onApply(com.hereliesaz.geministrator.memory.MemoryConsolidationPolicy())
        })
    }
}

@Composable
private fun ModelsSection(controller: MemoryLayerController) {
    MemorySectionLabel("On-device models")
    val manager = controller.localModels
    if (manager == null) {
        Text(
            "This platform has no on-device memory models. Stages run programmatically or on a hosted provider.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        return
    }
    val scope = rememberCoroutineScope()
    val models by manager.models.collectAsState()
    Text(
        "Needed only for stages set to Local model. Each is a separate download from The Aive's GitHub release.",
        style = AzphaltType.body,
        color = Azphalt.currentGround.onPage,
    )
    models.forEach { model ->
        AzphaltRecord(
            seed = "memory-model-${model.role.name}",
            eyebrow = model.role.name,
            title = model.name,
            body = model.error ?: if (model.installed) "Installed" else "Not installed",
            endCap = when {
                model.installing -> "…"
                model.installed -> "Installed"
                else -> null
            },
            well = {
                if (!model.installing) {
                    AzphaltPill(
                        if (model.installed) "Remove" else "Install",
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

    MemorySectionLabel("Stored memory")
    AzphaltRecord(
        seed = "memory-counts",
        eyebrow = "Graph",
        title = "${snapshot.episodes.size} episodes",
        body = "${snapshot.sections.size} sections · ${snapshot.nodes.size} memories · ${snapshot.edges.size} links",
    )
    snapshot.episodes.sortedByDescending { it.createdAtEpochMillis }.take(RECENT_EPISODES).forEach { episode ->
        AzphaltRecord(
            seed = "memory-episode-${episode.id.value}",
            eyebrow = episode.projectId ?: "Episode",
            title = episode.userPrompt.lineSequence().firstOrNull().orEmpty().take(120).ifBlank { episode.id.value },
            body = "${episode.chunks.size} chunks",
            well = {
                AzphaltPill("Forget", "memory-forget-${episode.id.value}", onClick = {
                    scope.launch { controller.forgetEpisode(episode.id) }
                })
            },
        )
    }

    val currentExport = exported
    if (currentExport == null) {
        AzphaltPill("Export memory to JSON", "memory-export", onClick = {
            scope.launch { exported = controller.exportJson() }
        }, modifier = Modifier.fillMaxWidth())
    } else {
        OutlinedTextField(
            value = currentExport,
            onValueChange = {},
            readOnly = true,
            label = { Text("Exported memory (${currentExport.length} chars)") },
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        AzphaltPill("Dismiss", "memory-export-dismiss", onClick = { exported = null })
    }

    val draft = importDraft
    if (draft == null) {
        AzphaltPill("Import memory from JSON", "memory-import", onClick = { importDraft = "" }, modifier = Modifier.fillMaxWidth())
    } else {
        OutlinedTextField(
            value = draft,
            onValueChange = { importDraft = it },
            label = { Text("Paste exported memory; it replaces what is stored") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill("Replace memory", "memory-import-confirm", onClick = {
                scope.launch {
                    message = runCatching { controller.importJson(draft) }
                        .fold({ "Imported." }, { "Not imported: ${it.message ?: "unreadable JSON"}" })
                    importDraft = null
                }
            })
            AzphaltPill("Cancel", "memory-import-cancel", onClick = { importDraft = null })
        }
    }

    if (!clearConfirm) {
        AzphaltPill("Forget everything", "memory-clear", onClick = { clearConfirm = true }, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            "This deletes every stored memory on this device. Export first if you may want it back.",
            style = AzphaltType.body,
            color = Azphalt.currentGround.onPage,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill("Forget everything", "memory-clear-confirm", onClick = {
                scope.launch {
                    controller.clearAll()
                    clearConfirm = false
                    message = "Memory cleared."
                }
            })
            AzphaltPill("Keep it", "memory-clear-cancel", onClick = { clearConfirm = false })
        }
    }
    message?.let { Text(it, style = AzphaltType.body, color = Azphalt.currentGround.onPage) }
}

@Composable
private fun MemorySectionLabel(label: String) {
    Text(label.uppercase(), style = AzphaltType.eyebrow, color = Azphalt.currentGround.onPage)
}

private const val RECENT_EPISODES = 8
