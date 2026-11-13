package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.orchestration.PlanReviewRequest
import com.hereliesaz.geministrator.orchestration.PlanReviewer
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.orchestration.parsePlanVerdict
import com.hereliesaz.geministrator.orchestration.planReviewPrompt
import com.hereliesaz.geministrator.orchestration.reportLaunchProgress
import kotlinx.coroutines.CancellationException

/**
 * Plan reviewer backed by the keeper's linked cloud LLMs (orchestration/PlanReview.kt).
 *
 * A reviewer left to the app tries each linked LLM in turn, as the planner does, and the next one when
 * a provider fails or gives no verdict. A reviewer pinned to a provider (its role's own choice) never
 * switches silently: if that provider cannot answer, the review fails and says so. It sends the plan, which
 * the keeper's planner just wrote, only to LLMs the keeper has linked.
 */
class TextGenerationPlanReviewer(private val candidates: List<PlanningApiEntry>) : PlanReviewer {
    override suspend fun review(request: PlanReviewRequest): PlanVerdict {
        val pinned = request.pinnedProviderId
        val order = if (pinned == null) candidates else candidates.filter { it.id == pinned }
        if (order.isEmpty()) {
            error(if (pinned == null) "No linked LLM can review the plan" else "${request.reviewerName} is pinned to $pinned, which is not linked")
        }
        val prompt = planReviewPrompt(request)
        var last: Throwable? = null
        for ((index, candidate) in order.withIndex()) {
            try {
                val reply = candidate.api.generate(prompt).text
                return parsePlanVerdict(reply) ?: error("${candidate.name} gave no verdict")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                last = failure
                // A pinned reviewer is the keeper's choice: it does not move to another provider by itself.
                if (pinned != null) throw failure
                order.getOrNull(index + 1)?.let { next ->
                    reportLaunchProgress("${candidate.name} could not review the plan; asking ${next.name}…")
                }
            }
        }
        throw last ?: error("No linked LLM can review the plan")
    }
}

/** The plan reviewer for the linked LLMs; null when none is linked. */
fun configuredPlanReviewer(linked: Map<String, String>): PlanReviewer? =
    configuredPlanningApis(linked).takeIf { it.isNotEmpty() }?.let(::TextGenerationPlanReviewer)

/**
 * The queen's model, in two modes. Automatic (no pin, the default): the linked Sources in the routing order, keyless ones
 * included, moving to the next when one fails, and saying so. Pinned: only [pinnedProviderId] (it never switches silently). Her answer is validated by `acceptRuling`; this only carries text.
 */
class TextGenerationQueenModel(
    private val candidates: List<PlanningApiEntry>,
    private val pinnedProviderId: String? = null,
) : com.hereliesaz.geministrator.queen.QueenModel {
    override suspend fun answer(prompt: String): String {
        val order = if (pinnedProviderId == null) candidates else candidates.filter { it.id == pinnedProviderId }
        if (order.isEmpty()) error("No linked LLM can answer for the queen")
        // Inside a dispute the call keeps each Source's request limit (waiting its turn, counted as her wait) and tells the Story of a move.
        val call = com.hereliesaz.geministrator.queen.currentQueenCallContext()
        var last: Throwable? = null
        for ((index, candidate) in order.withIndex()) {
            try {
                if (call != null && !call.awaitTurn(candidate.id)) error("${candidate.name} has no turn within the queen's wait")
                return candidate.api.generate(prompt).text
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                last = failure
                // Pinned: the keeper's choice, it never moves (it waits its turn, and fails when that is too far off). Automatic: the next Source in the routing order, said in the Story.
                if (pinnedProviderId != null) throw failure
                order.getOrNull(index + 1)?.let { next ->
                    reportLaunchProgress("${candidate.name} could not answer for the queen; asking ${next.name}…")
                }
            }
        }
        throw last ?: error("No linked LLM can answer for the queen")
    }
}

/** The queen's model for the linked LLMs; null when none is linked. */
fun configuredQueenModel(linked: Map<String, String>, pinnedProviderId: String? = null): com.hereliesaz.geministrator.queen.QueenModel? =
    configuredPlanningApis(linked).takeIf { it.isNotEmpty() }?.let { TextGenerationQueenModel(it, pinnedProviderId) }
