package com.hereliesaz.geministrator.memory

/**
 * The engine's deterministic contrast step and the variant register it fills.
 *
 * For each memory of a finished episode, nearby memories of the same kind that share a content word
 * are compared with [MemoryContrast]. Each contrast adds, append-only:
 *
 * - a **divergence marker** (`Diverges`) between the two memories: structural and advisory, never a
 *   verdict. Recall returns marked partners together ([MemoryRecallHit.conflicts]).
 * - the **variant register** for the frame: a `Frame` node, one `Variant` node per filler
 *   (`VariantOf` -> frame), and an `Attests` edge from each memory to the variant it states. The
 *   `Attests` metadata carries age (record time; event time when the text states a date), context
 *   (session, project, run, task, role), subject and source episodes. A variant's occurrence count is
 *   the number of distinct source episodes over its attestations.
 *
 * It runs on a workflow's lineage bank ([LineageMemoryStore]: the workflow's own records plus its
 * ancestors'), and everything it adds is written to that workflow's own bank. When a merge marries
 * lineages, [mergeMutationFor] compares the memories of the married sides and marks their contrasts in
 * the merging workflow's bank: noticing, not judging.
 *
 * Nothing here ranks a filler as correct, removes, edits or hides a memory. Writing the register
 * twice is a no-op (all ids are derived from content). Every contrasting memory is kept side by side,
 * and each attestation records both when it was recorded and the date its text states.
 */
internal object MemoryVariantRegister {
    /** Kinds that carry statements; tags and categories are single cues and are not compared. */
    val CLAIM_KINDS: Set<MemoryNodeKind> = setOf(MemoryNodeKind.Context, MemoryNodeKind.Phrase, MemoryNodeKind.Summary)

    private const val MAX_CANDIDATES_PER_NODE = 48

    fun mutationFor(snapshot: MemorySnapshot, episodeId: MemoryEpisodeId, nowEpochMillis: Long): MemoryStoreMutation {
        val claims = claimsOf(snapshot)
        return contrastMutation(snapshot, claims, claims.filter { episodeId in it.sourceEpisodeIds }, nowEpochMillis) { _, _ -> true }
    }

    /**
     * The contrast step for a merge: [sides] are the memory ids each married lineage holds (a memory
     * of a common ancestor is on every side). Every claim is compared with claims of the other sides
     * only, and each contrast is marked and registered, like any other.
     */
    fun mergeMutationFor(snapshot: MemorySnapshot, sides: List<Set<MemoryNodeId>>, nowEpochMillis: Long): MemoryStoreMutation {
        if (sides.size < 2) return MemoryStoreMutation()
        val claims = claimsOf(snapshot)
        fun apart(a: MemoryNodeId, b: MemoryNodeId) =
            sides.any { a in it && b !in it } && sides.any { b in it && a !in it }
        val sideMembers = sides.flatten().toSet()
        return contrastMutation(snapshot, claims, claims.filter { it.id in sideMembers }, nowEpochMillis) { a, b -> apart(a.id, b.id) }
    }

    private fun claimsOf(snapshot: MemorySnapshot): List<MemoryNode> {
        val superseded = snapshot.edges.filter { it.relation == MemoryRelationKind.Supersedes }.mapTo(hashSetOf()) { it.to }
        return snapshot.nodes.filter { it.kind in CLAIM_KINDS && it.id !in superseded }
    }

