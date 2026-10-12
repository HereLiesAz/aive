package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.vector.TextEmbedder
import com.hereliesaz.geministrator.vector.normalized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The installed MiniLM sentence embedder (the AssociationLinker's model) as a recall
 * [TextEmbedder], with [fallback] (the built-in space) behind it.
 *
 * The model's width is only known after its first answer, and the model can be missing or fail
 * (not installed yet, removed, runtime unavailable). The current space is reported through [id] and
 * [dimension] (together, `space`): a failure switches to the fallback, and the model is tried again
 * after [retryAfter], so one installed mid-session is picked up. Callers compare the space before
 * and after an embed call and rebuild what they cached whenever it changes, so vectors from the two
 * spaces never meet.
 */
class ModelTextEmbedder(
    private val modelId: String,
    private val embedModel: suspend (List<String>) -> List<List<Float>>,
    private val fallback: TextEmbedder = MemoryVectorEmbedder(),
    private val retryAfter: Duration = 5.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : TextEmbedder {
    private var modelDimension: Int? = null
    private var failedAt: TimeMark? = null
    private val failed: Boolean get() = failedAt != null

    override val id: String get() = if (failed) fallback.id else "model:$modelId"
    override val version: Int get() = if (failed) fallback.version else 1
    override val dimension: Int get() = if (failed) fallback.dimension else modelDimension ?: fallback.dimension

    /** Whether the model (rather than the fallback) answers. Unknown before the first call. */
    val usingModel: Boolean get() = !failed && modelDimension != null

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        if (failedAt?.let { it.elapsedNow() >= retryAfter } == true) failedAt = null
        if (!failed) {
            val vectors = try {
                // The runtime rejects blank input; a blank text embeds to the zero vector.
                val nonBlank = texts.indices.filter { texts[it].isNotBlank() }
                val answered = if (nonBlank.isEmpty()) emptyList() else embedModel(nonBlank.map { texts[it] })
                require(answered.size == nonBlank.size) { "$modelId returned ${answered.size} embeddings for ${nonBlank.size} inputs" }
                val width = answered.firstOrNull()?.size ?: modelDimension
                require(answered.all { it.size == width }) { "$modelId returned embeddings of differing widths" }
                if (width != null && modelDimension == null) modelDimension = width
                require(width == null || width == modelDimension) { "$modelId changed width from $modelDimension to $width" }
                val byIndex = nonBlank.zip(answered).toMap()
                texts.indices.map { i -> byIndex[i]?.let { normalized(it.toFloatArray()) } ?: FloatArray(modelDimension ?: fallback.dimension) }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                failedAt = timeSource.markNow()
                null
            }
            if (vectors != null && !failed && modelDimension != null) return vectors
            // Only blank texts so far: no width to report yet, so answer in the fallback space shape.
            if (vectors != null && !failed) return texts.map { FloatArray(fallback.dimension) }
        }
        return fallback.embed(texts)
    }
}
