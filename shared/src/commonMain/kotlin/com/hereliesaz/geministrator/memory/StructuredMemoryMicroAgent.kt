package com.hereliesaz.geministrator.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Runtime-agnostic structured worker for the eight autoregressive memory clerks. */
class StructuredMemoryMicroAgent(
    override val role: MemoryMicroAgentRole,
    override val model: MemoryMicroAgentModelSpec,
    private val runtime: MemoryGenerativeInferenceRuntime,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
    private val nowEpochMillis: () -> Long = ::microAgentNowEpochMillis,
) : MemoryMicroAgent {
    init {
        require(role != MemoryMicroAgentRole.AssociationLinker) {
            "AssociationLinker is embedding inference; use EmbeddingAssociationLinkerMicroAgent"
        }
        require(model.requirements.workload == MemoryInferenceWorkload.AutoregressiveGeneration)
        require(model.deployment.artifactsFor(runtime.platform).isNotEmpty()) {
            "Model ${model.modelId} has no ${runtime.platform} deployment artifact"
        }
    }

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val artifact = selectArtifact()
        val prompt = packet.renderMicroAgentPrompt(role)
        require(prompt.length <= model.maxInputChars) {
            "Rendered ${role.name} prompt has ${prompt.length} chars; limit is ${model.maxInputChars}"
        }
        val result = runtime.generate(
            MemoryGenerativeInferenceRequest(
                role = role,
                model = model,
                artifact = artifact,
                prompt = prompt,
            ),
        )
        require(result.text.length <= model.maxOutputChars) {
            "${role.name} output has ${result.text.length} chars; limit is ${model.maxOutputChars}"
        }
        if (role == MemoryMicroAgentRole.CondensationRewriter && result.text.isCondensationDecline()) {
            // A legitimate answer, not a failure: the consolidator records the cluster as declined.
            return MemoryMutationBatch()
        }
        val element = json.parseToJsonElement(result.text.extractMicroAgentJson())
        // Unknown keys are ignored, so an answer in another schema (e.g. the epoch-8 training format
        // `{"mutations":[...]}`) would otherwise decode to an empty proposal and silently drop the packet.
        require(element is JsonObject && PROPOSAL_KEYS.any(element::containsKey)) {
            "${role.name} output does not follow the sections/nodes/links contract" +
                ((element as? JsonObject)?.keys?.let { " (keys: ${it.joinToString()})" } ?: "")
        }
        val proposal = json.decodeFromJsonElement(MicroAgentProposal.serializer(), element)
        return proposal.toMutationBatch(packet, role, nowEpochMillis())
    }

    private suspend fun selectArtifact(): MemoryMicroAgentArtifact {
        for (candidate in model.deployment.artifactsFor(runtime.platform)) {
            if (runtime.isAvailable(model, candidate)) return candidate
        }
        error("No available ${runtime.platform} artifact for ${model.modelId}/${role.name}")
    }
}

@Serializable
private data class MicroAgentProposal(
    val sections: List<MicroSectionDraft> = emptyList(),
    val nodes: List<MicroNodeDraft> = emptyList(),
    val links: List<MicroLinkDraft> = emptyList(),
)

