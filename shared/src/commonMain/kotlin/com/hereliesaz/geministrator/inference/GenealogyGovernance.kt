package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Structural ancestry node for one concrete inference invocation.
 *
 * This graph describes derivation only. It does not encode truth, correctness, contradiction,
 * confidence, or preference between claims.
 */
data class InferenceGenealogyNode(
    val invocationId: String,
    val upstreamInvocationIds: Set<String> = emptySet(),
    val upstreamTaskRunIds: Set<TaskRunId> = emptySet(),
    val upstreamArtifactIds: Set<ArtifactId> = emptySet(),
    val memoryAddresses: Set<String> = emptySet(),
    val toolEvidenceIds: Set<String> = emptySet(),
    val promptFingerprint: String? = null,
    val configurationFingerprint: String? = null,
) {
    init {
        require(invocationId.isNotBlank()) { "Genealogy node invocationId must not be blank" }
        require(invocationId !in upstreamInvocationIds) {
            "An inference invocation cannot list itself as a direct ancestor"
        }
        require(upstreamInvocationIds.none(String::isBlank)) { "Upstream invocation IDs must not be blank" }
        require(memoryAddresses.none(String::isBlank)) { "Memory addresses must not be blank" }
        require(toolEvidenceIds.none(String::isBlank)) { "Tool evidence IDs must not be blank" }
    }

    fun directEvidenceKeys(): Set<GenealogyEvidenceKey> = buildSet {
        upstreamTaskRunIds.forEach { add(GenealogyEvidenceKey(GenealogyEvidenceKind.TaskRun, it.value)) }
        upstreamArtifactIds.forEach { add(GenealogyEvidenceKey(GenealogyEvidenceKind.Artifact, it.value)) }
        memoryAddresses.forEach { add(GenealogyEvidenceKey(GenealogyEvidenceKind.Memory, it)) }
        toolEvidenceIds.forEach { add(GenealogyEvidenceKey(GenealogyEvidenceKind.ToolEvidence, it)) }
    }
}

enum class GenealogyEvidenceKind {
    TaskRun,
    Artifact,
    Memory,
    ToolEvidence,
}

data class GenealogyEvidenceKey(
    val kind: GenealogyEvidenceKind,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "Genealogy evidence ID must not be blank" }
    }
}

interface InferenceGenealogyGraph {
    suspend fun register(node: InferenceGenealogyNode)
    suspend fun get(invocationId: String): InferenceGenealogyNode?
    suspend fun all(): List<InferenceGenealogyNode>
}

class InMemoryInferenceGenealogyGraph : InferenceGenealogyGraph {
    private val mutex = Mutex()
    private val nodes = linkedMapOf<String, InferenceGenealogyNode>()

    override suspend fun register(node: InferenceGenealogyNode) = mutex.withLock {
        val existing = nodes[node.invocationId]
        require(existing == null || existing == node) {
            "Inference genealogy node ${node.invocationId} is already registered with different ancestry"
        }
        nodes[node.invocationId] = node
    }

    override suspend fun get(invocationId: String): InferenceGenealogyNode? =
        mutex.withLock { nodes[invocationId] }

    override suspend fun all(): List<InferenceGenealogyNode> = mutex.withLock { nodes.values.toList() }
}

enum class GenealogyGovernanceFindingKind {
    MissingGenealogy,
    MissingEvidence,
    CommonAncestry,
    CircularDerivation,
    InsufficientIndependence,
    UnsupportedConsensus,
}

data class GenealogyGovernanceFinding(
    val kind: GenealogyGovernanceFindingKind,
    val invocationIds: Set<String>,
    val sharedEvidence: Set<GenealogyEvidenceKey> = emptySet(),
    val sharedAncestorInvocationIds: Set<String> = emptySet(),
    val message: String,
)

data class GenealogyConsensusGroup(
    val groupId: String,
    val invocationIds: Set<String>,
) {
    init {
        require(groupId.isNotBlank()) { "Consensus group ID must not be blank" }
        require(invocationIds.size >= 2) { "Consensus groups require at least two invocations" }
        require(invocationIds.none(String::isBlank)) { "Consensus invocation IDs must not be blank" }
    }
}