    private fun contrastMutation(
        snapshot: MemorySnapshot,
        claims: List<MemoryNode>,
        own: List<MemoryNode>,
        nowEpochMillis: Long,
        comparable: (MemoryNode, MemoryNode) -> Boolean,
    ): MemoryStoreMutation {
        if (own.isEmpty()) return MemoryStoreMutation()

        val episodesById = snapshot.episodes.associateBy(MemoryEpisode::id)
        val existingNodes = snapshot.nodes.mapTo(hashSetOf()) { it.id }
        val existingEdges = snapshot.edges.mapTo(hashSetOf()) { it.id }
        val termsOf = HashMap<MemoryNodeId, Set<String>>()
        fun terms(node: MemoryNode) = termsOf.getOrPut(node.id) {
            MemoryContrast.tokens(node.text).filter { it.content }.mapTo(hashSetOf()) { it.norm }
        }
        val postings = HashMap<String, MutableList<MemoryNode>>()
        claims.forEach { node -> terms(node).forEach { postings.getOrPut(it) { mutableListOf() } += node } }

        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        val newNodeIds = hashSetOf<MemoryNodeId>()
        val newEdgeIds = hashSetOf<MemoryEdgeId>()
        fun addNode(node: MemoryNode) { if (node.id !in existingNodes && newNodeIds.add(node.id)) nodes += node }
        fun addEdge(edge: MemoryEdge) { if (edge.id !in existingEdges && newEdgeIds.add(edge.id)) edges += edge }
        val compared = hashSetOf<String>()

        own.forEach { node ->
            val overlap = HashMap<MemoryNodeId, Int>()
            val byId = HashMap<MemoryNodeId, MemoryNode>()
            terms(node).forEach { term ->
                postings[term].orEmpty().forEach { other ->
                    if (other.id != node.id && other.kind == node.kind && comparable(node, other)) {
                        overlap[other.id] = (overlap[other.id] ?: 0) + 1
                        byId[other.id] = other
                    }
                }
            }
            overlap.entries.sortedWith(compareByDescending<Map.Entry<MemoryNodeId, Int>> { it.value }.thenBy { it.key.value })
                .take(MAX_CANDIDATES_PER_NODE)
                .forEach { (otherId, _) ->
                    val other = byId.getValue(otherId)
                    val (left, right) = if (node.id.value <= other.id.value) node to other else other to node
                    if (!compared.add("${left.id.value}\u0000${right.id.value}")) return@forEach
                    val contrast = MemoryContrast.between(left.text, right.text) ?: return@forEach
                    val frameId = MemoryNodeId("frame:${stableHash(contrast.frameKey)}")
                    addNode(
                        MemoryNode(
                            id = frameId,
                            kind = MemoryNodeKind.Frame,
                            text = contrast.frameText,
                            createdAtEpochMillis = nowEpochMillis,
                            metadata = mapOf(FRAME_KEY to contrast.frameKey, SUBJECT to contrast.subject),
                        ),
                    )
                    addEdge(
                        MemoryEdge(
                            id = MemoryEdgeId("diverges:${left.id.value}|${right.id.value}"),
                            from = left.id,
                            to = right.id,
                            relation = MemoryRelationKind.Diverges,
                            createdAtEpochMillis = nowEpochMillis,
                            metadata = mapOf(
                                "basis" to "contrast",
                                FRAME_KEY to contrast.frameKey,
                                "frameId" to frameId.value,
                                "fromFiller" to contrast.leftFiller,
                                "toFiller" to contrast.rightFiller,
                            ),
                        ),
                    )
                    listOf(left to (contrast.leftFiller to contrast.leftFillerText), right to (contrast.rightFiller to contrast.rightFillerText))
                        .forEach { (memory, filler) ->
                            val (key, text) = filler
                            val variantId = MemoryNodeId("variant:${stableHash(contrast.frameKey + "\u0000" + key)}")
                            addNode(
                                MemoryNode(
                                    id = variantId,
                                    kind = MemoryNodeKind.Variant,
                                    text = text.ifBlank { EMPTY_FILLER },
                                    createdAtEpochMillis = nowEpochMillis,
                                    metadata = mapOf(FRAME_KEY to contrast.frameKey, FILLER_KEY to key),
                                ),
                            )
                            addEdge(
                                MemoryEdge(
                                    id = MemoryEdgeId("variantof:${variantId.value}"),
                                    from = variantId,
                                    to = frameId,
                                    relation = MemoryRelationKind.VariantOf,
                                    createdAtEpochMillis = nowEpochMillis,
                                ),
                            )
                            addEdge(
                                MemoryEdge(
                                    id = MemoryEdgeId("attests:${memory.id.value}|${variantId.value}"),
                                    from = memory.id,
                                    to = variantId,
                                    relation = MemoryRelationKind.Attests,
                                    createdAtEpochMillis = nowEpochMillis,
                                    metadata = attestation(memory, contrast.subject, episodesById),
                                ),
                            )
                        }
                }
        }
        return MemoryStoreMutation(nodesToAdd = nodes, edgesToAdd = edges)
    }

