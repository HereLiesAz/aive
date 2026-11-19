package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.domain.TaskResource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.retrieval.AiveDocsChunkSource
import com.hereliesaz.geministrator.retrieval.SimilarityContextRetriever
import kotlinx.coroutines.CancellationException

/**
 * The queen reading the app's own docs while she plans, so she can decide what to assign each creature. For any
 * objective: the top [maxChunks] sections by similarity to it, and only those that clear [minSimilarity] (an objective
 * the docs say nothing about gets none, so a plain coding goal is not padded). Local: reads bundled resources, sends
 * nothing, asks the keeper nothing. Never throws except for cancellation.
 */
class AiveDocsResearch(
    source: AiveDocsChunkSource,
    private val maxChars: Int = 4_000,
    maxChunks: Int = 4,
    minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
) {
    private val retriever = SimilarityContextRetriever(
        listOf(source),
        maxChunks = maxChunks,
        minSimilarity = minSimilarity,
        // Nothing below the floor is kept anyway: the docs are offered only when they bear on the objective.
        floorChunks = 0,
    )

    /** One cleaned line per excerpt, `[the Aive's own docs | docs/File.md > Heading] text`, at most [maxChars] characters in all. */
    suspend fun excerpts(objective: String): List<String> {
        if (objective.isBlank()) return emptyList()
        return try {
            val request = AgentTaskRequest(
                taskRunId = TaskRunId("research-aive-docs"),
                objective = objective,
                roleInstructions = "",
                acceptanceCriteria = emptyList(),
                resources = listOf(TaskResource.AiveDocs()),
            )
            val lines = mutableListOf<String>()
            var left = maxChars
            for (hit in retriever.retrieve(request)) {
                if (left < MIN_ENTRY) break
                val line = ResearchText.clean("[the Aive's own docs | ${hit.chunk.label}] ${hit.chunk.text}", minOf(left, MAX_ENTRY))
                if (line.isEmpty()) continue
                lines += line
                left -= line.length
            }
            lines
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_MIN_SIMILARITY = 0.3f
        private const val MAX_ENTRY = 1_300
        private const val MIN_ENTRY = 200

        /** What the planner is told about those lines. */
        const val PLAN_INSTRUCTION: String =
            " Entries marked 'the Aive's own docs' are excerpts of this app's own documentation: use them as facts about how the app" +
                " works when choosing steps, and give a step that needs such facts an aive_docs resource."
    }
}