data class GenealogyGovernancePolicy(
    val requireEvidence: Boolean = false,
    val minimumIndependentMembersForConsensus: Int = 2,
) {
    init {
        require(minimumIndependentMembersForConsensus >= 2) {
            "minimumIndependentMembersForConsensus must be at least two"
        }
    }
}

data class GenealogyGovernanceRequest(
    val invocationIds: Set<String>,
    val consensusGroups: List<GenealogyConsensusGroup> = emptyList(),
    val policy: GenealogyGovernancePolicy = GenealogyGovernancePolicy(),
) {
    init {
        require(invocationIds.isNotEmpty()) { "Genealogy governance requires at least one invocation" }
        require(invocationIds.none(String::isBlank)) { "Governed invocation IDs must not be blank" }
        consensusGroups.forEach { group ->
            require(group.invocationIds.all(invocationIds::contains)) {
                "Consensus group ${group.groupId} contains invocation outside the governance request"
            }
        }
    }
}

data class GenealogyIndependenceAssessment(
    val leftInvocationId: String,
    val rightInvocationId: String,
    val independent: Boolean,
    val sharedEvidence: Set<GenealogyEvidenceKey>,
    val sharedAncestorInvocationIds: Set<String>,
)

data class GenealogyGovernanceReport(
    val invocationIds: Set<String>,
    val findings: List<GenealogyGovernanceFinding>,
    val pairwiseIndependence: List<GenealogyIndependenceAssessment>,
) {
    val structurallyClear: Boolean get() = findings.isEmpty()
}

fun interface GenealogyGovernanceEvaluator {
    suspend fun evaluate(request: GenealogyGovernanceRequest): GenealogyGovernanceReport
}

/**
 * Deterministic governance over ancestry structure.
 *
 * The evaluator can say that two outputs share sources or derive from one another. It cannot say
 * whether an output is true, false, better, contradictory, or worthy of belief.
 */
