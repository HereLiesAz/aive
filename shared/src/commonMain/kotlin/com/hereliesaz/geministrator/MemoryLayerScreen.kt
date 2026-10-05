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
    // Raw history (full session context) is kept until the user says otherwise.
    val rawUsage by controller.rawUsage.collectAsState()
    Text(
        "Raw history: ${rawUsage.characters} characters in ${rawUsage.episodes} sessions" +
            (if (rawUsage.purgedEpisodes > 0) " (${rawUsage.purgedEpisodes} purged by your retention setting)" else "") +
            ". Consolidated memory never depends on it.",
        style = AzphaltType.body,
        color = Azphalt.currentGround.onPage,
    )
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "Keep all raw history" to com.hereliesaz.geministrator.memory.MemoryRawRetention.KeepAll,
            "Keep 50 M characters" to com.hereliesaz.geministrator.memory.MemoryRawRetention(
                com.hereliesaz.geministrator.memory.MemoryRawRetention.Mode.CapBySize, maxCharacters = 50_000_000L,
            ),
            "Keep 90 days" to com.hereliesaz.geministrator.memory.MemoryRawRetention(
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
    var openTree by remember { mutableStateOf<com.hereliesaz.geministrator.memory.MemoryEpisodeId?>(null) }

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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AzphaltPill(
                            if (episode.id == openTree) "Hide summary tree" else "Summary tree",
                            "memory-tree-${episode.id.value}",
                            selected = episode.id == openTree,
                            onClick = { openTree = if (episode.id == openTree) null else episode.id },
                        )
                        AzphaltPill("Forget", "memory-forget-${episode.id.value}", onClick = {
                            scope.launch { controller.forgetEpisode(episode.id) }
                        })
                    }
                    if (episode.id == openTree) {
                        val rows = MemoryInspection.outline(snapshot, episode.id)
                        if (rows.isEmpty()) {
                            MemoryBody("No summary tree yet: it is built when this session reaches Condensation.")
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

/** Summarizer cost, and one memory's summary-tree levels, pair summaries and explicit history. */
@Composable
private fun SummariesSection(controller: MemoryLayerController) {
    val scope = rememberCoroutineScope()
    val snapshot by controller.snapshot.collectAsState()
    val metrics by controller.summaryMetrics.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    var selected by remember { mutableStateOf<MemoryNodeId?>(null) }
    var history by remember { mutableStateOf<List<MemoryNode>?>(null) }

    MemorySectionLabel("Summaries")
    AzphaltRecord(
        seed = "memory-summary-metrics",
        eyebrow = "Summarizer",
        title = "${metrics.summaries} summaries · ${metrics.treeNodes} tree nodes · ${metrics.pairSummaries} pair summaries",
        body = buildString {
            append("Model calls: ${metrics.modelCalls}")
            if (metrics.modelFailures > 0) append(" (${metrics.modelFailures} failed)")
            append(" · embedding calls: ${metrics.embeddingCalls}")
            if (metrics.embeddingFailures > 0) append(" (${metrics.embeddingFailures} failed)")
            append(" · time: ${metrics.millis} ms")
            append("\nPair-summary violations (a link recalled before its summary existed): ${metrics.pairSummaryViolations}")
            if (metrics.byEngine.isNotEmpty()) {
                append("\nBy engine: ").append(metrics.byEngine.entries.joinToString(" · ") { "${it.key} ${it.value}" })
            }
            append("\nCounted since this bank's layer was last built (a settings change rebuilds it).")
        },
        endCap = if (metrics.pairSummaryViolations > 0) "${metrics.pairSummaryViolations} late" else null,
    )

    val memories = remember(snapshot) { MemoryInspection.inspectable(snapshot) }
    if (memories.isEmpty()) {
        MemoryBody("No memories to inspect yet.")
        return
    }
    MemoryBody("Choose a memory to see its summary-tree levels and the pair summary of each of its links.")
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
        eyebrow = "${node.kind.name} · memory-node:${node.id.value}",
        title = node.text.take(INSPECTED_CHARS),
        body = if (outline.isEmpty()) "No summary-tree levels above this memory." else
            "Summary tree, root first:\n" + outline.mapIndexed { level, it -> "  ".repeat(level) + "▸ " + it.text.replace('\n', ' ').take(OUTLINE_ROW_CHARS) }.joinToString("\n"),
        well = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill("History", "memory-history-${node.id.value}", onClick = {
                    val bank = selectedBank ?: return@AzphaltPill
                    scope.launch { history = controller.history(bank, node.id) }
                })
                history?.let { past ->
                    MemoryBody(
                        if (past.isEmpty()) "No history: this memory replaced and was condensed from nothing." else
                            "What it replaced and was condensed from:\n" + past.joinToString("\n") { "- ${it.kind.name}: ${it.text.replace('\n', ' ').take(OUTLINE_ROW_CHARS)}" },
                    )
                }
            }
        },
    )
    pairs.forEach { pair ->
        AzphaltRecord(
            seed = "memory-pair-${pair.edge.id.value}",
            eyebrow = "${pair.edge.relation.name} link",
            title = pair.partner?.text?.replace('\n', ' ')?.take(OUTLINE_ROW_CHARS) ?: pair.edge.id.value,
            body = pair.summary?.text ?: "No pair summary yet; the next consolidation pass writes it.",
        )
    }
}