    private fun attestation(
        memory: MemoryNode,
        subject: String,
        episodesById: Map<MemoryEpisodeId, MemoryEpisode>,
    ): Map<String, String> {
        val episodes = memory.sourceEpisodeIds.mapNotNull(episodesById::get)
        fun joined(values: List<String?>) = values.filterNotNull().filter(String::isNotBlank).distinct().joinToString(",")
        return buildMap {
            put(RECORDED_AT, memory.createdAtEpochMillis.toString())
            put(EPISODE_AT, joined(episodes.map { it.createdAtEpochMillis.toString() }))
            STATED_DATE.find(memory.text)?.let { put(EVENT_AT, it.value) }
            put(SUBJECT, subject)
            put("sourceEpisodeIds", joined(memory.sourceEpisodeIds.map { it.value }))
            put("sessions", joined(episodes.map { it.sourceSessionId }))
            put("projectId", joined(episodes.map { it.projectId }))
            put("workflowRunId", joined(episodes.map { it.workflowRunId }))
            put("taskRunId", joined(episodes.map { it.taskRunId }))
            put("roleId", joined(episodes.map { it.roleId }))
        }.filterValues(String::isNotEmpty)
    }

    const val FRAME_KEY = "frameKey"
    const val FILLER_KEY = "filler"
    const val SUBJECT = "subject"
    const val RECORDED_AT = "recordedAt"
    const val EPISODE_AT = "episodeAt"
    const val EVENT_AT = "eventAt"
    const val EMPTY_FILLER = "(nothing in this slot)"

    private val STATED_DATE = Regex("\\b(19|20)\\d\\d-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])\\b")

    /** FNV-1a 64, hex: identical on every platform. */
    fun stableHash(text: String): String {
        var hash = -0x340d631b7bdddcdbL
        text.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash.toULong().toString(16)
    }
}

/** One filler seen for a frame, with everything recorded about where and when it was seen. */
data class MemoryVariant(
    val id: MemoryNodeId,
    val filler: String,
    /** Distinct source episodes over all attestations. */
    val occurrences: Int,
    val attestations: List<MemoryAttestation>,
)

data class MemoryAttestation(
    val memoryId: MemoryNodeId,
    val recordedAtEpochMillis: Long?,
    /** A date the memory's text states, when it states one (ISO yyyy-mm-dd). */
    val statedEventDate: String?,
    val subject: String,
    val sourceEpisodeIds: List<String>,
    /** Session, project, run, task and role the memory came from. */
    val context: Map<String, String>,
)

/** A frame and every filler encountered for it, in first-recorded order. No variant is ranked as correct. */
data class MemoryFrameVariants(
    val frameId: MemoryNodeId,
    val frame: String,
    val variants: List<MemoryVariant>,
)

/** The variant register as stored: read-only, derived from `Frame`/`Variant` nodes and their edges. */
fun MemorySnapshot.variantRegister(): List<MemoryFrameVariants> {
    val byId = nodes.associateBy(MemoryNode::id)
    val variantsOf = edges.filter { it.relation == MemoryRelationKind.VariantOf }.groupBy({ it.to }, { it.from })
    val attests = edges.filter { it.relation == MemoryRelationKind.Attests }.groupBy { it.to }
    return nodes.filter { it.kind == MemoryNodeKind.Frame }.map { frame ->
        val variants = variantsOf[frame.id].orEmpty().mapNotNull(byId::get).map { variant ->
            val attestations = attests[variant.id].orEmpty().map { edge ->
                val meta = edge.metadata
                MemoryAttestation(
                    memoryId = edge.from,
                    recordedAtEpochMillis = meta[MemoryVariantRegister.RECORDED_AT]?.toLongOrNull(),
                    statedEventDate = meta[MemoryVariantRegister.EVENT_AT],
                    subject = meta[MemoryVariantRegister.SUBJECT].orEmpty(),
                    sourceEpisodeIds = meta["sourceEpisodeIds"].orEmpty().split(',').filter(String::isNotBlank),
                    context = meta.filterKeys { it in CONTEXT_KEYS },
                )
            }
            MemoryVariant(
                id = variant.id,
                filler = variant.text,
                occurrences = attestations.flatMap { it.sourceEpisodeIds }.distinct().size,
                attestations = attestations,
            )
        }
        MemoryFrameVariants(frame.id, frame.text, variants)
    }
}

private val CONTEXT_KEYS = setOf("sessions", "projectId", "workflowRunId", "taskRunId", "roleId")

/**
 * Deterministic coverage: true when [derived] contains every sentence of [source] (case and spacing
 * folded), which includes an identical repeat. Only this may justify `Supersedes`.
 */
internal fun memoryCovers(derived: String, source: String): Boolean {
    fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', '?', ' ')
    val derivedSentences = memorySentences(derived).map(::norm).toSet()
    val sourceSentences = memorySentences(source).map(::norm).filter(String::isNotEmpty)
    if (norm(derived) == norm(source)) return true
    return sourceSentences.isNotEmpty() && sourceSentences.all { it in derivedSentences }
}