class DefaultGenealogyGovernanceEvaluator(
    private val graph: InferenceGenealogyGraph,
) : GenealogyGovernanceEvaluator {
    override suspend fun evaluate(request: GenealogyGovernanceRequest): GenealogyGovernanceReport {
        val allNodes = graph.all().associateBy(InferenceGenealogyNode::invocationId)
        val requestedNodes = request.invocationIds.mapNotNull(allNodes::get)
        val findings = mutableListOf<GenealogyGovernanceFinding>()

        val missing = request.invocationIds - requestedNodes.mapTo(linkedSetOf(), InferenceGenealogyNode::invocationId)
        missing.sorted().forEach { invocationId ->
            findings += GenealogyGovernanceFinding(
                kind = GenealogyGovernanceFindingKind.MissingGenealogy,
                invocationIds = setOf(invocationId),
                message = "Invocation $invocationId has no registered genealogy node.",
            )
        }

        if (request.policy.requireEvidence) {
            requestedNodes.filter { node ->
                node.directEvidenceKeys().isEmpty() && node.upstreamInvocationIds.isEmpty()
            }.forEach { node ->
                findings += GenealogyGovernanceFinding(
                    kind = GenealogyGovernanceFindingKind.MissingEvidence,
                    invocationIds = setOf(node.invocationId),
                    message = "Invocation ${node.invocationId} has no registered evidence or upstream invocation ancestry.",
                )
            }
        }

        val ancestryCache = mutableMapOf<String, Set<String>>()
        fun ancestors(invocationId: String, activePath: Set<String> = emptySet()): Set<String> {
            ancestryCache[invocationId]?.let { return it }
            val node = allNodes[invocationId] ?: return emptySet()
            if (invocationId in activePath) return emptySet()
            val result = buildSet {
                node.upstreamInvocationIds.forEach { parent ->
                    add(parent)
                    addAll(ancestors(parent, activePath + invocationId))
                }
            }
            ancestryCache[invocationId] = result
            return result
        }

        fun reachableCycle(startInvocationId: String): Set<String> {
            val path = mutableListOf<String>()
            val pathIndex = mutableMapOf<String, Int>()
            val fullyVisited = mutableSetOf<String>()

            fun visit(invocationId: String): Set<String>? {
                pathIndex[invocationId]?.let { index ->
                    return path.subList(index, path.size).toSet() + invocationId
                }
                if (!fullyVisited.add(invocationId)) return null
                pathIndex[invocationId] = path.size
                path += invocationId
                val node = allNodes[invocationId]
                if (node != null) {
                    for (parent in node.upstreamInvocationIds) {
                        val cycle = visit(parent)
                        if (cycle != null) return cycle
                    }
                }
                path.removeAt(path.lastIndex)
                pathIndex.remove(invocationId)
                return null
            }

            return visit(startInvocationId).orEmpty()
        }

        val evidenceClosureCache = mutableMapOf<String, Set<GenealogyEvidenceKey>>()
        fun evidenceClosure(invocationId: String, activePath: Set<String> = emptySet()): Set<GenealogyEvidenceKey> {
            evidenceClosureCache[invocationId]?.let { return it }
            val node = allNodes[invocationId] ?: return emptySet()
            if (invocationId in activePath) return node.directEvidenceKeys()
            val evidence = buildSet {
                addAll(node.directEvidenceKeys())
                node.upstreamInvocationIds.forEach { parent ->
                    addAll(evidenceClosure(parent, activePath + invocationId))
                }
            }
            evidenceClosureCache[invocationId] = evidence
            return evidence
        }

        val reportedCycles = mutableSetOf<Set<String>>()
        requestedNodes.forEach { node ->
            val cycle = reachableCycle(node.invocationId)
            if (cycle.isNotEmpty() && reportedCycles.add(cycle)) {
                findings += GenealogyGovernanceFinding(
                    kind = GenealogyGovernanceFindingKind.CircularDerivation,
                    invocationIds = cycle,
                    sharedAncestorInvocationIds = cycle,
                    message = "Inference genealogy contains a circular derivation path involving ${cycle.sorted().joinToString()}.",
                )
            }
        }

        val pairwise = mutableListOf<GenealogyIndependenceAssessment>()
        for (leftIndex in requestedNodes.indices) {
            for (rightIndex in leftIndex + 1 until requestedNodes.size) {
                val left = requestedNodes[leftIndex]
                val right = requestedNodes[rightIndex]
                val sharedEvidence = evidenceClosure(left.invocationId) intersect evidenceClosure(right.invocationId)
                val leftAncestors = ancestors(left.invocationId)
                val rightAncestors = ancestors(right.invocationId)
                val sharedAncestors = buildSet {
                    addAll(leftAncestors intersect rightAncestors)
                    if (left.invocationId in rightAncestors) add(left.invocationId)
                    if (right.invocationId in leftAncestors) add(right.invocationId)
                }
                val assessment = GenealogyIndependenceAssessment(
                    leftInvocationId = left.invocationId,
                    rightInvocationId = right.invocationId,
                    independent = sharedEvidence.isEmpty() && sharedAncestors.isEmpty(),
                    sharedEvidence = sharedEvidence,
                    sharedAncestorInvocationIds = sharedAncestors,
                )
                pairwise += assessment
                if (!assessment.independent) {
                    findings += GenealogyGovernanceFinding(
                        kind = GenealogyGovernanceFindingKind.CommonAncestry,
                        invocationIds = setOf(left.invocationId, right.invocationId),
                        sharedEvidence = sharedEvidence,
                        sharedAncestorInvocationIds = sharedAncestors,
                        message = "Invocations ${left.invocationId} and ${right.invocationId} are not structurally independent.",
                    )
                }
            }
        }

        request.consensusGroups.forEach { group ->
            val groupNodes = group.invocationIds.mapNotNull(allNodes::get)
            val independentMembers = maximumPairwiseIndependentCount(
                invocationIds = groupNodes.map(InferenceGenealogyNode::invocationId),
                pairwise = pairwise,
            )
            if (independentMembers < request.policy.minimumIndependentMembersForConsensus) {
                findings += GenealogyGovernanceFinding(
                    kind = GenealogyGovernanceFindingKind.UnsupportedConsensus,
                    invocationIds = group.invocationIds,
                    message = "Consensus group ${group.groupId} has only $independentMembers structurally independent member(s).",
                )
                findings += GenealogyGovernanceFinding(
                    kind = GenealogyGovernanceFindingKind.InsufficientIndependence,
                    invocationIds = group.invocationIds,
                    message = "Consensus group ${group.groupId} does not meet the required independent-member threshold of ${request.policy.minimumIndependentMembersForConsensus}.",
                )
            }
        }

        return GenealogyGovernanceReport(
            invocationIds = request.invocationIds,
            findings = findings.distinct(),
            pairwiseIndependence = pairwise,
        )
    }

    private fun maximumPairwiseIndependentCount(
        invocationIds: List<String>,
        pairwise: List<GenealogyIndependenceAssessment>,
    ): Int {
        if (invocationIds.isEmpty()) return 0
        val independenceByPair = pairwise.associateBy {
            normalizedPair(it.leftInvocationId, it.rightInvocationId)
        }
        var best = 0
        val selected = mutableListOf<String>()

        fun search(index: Int) {
            if (selected.size + (invocationIds.size - index) <= best) return
            if (index >= invocationIds.size) {
                best = maxOf(best, selected.size)
                return
            }

            val candidate = invocationIds[index]
            val compatible = selected.all { existing ->
                independenceByPair[normalizedPair(existing, candidate)]?.independent == true
            }
            if (compatible) {
                selected += candidate
                search(index + 1)
                selected.removeAt(selected.lastIndex)
            }
            search(index + 1)
        }

        search(0)
        return best
    }

    private fun normalizedPair(left: String, right: String): Pair<String, String> =
        if (left <= right) left to right else right to left
}