/** Per-project raw retention and project expansion (add-only). */
@Composable
private fun ProjectsSection(controller: MemoryLayerController, settings: MemoryLayerSettings) {
    val scope = rememberCoroutineScope()
    val log by controller.lineageLog.collectAsState()
    val projects = remember(log) { log.workflows.mapNotNull { it.projectId }.distinct().sorted() }
    MemorySectionLabel("Projects")
    if (projects.isEmpty()) {
        MemoryBody("No project has a workflow with memory yet.")
        return
    }
    MemoryBody(
        "A project's workflows read each other's memory, read-only. Expanding a project adds another project's " +
            "workflows to it; expansions are permanent. Raw history can be kept per project; otherwise the global setting applies.",
    )
    projects.forEach { project ->
        var reason by remember(project) { mutableStateOf("") }
        var message by remember(project) { mutableStateOf<String?>(null) }
        val includes = log.expansions.filter { it.projectId == project }
        AzphaltRecord(
            seed = "memory-project-$project",
            eyebrow = "Project",
            title = project,
            body = buildString {
                append("${log.workflows.count { it.projectId == project }} workflows")
                if (includes.isNotEmpty()) {
                    append("\nIncludes: ").append(includes.joinToString(" · ") { "${it.incorporatesProjectId} (${it.reason})" })
                }
            },
            well = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val current = settings.rawRetentionByProject[project]
                        AzphaltPill("Raw: global", "memory-project-retention-$project-global", selected = current == null, onClick = {
                            scope.launch { controller.setProjectRawRetention(project, null) }
                        })
                        RETENTION_CHOICES.forEach { (label, retention) ->
                            AzphaltPill("Raw: $label", "memory-project-retention-$project-${retention.mode.name}", selected = current == retention, onClick = {
                                scope.launch { controller.setProjectRawRetention(project, retention) }
                            })
                        }
                    }
                    val others = projects.filter { other -> other != project && includes.none { it.incorporatesProjectId == other } }
                    if (others.isNotEmpty()) {
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            label = { Text("Why include another project (required)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            others.forEach { other ->
                                AzphaltPill("Include $other", "memory-project-expand-$project-$other", onClick = {
                                    if (reason.isBlank()) {
                                        message = "Give a reason first."
                                    } else {
                                        scope.launch {
                                            message = runCatching { controller.expandProject(project, other, by = "user", reason = reason.trim()) }
                                                .fold({ "Now includes $other." }, { "Not expanded: ${it.message}" })
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