@Serializable
private data class MicroSectionDraft(
    val text: String,
    val sourceIds: List<String>,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class MicroNodeDraft(
    val key: String,
    val kind: String,
    val text: String,
    val sourceIds: List<String>,
    val salience: Float = 0.5f,
    val confidence: Float = 1f,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
private data class MicroLinkDraft(
    val from: String,
    val to: String,
    val relation: String,
    val weight: Float = 1f,
    val metadata: Map<String, String> = emptyMap(),
)

private fun MicroAgentProposal.toMutationBatch(
    packet: MemoryWorkPacket,
    role: MemoryMicroAgentRole,
    nowEpochMillis: Long,
): MemoryMutationBatch {
    require(sections.all { it.text.isNotBlank() && it.sourceIds.isNotEmpty() })
    require(nodes.all { it.key.isNotBlank() && it.text.isNotBlank() && it.sourceIds.isNotEmpty() })
    require(nodes.map(MicroNodeDraft::key).distinct().size == nodes.size)

    val workItems = (packet.items + packet.neighborhood).associateBy(MemoryWorkItem::id)
    val packetIds = workItems.keys
    val namespace = "${packet.queueId.value}:${packet.stage.name}:${packet.packetKey}:${role.name}"

    val builtSections = sections.mapIndexed { index, draft ->
        require(draft.sourceIds.all { it in packetIds })
        MemorySection(
            id = MemorySectionId("$namespace:section:$index"),
            episodeId = packet.episodeId,
            sourceChunkIds = draft.sourceIds.mapTo(linkedSetOf(), ::MemoryChunkId),
            ordinal = draft.sourceIds
                .mapNotNull { workItems[it]?.metadata?.get("ordinal")?.toIntOrNull() }
                .minOrNull()
                ?: index,
            text = draft.text.trim(),
            metadata = draft.metadata + ("microAgentRole" to role.name),
        )
    }

    val nodeIdsByKey = nodes.mapIndexed { index, draft ->
        draft.key to MemoryNodeId("$namespace:node:$index:${draft.key.microSafeIdPart()}")
    }.toMap()

    val builtNodes = nodes.map { draft ->
        require(draft.sourceIds.all { it in packetIds })
        val kind = runCatching { MemoryNodeKind.valueOf(draft.kind) }
            .getOrElse { error("Unknown memory node kind ${draft.kind}") }
        require(kind.isClerkMemory) { "A memory clerk may not write ${kind.name} nodes" }
        val inheritedEpisodes = draft.sourceIds
            .flatMap { workItems[it]?.microSourceEpisodeIds().orEmpty() }
            .toMutableSet()
            .apply { add(packet.episodeId) }
        val inheritedSections = draft.sourceIds
            .flatMap { workItems[it]?.microSourceSectionIds().orEmpty() }
            .toMutableSet()
        if (packet.stage == MemoryConsolidationStage.Salience) {
            draft.sourceIds.mapTo(inheritedSections, ::MemorySectionId)
        }
        MemoryNode(
            id = requireNotNull(nodeIdsByKey[draft.key]),
            kind = kind,
            text = draft.text.trim(),
            sourceEpisodeIds = inheritedEpisodes,
            sourceSectionIds = inheritedSections,
            salience = draft.salience.coerceIn(0f, 1f),
            confidence = draft.confidence.coerceIn(0f, 1f),
            createdAtEpochMillis = nowEpochMillis,
            metadata = draft.metadata + ("microAgentRole" to role.name),
        )
    }

    val knownNodeIds = workItems.values
        .filter { it.kind.startsWith("node:") }
        .mapTo(hashSetOf()) { it.id }
    val builtEdges = links.mapIndexed { index, link ->
        val from = nodeIdsByKey[link.from] ?: MemoryNodeId(link.from)
        val to = nodeIdsByKey[link.to] ?: MemoryNodeId(link.to)
        require(from.value in knownNodeIds || from in nodeIdsByKey.values)
        require(to.value in knownNodeIds || to in nodeIdsByKey.values)
        val relation = runCatching { MemoryRelationKind.valueOf(link.relation) }
            .getOrElse { error("Unknown memory relation ${link.relation}") }
        require(relation in MODEL_WRITABLE_RELATIONS) { "A memory clerk may not write ${relation.name}" }
        MemoryEdge(
            id = MemoryEdgeId("$namespace:edge:$index"),
            from = from,
            to = to,
            relation = relation,
            weight = link.weight.coerceIn(0f, 1f),
            createdAtEpochMillis = nowEpochMillis,
            metadata = link.metadata + ("microAgentRole" to role.name),
        )
    }

    return MemoryMutationBatch(
        sectionsToAdd = builtSections,
        nodesToAdd = builtNodes,
        edgesToAdd = builtEdges,
    )
}

/**
 * The text a model-backed memory clerk sees. Hosted engines send [render] as the prompt; local
 * models are trained on, and run with, [chatPrompt]: the role's [system] prompt and the rendered
 * packet in the Qwen chat template. `MemoryDatasetGenerator` builds training rows from the same
 * functions, so training and runtime cannot drift.
 */
object MemoryMicroAgentPrompts {
    fun system(role: MemoryMicroAgentRole): String =
        "You are Aive's ${role.name} memory clerk. Do only what the packet's instruction asks, using only the " +
            "material it supplies. Reply with exactly one JSON object in the sections/nodes/links contract and nothing else."

    fun render(packet: MemoryWorkPacket, role: MemoryMicroAgentRole): String = packet.renderMicroAgentPrompt(role)

    fun chatPrompt(role: MemoryMicroAgentRole, prompt: String): String = buildString {
        append("<|im_start|>system\n").append(system(role)).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(prompt).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}

/** Metadata the decoder adds or the programmatic clerks stamp; a model never writes these. */
private val DERIVED_METADATA_KEYS = setOf("microAgentRole", "semanticSource")

/**
 * [this] batch as the JSON a model clerk would answer for [packet], or null when it cannot be
 * expressed in the contract (a source or link endpoint the packet does not show). New nodes get
 * local keys `n0`, `n1`, …; links to existing nodes use their visible IDs.
 */
internal fun MemoryMutationBatch.toMicroAgentProposalJson(packet: MemoryWorkPacket): String? {
    val items = packet.items + packet.neighborhood
    val visible = items.mapTo(hashSetOf(), MemoryWorkItem::id)
    val visibleNodes = items.filter { it.kind.startsWith("node:") }.mapTo(hashSetOf(), MemoryWorkItem::id)
    val keys = nodesToAdd.mapIndexed { index, node -> node.id to "n$index" }.toMap()

    val sections = sectionsToAdd.map { section ->
        val sourceIds = section.sourceChunkIds.map(MemoryChunkId::value)
        if (sourceIds.isEmpty() || !sourceIds.all(visible::contains)) return null
        MicroSectionDraft(section.text, sourceIds, section.metadata - DERIVED_METADATA_KEYS)
    }
    val nodes = nodesToAdd.map { node ->
        val linked = edgesToAdd.flatMap { edge ->
            when (node.id) {
                edge.from -> listOf(edge.to.value)
                edge.to -> listOf(edge.from.value)
                else -> emptyList()
            }
        }
        val sourceIds = items.filter { item ->
            item.id in linked ||
                MemorySectionId(item.id) in node.sourceSectionIds ||
                item.microSourceSectionIds().let { it.isNotEmpty() && node.sourceSectionIds.containsAll(it) }
        }.map(MemoryWorkItem::id)
        if (sourceIds.isEmpty()) return null
        MicroNodeDraft(
            key = keys.getValue(node.id),
            kind = node.kind.name,
            text = node.text,
            sourceIds = sourceIds,
            salience = node.salience,
            confidence = node.confidence,
            metadata = node.metadata - DERIVED_METADATA_KEYS,
        )
    }
    val links = edgesToAdd.map { edge ->
        fun endpoint(id: MemoryNodeId): String? = keys[id] ?: id.value.takeIf(visibleNodes::contains)
        MicroLinkDraft(
            from = endpoint(edge.from) ?: return null,
            to = endpoint(edge.to) ?: return null,
            relation = edge.relation.name,
            weight = edge.weight,
            metadata = edge.metadata - DERIVED_METADATA_KEYS,
        )
    }
    return PROPOSAL_JSON.encodeToString(MicroAgentProposal.serializer(), MicroAgentProposal(sections, nodes, links))
}

/** Compact, with defaults: a label states every field the decoder reads. */
private val PROPOSAL_JSON = Json { encodeDefaults = true }

private fun MemoryWorkPacket.renderMicroAgentPrompt(role: MemoryMicroAgentRole): String = buildString {
    appendLine("You are a local Haive memory micro-agent.")
    appendLine("ROLE: ${role.name}")
    appendLine("STAGE: ${stage.name}")
    appendLine("PACKET: $packetKey")
    appendLine(instruction)
    appendLine()
    appendLine("INPUT ITEMS")
    items.forEach(::appendMicroWorkItem)
    if (neighborhood.isNotEmpty()) {
        appendLine()
        appendLine("BOUNDED MEMORY NEIGHBORHOOD")
        neighborhood.forEach(::appendMicroWorkItem)
    }
    appendLine()
    appendLine("Return exactly one JSON object and nothing else.")
    appendLine("{\"sections\":[{\"text\":\"...\",\"sourceIds\":[\"id\"],\"metadata\":{}}],")
    appendLine(" \"nodes\":[{\"key\":\"local-key\",\"kind\":\"Context|NounTag|VerbTag|Phrase|Summary|Category\",\"text\":\"...\",\"sourceIds\":[\"id\"],\"salience\":0.5,\"confidence\":1.0,\"metadata\":{}}],")
    appendLine(" \"links\":[{\"from\":\"local-key-or-visible-node-id\",\"to\":\"local-key-or-visible-node-id\",\"relation\":\"${MODEL_WRITABLE_RELATIONS.joinToString("|") { it.name }}\",\"weight\":1.0,\"metadata\":{}}]}")
    appendLine("Use only IDs visible in this packet as sourceIds or link endpoints, except local keys created in this same response.")
    appendLine("Never infer contradiction, truth, falsity, or conflict resolution. Memory clerks only organize supplied material.")
}

private fun StringBuilder.appendMicroWorkItem(item: MemoryWorkItem) {
    append("- ID=").append(item.id).append(" KIND=").append(item.kind).appendLine()
    if (item.metadata.isNotEmpty()) {
        append("  META=")
        append(item.metadata.entries.joinToString(" | ") { (key, value) -> "$key=$value" })
        appendLine()
    }
    appendLine(item.text)
}

private fun MemoryWorkItem.microSourceEpisodeIds(): List<MemoryEpisodeId> = metadata["sourceEpisodeIds"]
    .orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::MemoryEpisodeId)

private fun MemoryWorkItem.microSourceSectionIds(): List<MemorySectionId> = metadata["sourceSectionIds"]
    .orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::MemorySectionId)

private val PROPOSAL_KEYS = setOf("sections", "nodes", "links")

/** `DO_NOT_CONDENSE` alone, or as the whole answer before any JSON, is a decline. */
private fun String.isCondensationDecline(): Boolean {
    val marker = indexOf("DO_NOT_CONDENSE")
    return marker >= 0 && indexOf('{').let { it < 0 || it > marker }
}

private fun String.extractMicroAgentJson(): String {
    val cleaned = trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    require(start >= 0 && end > start) { "Memory micro-agent did not return JSON" }
    return cleaned.substring(start, end + 1)
}

private fun String.microSafeIdPart(): String = buildString {
    this@microSafeIdPart.forEach { char ->
        when {
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            char == '-' || char == '_' -> append(char)
            else -> append('-')
        }
    }
}.trim('-').take(48).ifBlank { "node" }

@OptIn(ExperimentalTime::class)
private fun microAgentNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
