package com.hereliesaz.geministrator.memory

/**
 * Creates neutral memory associations that can be proven from bookkeeping data alone.
 *
 * No model is involved. These relationships are deliberately limited to exact identifiers,
 * orchestration scope, provenance, sequence, derived temporal buckets, and mechanical propagation
 * of associations shared by memories that were condensed together. Fuzzy semantic similarity
 * remains the job of the semantic association model.
 *
 * The MemoryStore itself defines the association universe. projectId is useful context, but is not
 * an isolation boundary: one orchestration may intentionally span multiple projects in the same
 * store, and those memories should remain able to associate with one another.
 */
class MemoryProgrammaticAssociator(
    private val store: MemoryStore,
    private val maxEdgesPerRefresh: Int = 256,
) {
    init {
        require(maxEdgesPerRefresh > 0)
    }

    suspend fun refresh(nowEpochMillis: Long): Int {
        while (true) {
            val snapshot = store.read()
            val edges = snapshot.programmaticAssociationCandidates(nowEpochMillis, maxEdgesPerRefresh)
            if (edges.isEmpty()) return 0
            if (store.commit(snapshot.revision, MemoryStoreMutation(edgesToAdd = edges))) {
                return edges.size
            }
        }
    }
}

internal fun MemorySnapshot.programmaticAssociationCandidates(
    nowEpochMillis: Long,
    limit: Int,
): List<MemoryEdge> {
    if (limit <= 0 || nodes.size < 2) return emptyList()

    val superseded = edges
        .asSequence()
        .filter { it.relation == MemoryRelationKind.Supersedes }
        .mapTo(hashSetOf()) { it.to }
    val activeNodes = nodes.filter { it.id !in superseded }
    if (activeNodes.size < 2) return emptyList()

    val nodesById = nodes.associateBy(MemoryNode::id)
    val activeNodeIds = activeNodes.mapTo(hashSetOf(), MemoryNode::id)
    val existingIds = edges.mapTo(hashSetOf()) { it.id }
    val episodeById = episodes.associateBy(MemoryEpisode::id)
    val anchors = episodes.mapNotNull { episode ->
        activeNodes.anchorForEpisode(episode.id)?.let { episode.id to it }
    }.toMap()
    val candidates = linkedMapOf<MemoryEdgeId, MemoryEdge>()

    fun add(
        left: MemoryNode?,
        right: MemoryNode?,
        basis: String,
        weight: Float,
        detail: String? = null,
        evidenceKey: String? = null,
        extraMetadata: Map<String, String> = emptyMap(),
    ) {
        if (left == null || right == null || left.id == right.id || candidates.size >= limit) return
        val first: MemoryNode
        val second: MemoryNode
        if (left.id.value <= right.id.value) {
            first = left
            second = right
        } else {
            first = right
            second = left
        }
        val evidenceSuffix = evidenceKey?.let { ":evidence:$it" }.orEmpty()
        val edgeId = MemoryEdgeId("programmatic:$basis$evidenceSuffix:${first.id.value}|${second.id.value}")
        if (edgeId in existingIds || edgeId in candidates) return
        candidates[edgeId] = MemoryEdge(
            id = edgeId,
            from = first.id,
            to = second.id,
            relation = MemoryRelationKind.AssociatedWith,
            weight = weight.coerceIn(0f, 1f),
            createdAtEpochMillis = nowEpochMillis,
            metadata = buildMap {
                put("deterministic", "true")
                put("basis", basis)
                detail?.takeIf(String::isNotBlank)?.let { put("detail", it.take(512)) }
                putAll(extraMetadata)
            },
        )
    }

    /*
     * Condensation loses specificity but shared associations become stronger. Do this first because
     * these edges preserve the most useful overlap of memories that have just become less granular.
     *
     * Each source contributes a separate append-only support edge. Parallel inherited support is
     * combined by GRIP using 1 - Π(1 - wi), so new independent support can strengthen the relation
     * later without mutating or double-counting an old aggregate edge. Correlated evidence families
     * (notably temporal rebucketing) are canonicalized before a source contribution is inherited.
     */
    val condensedSourcesByGeneralized = edges
        .asSequence()
        .filter { it.relation == MemoryRelationKind.CondensedFrom && it.from in activeNodeIds }
        .groupBy({ it.from }, { it.to })
        .mapValues { (_, sourceIds) -> sourceIds.distinct() }

    data class CondensationOverlap(
        val generalized: MemoryNode,
        val target: MemoryNode,
        val sourceId: MemoryNodeId,
        val sourceStrength: Float,
        val combinedStrength: Float,
        val supportCount: Int,
    )

    val overlapContributions = mutableListOf<CondensationOverlap>()
    condensedSourcesByGeneralized.forEach { (generalizedId, sourceIds) ->
        if (sourceIds.size < 2) return@forEach
        val generalized = nodesById[generalizedId] ?: return@forEach
        val sourceIdSet = sourceIds.toHashSet()
        val evidenceByTargetAndSource =
            linkedMapOf<MemoryNodeId, LinkedHashMap<MemoryNodeId, MutableList<MemoryEdge>>>()

        edges.asSequence()
            .filter { it.relation.isAssociativeEvidence() }
            .forEach { edge ->
                val sourceId = when {
                    edge.from in sourceIdSet -> edge.from
                    edge.to in sourceIdSet -> edge.to
                    else -> null
                } ?: return@forEach
                val targetId = if (edge.from == sourceId) edge.to else edge.from
                if (
                    targetId == generalizedId ||
                    targetId in sourceIdSet ||
                    targetId !in activeNodeIds
                ) {
                    return@forEach
                }
                evidenceByTargetAndSource
                    .getOrPut(targetId) { linkedMapOf() }
                    .getOrPut(sourceId) { mutableListOf() } += edge
            }

        evidenceByTargetAndSource.forEach { (targetId, bySource) ->
            if (bySource.size < 2) return@forEach
            val target = nodesById[targetId] ?: return@forEach
            val sourceStrengths = bySource.mapValues { (_, evidence) -> accumulateAssociationEvidence(evidence) }
            val combined = accumulateAssociationStrength(sourceStrengths.values)
            sourceStrengths.forEach { (sourceId, sourceStrength) ->
                overlapContributions += CondensationOverlap(
                    generalized = generalized,
                    target = target,
                    sourceId = sourceId,
                    sourceStrength = sourceStrength,
                    combinedStrength = combined,
                    supportCount = sourceStrengths.size,
                )
            }
        }
    }

    overlapContributions
        .sortedWith(
            compareByDescending<CondensationOverlap> { it.combinedStrength }
                .thenBy { it.generalized.id.value }
                .thenBy { it.target.id.value }
                .thenBy { it.sourceId.value },
        )
        .forEach { overlap ->
            add(
                left = overlap.generalized,
                right = overlap.target,
                basis = "condensation:overlap",
                weight = overlap.sourceStrength,
                detail = "shared by ${overlap.supportCount} condensed sources",
                evidenceKey = overlap.sourceId.value,
                extraMetadata = mapOf(
                    "supportSourceId" to overlap.sourceId.value,
                    "supportCount" to overlap.supportCount.toString(),
                    "combinedStrength" to overlap.combinedStrength.toString(),
                ),
            )
        }

    // Exact semantic cue identity is a bookkeeping fact, not a semantic inference.
    activeNodes
        .filter { it.kind == MemoryNodeKind.NounTag || it.kind == MemoryNodeKind.VerbTag || it.kind == MemoryNodeKind.Category }
        .groupBy { "${it.kind.name}:${it.text.normalizedMemoryKey()}" }
        .entries
        .sortedBy { it.key }
        .map { it.value }
        .filter { it.size > 1 }
        .forEach { group ->
            group.distinctBy(MemoryNode::id)
                .sortedWith(compareBy<MemoryNode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .zipWithNext()
                .forEach { (left, right) ->
                    add(left, right, "exact-cue", 1f, left.text.normalizedMemoryKey())
                }
        }

    // Exact machine-readable artifacts and identifiers. Project-relative identifiers are only exact
    // inside a single known project namespace; full URLs, commit hashes, and error types are global.
    val identifierGroups = linkedMapOf<String, MutableList<MemoryNode>>()
    activeNodes.forEach { node ->
        val projectNamespace = node.singleProjectNamespace(episodeById)
        node.text.exactMemoryIdentifiers().forEach { identifier ->
            val groupingKey = when (identifier.scope) {
                IdentifierScope.Global -> "global:${identifier.value}"
                IdentifierScope.Project -> projectNamespace?.let { "project:$it:${identifier.value}" }
            } ?: return@forEach
            identifierGroups.getOrPut(groupingKey) { mutableListOf() } += node
        }
    }
    identifierGroups.entries
        .filter { it.value.size > 1 }
        .sortedBy { it.key }
        .forEach { (identifier, group) ->
            group.distinctBy(MemoryNode::id)
                .sortedWith(compareBy<MemoryNode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .zipWithNext()
                .forEach { (left, right) -> add(left, right, "exact-identifier", 0.98f, identifier) }
        }

    // Direct shared evidence provenance.
    activeNodes
        .flatMap { node -> node.sourceSectionIds.map { sectionId -> sectionId to node } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key.value }
        .map { it.value }
        .filter { it.size > 1 }
        .forEach { group ->
            group.distinctBy(MemoryNode::id)
                .sortedWith(compareBy<MemoryNode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .zipWithNext()
                .forEach { (left, right) -> add(left, right, "shared-provenance", 1f) }
        }

    fun associateEpisodeGroups(
        basis: String,
        weight: Float,
        groups: Map<String, List<MemoryEpisode>>,
    ) {
        groups.entries.sortedBy { it.key }.forEach { (_, group) ->
            group.sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .mapNotNull { anchors[it.id] }
                .distinctBy(MemoryNode::id)
                .zipWithNext()
                .forEach { (left, right) -> add(left, right, basis, weight) }
        }
    }

    // These run/session identifiers are intentionally NOT project-qualified. If one orchestration
    // spans two projects, shared run/session identity is exactly the association we want to keep.
    associateEpisodeGroups(
        basis = "scope:session",
        weight = 0.98f,
        groups = episodes.groupBy { it.sourceSessionId },
    )
    associateEpisodeGroups(
        basis = "scope:task-run",
        weight = 0.96f,
        groups = episodes.mapNotNull { it.taskRunId?.let { value -> value to it } }.groupBy({ it.first }, { it.second }),
    )
    associateEpisodeGroups(
        basis = "scope:workflow-run",
        weight = 0.92f,
        groups = episodes.mapNotNull { it.workflowRunId?.let { value -> value to it } }.groupBy({ it.first }, { it.second }),
    )

    // Task definition IDs are only exact inside a workflow definition and project namespace.
    associateEpisodeGroups(
        basis = "scope:task-definition",
        weight = 0.84f,
        groups = episodes.mapNotNull { episode ->
            val projectId = episode.projectId
            val workflowDefinitionId = episode.workflowDefinitionId
            val taskDefinitionId = episode.taskDefinitionId
            if (projectId != null && workflowDefinitionId != null && taskDefinitionId != null) {
                "$projectId|$workflowDefinitionId|$taskDefinitionId" to episode
            } else {
                null
            }
        }.groupBy({ it.first }, { it.second }),
    )
    associateEpisodeGroups(
        basis = "scope:workflow-definition",
        weight = 0.80f,
        groups = episodes.mapNotNull { episode ->
            val projectId = episode.projectId
            val workflowDefinitionId = episode.workflowDefinitionId
            if (projectId != null && workflowDefinitionId != null) "$projectId|$workflowDefinitionId" to episode else null
        }.groupBy({ it.first }, { it.second }),
    )
    associateEpisodeGroups(
        basis = "scope:project-role",
        weight = 0.76f,
        groups = episodes.mapNotNull { episode ->
            val projectId = episode.projectId
            val roleId = episode.roleId
            if (projectId != null && roleId != null) "$projectId|$roleId" to episode else null
        }.groupBy({ it.first }, { it.second }),
    )

    // Global chronological adjacency captures context switching, including an orchestration moving
    // from one project to another. This records sequence only, never causation.
    episodes
        .sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
        .zipWithNext()
        .forEach { (leftEpisode, rightEpisode) ->
            add(anchors[leftEpisode.id], anchors[rightEpisode.id], "sequence:adjacent-store", 0.68f)
        }

    // Same-project chronological adjacency is a slightly stronger bookkeeping signal.
    episodes.mapNotNull { episode -> episode.projectId?.let { it to episode } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .forEach { (_, group) ->
            group.sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .zipWithNext()
                .forEach { (leftEpisode, rightEpisode) ->
                    add(anchors[leftEpisode.id], anchors[rightEpisode.id], "sequence:adjacent-project", 0.72f)
                }
        }

    // Active temporal buckets provide bounded recency associations without any model inference.
    // They intentionally span project IDs within this store: temporal co-presence can be useful when
    // one orchestration is working across multiple projects at the same time. Different rollup
    // levels are representations of the same temporal evidence, not independent reinforcement.
    val temporal = MemoryTemporalIndex.build(episodes)
    temporal.buckets
        .sortedWith(compareBy<MemoryTemporalBucket> { it.level.ordinal }.thenBy { it.startEpochMillis })
        .forEach { bucket ->
            val weight = when (bucket.level) {
                MemoryTemporalLevel.FifteenMinutes -> 0.88f
                MemoryTemporalLevel.OneHour -> 0.80f
                MemoryTemporalLevel.SixHours -> 0.70f
                MemoryTemporalLevel.TwelveHours -> 0.64f
                MemoryTemporalLevel.Day -> 0.58f
                MemoryTemporalLevel.Week -> 0.50f
            }
            bucket.episodeIds
                .mapNotNull { episodeById[it] }
                .sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .mapNotNull { anchors[it.id] }
                .distinctBy(MemoryNode::id)
                .zipWithNext()
                .forEach { (left, right) ->
                    add(
                        left = left,
                        right = right,
                        basis = "temporal:${bucket.level.name}",
                        weight = weight,
                        detail = bucket.id,
                        extraMetadata = mapOf(
                            "evidenceFamily" to "temporal-co-bucket",
                            "evidencePolicy" to "latest",
                            "temporalLevel" to bucket.level.name,
                        ),
                    )
                }
        }

    return candidates.values.take(limit)
}

private fun List<MemoryNode>.anchorForEpisode(episodeId: MemoryEpisodeId): MemoryNode? =
    asSequence()
        .filter { episodeId in it.sourceEpisodeIds }
        .sortedWith(
            compareBy<MemoryNode> { node ->
                when (node.kind) {
                    MemoryNodeKind.Category -> 0
                    MemoryNodeKind.Summary -> 1
                    MemoryNodeKind.Phrase -> 2
                    MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag -> 3
                    MemoryNodeKind.Context -> 4
                }
            }.thenByDescending(MemoryNode::salience)
                .thenByDescending(MemoryNode::createdAtEpochMillis)
                .thenBy { it.id.value },
        )
        .firstOrNull()

private fun MemoryNode.singleProjectNamespace(
    episodesById: Map<MemoryEpisodeId, MemoryEpisode>,
): String? = sourceEpisodeIds
    .mapNotNull { episodesById[it]?.projectId }
    .distinct()
    .singleOrNull()

private fun String.normalizedMemoryKey(): String =
    trim().lowercase().replace(Regex("\\s+"), " ")

private enum class IdentifierScope {
    Global,
    Project,
}

private data class ExactMemoryIdentifier(
    val value: String,
    val scope: IdentifierScope,
)

private fun String.exactMemoryIdentifiers(): Set<ExactMemoryIdentifier> {
    val identifiers = linkedSetOf<ExactMemoryIdentifier>()

    // Preserve the complete URL string: URL paths can be case-sensitive.
    Regex("https?://[^\\s)\\]}>,]+", RegexOption.IGNORE_CASE)
        .findAll(this)
        .forEach {
            identifiers += ExactMemoryIdentifier(
                "url:${it.value.trimEnd('.', ',', ';')}",
                IdentifierScope.Global,
            )
        }

    // Hexadecimal commit identity is case-insensitive by definition.
    Regex("\\b[0-9a-fA-F]{7,40}\\b")
        .findAll(this)
        .forEach {
            identifiers += ExactMemoryIdentifier("commit:${it.value.lowercase()}", IdentifierScope.Global)
        }

    Regex("#\\d+\\b")
        .findAll(this)
        .forEach {
            identifiers += ExactMemoryIdentifier("review:${it.value}", IdentifierScope.Project)
        }

    Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")
        .findAll(this)
        .map { it.value }
        .filter { '/' in it && it.length >= 5 }
        .forEach {
            identifiers += ExactMemoryIdentifier("path:$it", IdentifierScope.Project)
        }

    Regex("`([^`\\n]{2,120})`")
        .findAll(this)
        .mapNotNull { it.groups[1]?.value?.trim() }
        .filter(String::isNotEmpty)
        .forEach {
            identifiers += ExactMemoryIdentifier("code:$it", IdentifierScope.Project)
        }

    Regex("\\b[A-Za-z_][A-Za-z0-9_.]*(?:Exception|Error)\\b")
        .findAll(this)
        .forEach {
            identifiers += ExactMemoryIdentifier("error:${it.value}", IdentifierScope.Global)
        }

    Regex("(?:^|\\s)(:[A-Za-z0-9_.:-]+)")
        .findAll(this)
        .mapNotNull { it.groups[1]?.value }
        .forEach {
            identifiers += ExactMemoryIdentifier("task:$it", IdentifierScope.Project)
        }

    return identifiers
}