/**
 * Runtime coordinator used by every provider-backed inference path.
 *
 * Reports are advisory structural governance. They are not epistemic verdicts and never mutate
 * memory or silently rewrite worker output.
 */
class InferenceGenealogyGovernanceRuntime(
    val graph: InferenceGenealogyGraph = InMemoryInferenceGenealogyGraph(),
    evaluator: GenealogyGovernanceEvaluator? = null,
) {
    private val evaluator: GenealogyGovernanceEvaluator = evaluator ?: DefaultGenealogyGovernanceEvaluator(graph)
    private val mutex = Mutex()
    private val latestReportsByInvocation = linkedMapOf<String, GenealogyGovernanceReport>()

    suspend fun registerInvocation(genealogy: InferenceGenealogy): GenealogyGovernanceReport {
        graph.register(genealogy.toNode())
        return evaluate(
            GenealogyGovernanceRequest(
                invocationIds = setOf(genealogy.invocationId),
            ),
        )
    }

    suspend fun evaluate(request: GenealogyGovernanceRequest): GenealogyGovernanceReport {
        val report = evaluator.evaluate(request)
        mutex.withLock {
            report.invocationIds.forEach { invocationId ->
                latestReportsByInvocation[invocationId] = report
            }
        }
        return report
    }

    suspend fun latestReport(invocationId: String): GenealogyGovernanceReport? =
        mutex.withLock { latestReportsByInvocation[invocationId] }
}

fun InferenceGenealogy.toNode(): InferenceGenealogyNode = InferenceGenealogyNode(
    invocationId = invocationId,
    upstreamInvocationIds = upstreamInvocationIds,
    upstreamTaskRunIds = upstreamTaskRunIds,
    upstreamArtifactIds = upstreamArtifactIds,
    memoryAddresses = memoryAddresses,
    toolEvidenceIds = toolEvidenceIds,
    promptFingerprint = promptFingerprint,
    configurationFingerprint = configurationFingerprint,
)
