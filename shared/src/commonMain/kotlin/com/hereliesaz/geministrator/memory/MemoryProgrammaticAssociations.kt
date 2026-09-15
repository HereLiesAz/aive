package com.hereliesaz.geministrator.memory

/**
 * Creates neutral memory associations that can be proven from bookkeeping data alone.
 *
 * No model is involved. These relationships are deliberately limited to exact identifiers,
 * orchestration scope, provenance, sequence, and derived temporal buckets. Fuzzy semantic
 * similarity remains the job of the semantic association model.
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

    val existingIds = edges.mapTo(hashSetOf()) { it.id }
    val episodeById = episodes.associateBy(MemoryEpisode::id)
    val anchors = episodes.mapNotNull { episode ->
        activeNodes.anchorForEpisode(episode.id)?.let { episode.id to it }
    }.toMap()
    val candidates = linkedMapOf<MemoryEdgeId, MemoryEdge>()

    fun add(left: MemoryNode?, right: MemoryNode?, basis: String, weight: Float, detail: String? = null) {
        if (left == null || right == null || left.id == right.id || candidates.size >= limit) return
        if (!left.isProjectCompatibleWith(right, episodeById)) return

        val first: MemoryNode
        val second: MemoryNode
        if (left.id.value <= right.id.value) {
            first = left
            second = right
        } else {
            first = right
            second = left
        }
        val edgeId = MemoryEdgeId("programmatic:$basis:${first.id.value}|${second.id.value}")
        if (edgeId in existingIds || edgeId in candidates) return
        candidates[edgeId] = MemoryEdge(
            id = edgeId,
            from = first.id,
            to = second.id,
            relation = MemoryRelationKind.AssociatedWith,
            weight = weight,
            createdAtEpochMillis = nowEpochMillis,
            metadata = buildMap {
                put("deterministic", "true")
                put("basis", basis)
                detail?.takeIf(String::isNotBlank)?.let { put("detail", it.take(512)) }
            },
        )
    }

    // Exact semantic cue identity is a bookkeeping fact, not a semantic inference. Cue identity is
    // still project-scoped so generic tags such as "test" cannot bridge unrelated project graphs.
    activeNodes
        .filter { it.kind == MemoryNodeKind.NounTag || it.kind == MemoryNodeKind.VerbTag || it.kind == MemoryNodeKind.Category }
        .flatMap { node ->
            node.projectScopeKeys(episodeById).map { scope ->
                "$scope|${node.kind.name}:${node.text.normalizedMemoryKey()}" to node
            }
        }
        .groupBy({ it.first }, { it.second })
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

    // Exact machine-readable artifacts and identifiers: URLs, paths, commit hashes, PR/issue refs,
    // code spans, task paths, and exception/error type names. Project scoping prevents generic local
    // identifiers such as src/... or #12 from joining unrelated repositories.
    val identifierGroups = linkedMapOf<String, MutableList<MemoryNode>>()
    activeNodes.forEach { node ->
        node.text.exactMemoryIdentifiers().forEach { identifier ->
            node.projectScopeKeys(episodeById).forEach { scope ->
                identifierGroups.getOrPut("$scope|$identifier") { mutableListOf() } += node
            }
        }
    }
    identifierGroups.entries
        .filter { it.value.size > 1 }
        .sortedBy { it.key }
        .forEach { (scopedIdentifier, group) ->
            val identifier = scopedIdentifier.substringAfter('|')
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

    associateEpisodeGroups(
        basis = "scope:session",
        weight = 0.98f,
        groups = episodes.groupBy { it.scopedKey(it.sourceSessionId) },
    )
    associateEpisodeGroups(
        basis = "scope:task-run",
        weight = 0.96f,
        groups = episodes.mapNotNull { episode ->
            episode.taskRunId?.let { value -> episode.scopedKey(value) to episode }
        }.groupBy({ it.first }, { it.second }),
    )
    associateEpisodeGroups(
        basis = "scope:workflow-run",
        weight = 0.92f,
        groups = episodes.mapNotNull { episode ->
            episode.workflowRunId?.let { value -> episode.scopedKey(value) to episode }
        }.groupBy({ it.first }, { it.second }),
    )
    associateEpisodeGroups(
        basis = "scope:task-definition",
        weight = 0.84f,
        groups = episodes.mapNotNull { episode ->
            episode.taskDefinitionId?.let { value -> episode.scopedKey(value) to episode }
        }.groupBy({ it.first }, { it.second }),
    )
    associateEpisodeGroups(
        basis = "scope:workflow-definition",
        weight = 0.80f,
        groups = episodes.mapNotNull { episode ->
            episode.workflowDefinitionId?.let { value -> episode.scopedKey(value) to episode }
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

    // Neighboring episodes in the same project are mechanically adjacent in work history even when
    // their text shares no vocabulary.
    episodes.mapNotNull { episode -> episode.projectId?.let { it to episode } }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .forEach { (_, group) ->
            group.sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                .zipWithNext()
                .forEach { (leftEpisode, rightEpisode) ->
                    add(anchors[leftEpisode.id], anchors[rightEpisode.id], "sequence:adjacent-episode", 0.72f)
                }
        }

    // Active temporal buckets provide bounded recency associations without any model inference.
    // The temporal window is partitioned by project before linking, so coincidental clock proximity
    // cannot bridge unrelated project memory graphs.
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
                .groupBy { it.projectId ?: UNSCOPED_PROJECT }
                .entries
                .sortedBy { it.key }
                .forEach { (_, projectEpisodes) ->
                    projectEpisodes
                        .sortedWith(compareBy<MemoryEpisode> { it.createdAtEpochMillis }.thenBy { it.id.value })
                        .mapNotNull { anchors[it.id] }
                        .distinctBy(MemoryNode::id)
                        .zipWithNext()
                        .forEach { (left, right) ->
                            add(left, right, "temporal:${bucket.level.name}", weight, bucket.id)
                        }
                }
        }

    return candidates.values.take(limit)
}

private const val UNSCOPED_PROJECT = "<unscoped>"

private fun MemoryEpisode.scopedKey(value: String): String =
    "${projectId ?: UNSCOPED_PROJECT}|$value"

private fun MemoryNode.projectScopeKeys(
    episodesById: Map<MemoryEpisodeId, MemoryEpisode>,
): Set<String> {
    val projects = sourceEpisodeIds.mapNotNull { episodesById[it]?.projectId }.toSet()
    return if (projects.isEmpty()) setOf(UNSCOPED_PROJECT) else projects
}

private fun MemoryNode.isProjectCompatibleWith(
    other: MemoryNode,
    episodesById: Map<MemoryEpisodeId, MemoryEpisode>,
): Boolean {
    val leftProjects = sourceEpisodeIds.mapNotNull { episodesById[it]?.projectId }.toSet()
    val rightProjects = other.sourceEpisodeIds.mapNotNull { episodesById[it]?.projectId }.toSet()
    return leftProjects.isEmpty() || rightProjects.isEmpty() || leftProjects.any { it in rightProjects }
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

private fun String.normalizedMemoryKey(): String =
    trim().lowercase().replace(Regex("\\s+"), " ")

private fun String.exactMemoryIdentifiers(): Set<String> {
    val identifiers = linkedSetOf<String>()

    Regex("https?://[^\\s)\\]}>,]+", RegexOption.IGNORE_CASE)
        .findAll(this)
        .forEach { identifiers += "url:${it.value.trimEnd('.', ',', ';').lowercase()}" }

    Regex("\\b[0-9a-fA-F]{7,40}\\b")
        .findAll(this)
        .forEach { identifiers += "commit:${it.value.lowercase()}" }

    Regex("#\\d+\\b")
        .findAll(this)
        .forEach { identifiers += "review:${it.value}" }

    Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")
        .findAll(this)
        .map { it.value }
        .filter { '/' in it && it.length >= 5 }
        .forEach { identifiers += "path:${it.lowercase()}" }

    Regex("`([^`\\n]{2,120})`")
        .findAll(this)
        .mapNotNull { it.groups[1]?.value?.trim() }
        .filter(String::isNotEmpty)
        .forEach { identifiers += "code:${it.lowercase()}" }

    Regex("\\b[A-Za-z_][A-Za-z0-9_.]*(?:Exception|Error)\\b")
        .findAll(this)
        .forEach { identifiers += "error:${it.value.lowercase()}" }

    Regex("(?:^|\\s)(:[A-Za-z0-9_.:-]+)")
        .findAll(this)
        .mapNotNull { it.groups[1]?.value }
        .forEach { identifiers += "task:${it.lowercase()}" }

    return identifiers
}
