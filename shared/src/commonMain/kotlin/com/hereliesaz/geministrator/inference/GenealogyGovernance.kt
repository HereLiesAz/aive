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
            if (invocationId in activePath) return setOf(invocationId)
            val nextPath = activePath + invocationId
            val result = buildSet {
                node.upstreamInvocationIds.forEach { parent ->
                    add(parent)
                    addAll(ancestors(parent, nextPath))
                }
            }
            if (invocationId !in result) ancestryCache[invocationId] = result
            return result
        }

        requestedNodes.forEach { node ->
            val nodeAncestors = ancestors(node.invocationId)
            if (node.invocationId in nodeAncestors) {
                findings += GenealogyGovernanceFinding(
                    kind = GenealogyGovernanceFindingKind.CircularDerivation,
                    invocationIds = setOf(node.invocationId) + nodeAncestors,
                    sharedAncestorInvocationIds = nodeAncestors,
                    message = "Invocation ${node.invocationId} participates in circular derivation ancestry.",
                )
            }
        }

        val pairwise = mutableListOf<GenealogyIndependenceAssessment>()
        for (leftIndex in requestedNodes.indices) {
            for (rightIndex in leftIndex + 1 until requestedNodes.size) {
                val left = requestedNodes[leftIndex]
                val right = requestedNodes[rightIndex]
                val sharedEvidence = left.directEvidenceKeys() intersect right.directEvidenceKeys()
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
            val independentMembers = maximumPairwiseIndependentCount(groupNodes.map(InferenceGenealogyNode::invocationId), pairwise)
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
        if (invocationIds.size == 1) return 1
        val independenceByPair = pairwise.associateBy {
            normalizedPair(it.leftInvocationId, it.rightInvocationId)
        }
        var best = 1
        val totalMasks = 1 shl invocationIds.size
        for (mask in 1 until totalMasks) {
            val selected = invocationIds.indices.filter { index -> mask and (1 shl index) != 0 }
            if (selected.size <= best) continue
            val allIndependent = selected.allIndexedPairs { leftIndex, rightIndex ->
                independenceByPair[normalizedPair(invocationIds[leftIndex], invocationIds[rightIndex])]?.independent == true
            }
            if (allIndependent) best = selected.size
        }
        return best
    }

    private fun normalizedPair(left: String, right: String): Pair<String, String> =
        if (left <= right) left to right else right to left

    private inline fun List<Int>.allIndexedPairs(predicate: (Int, Int) -> Boolean): Boolean {
        for (leftIndex in indices) {
            for (rightIndex in leftIndex + 1 until size) {
                if (!predicate(this[leftIndex], this[rightIndex])) return false
            }
        }
        return true
    }
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
