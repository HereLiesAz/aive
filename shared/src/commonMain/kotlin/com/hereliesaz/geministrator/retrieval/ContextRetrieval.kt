package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.vector.BuiltInTextEmbedder
import com.hereliesaz.geministrator.vector.FlatVectorIndex
import com.hereliesaz.geministrator.vector.TextEmbedder
import com.hereliesaz.geministrator.vector.space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A piece of retrievable text: a slice of a repository file or of an upstream artifact.
 * [id] is stable for the same source and position; [label] is what the agent sees as its heading.
 */
data class TextChunk(
    val id: String,
    val label: String,
    val text: String,
)

data class RetrievedChunk(
    val chunk: TextChunk,
    val similarity: Float,
)

/** Where chunks for a request come from. A source that fails contributes nothing. */
fun interface ContextChunkSource {
    suspend fun chunks(request: AgentTaskRequest): List<TextChunk>
}

/** Retrieves the chunks most relevant to a request, for the Context Packer to weigh. */
fun interface ContextRetriever {
    suspend fun retrieve(request: AgentTaskRequest): List<RetrievedChunk>
}

/**
 * Chunks from every [sources] ranked by similarity to the request's objective, acceptance
 * criteria and role instructions; the best [maxChunks] at or above [minSimilarity].
 *
 * Chunk vectors are cached by text (bounded by [maxCachedVectors]) and dropped when the embedder's
 * space changes, so a repository read on every request is embedded once.
 */
class SimilarityContextRetriever(
    private val sources: List<ContextChunkSource>,
    private val embedder: TextEmbedder = BuiltInTextEmbedder(),
    private val maxChunks: Int = 8,
    private val minSimilarity: Float = 0.2f,
    private val maxCachedVectors: Int = 4_096,
) : ContextRetriever {
    // Least recently used first. Guarded by [cacheMutex]: retrieve() suspends while embedding.
    private val vectors = LinkedHashMap<String, FloatArray>()
    private var vectorSpace: String? = null
    private val cacheMutex = Mutex()

    init {
        require(maxChunks > 0)
        require(maxCachedVectors > 0)
    }

    override suspend fun retrieve(request: AgentTaskRequest): List<RetrievedChunk> {
        val query = request.retrievalQuery()
        if (query.isBlank()) return emptyList()
        val chunks = sources.flatMap { source ->
            try {
                source.chunks(request)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptyList()
            }
        }.filter { it.text.isNotBlank() }.distinctBy { it.id }
        if (chunks.isEmpty()) return emptyList()

        val queryVector = embedder.embed(listOf(query)).single()
        val texts = chunks.map { it.text }.distinct()
        val requestVectors = cacheMutex.withLock {
            if (vectorSpace != embedder.space) {
                vectors.clear()
                vectorSpace = embedder.space
            }
            val missing = texts.filter { it !in vectors }
            if (missing.isNotEmpty()) {
                embedder.embed(missing).forEachIndexed { i, vector -> vectors[missing[i]] = vector }
            }
            // Touch this request's vectors so they are the most recently used, then evict from the
            // least recently used end; the cap never drops below this request's own size.
            val current = buildMap { texts.forEach { text -> vectors.remove(text)?.let { put(text, it) } } }
            vectors.putAll(current)
            while (vectors.size > maxOf(maxCachedVectors, texts.size)) vectors.remove(vectors.keys.first())
            current
        }
        // The space can change while embedding (a model falling back): then this request goes without.
        if (vectorSpace != embedder.space || queryVector.size != embedder.dimension) return emptyList()

        val index = FlatVectorIndex(embedder.dimension)
        val byId = chunks.associateBy { it.id }
        chunks.forEach { chunk -> requestVectors[chunk.text]?.let { index.upsert(chunk.id, it) } }
        return index.search(queryVector, maxChunks)
            .filter { it.score >= minSimilarity }
            .map { RetrievedChunk(byId.getValue(it.id), it.score) }
    }
}

/** What a request is about, in words, for similarity search. */
internal fun AgentTaskRequest.retrievalQuery(): String =
    (listOf(objective) + acceptanceCriteria.map { it.description }).filter(String::isNotBlank).joinToString("\n")

/** The text content of the request's upstream artifacts, chunked. */
object ArtifactChunkSource : ContextChunkSource {
    override suspend fun chunks(request: AgentTaskRequest): List<TextChunk> =
        request.contextArtifacts.flatMap { artifact ->
            val text = artifact.textContent ?: return@flatMap emptyList()
            chunkText(text).mapIndexed { i, part ->
                TextChunk(
                    id = "artifact:${artifact.id.value}#$i",
                    label = "Artifact: ${artifact.label}" + if (i > 0) " (part ${i + 1})" else "",
                    text = part,
                )
            }
        }
}

/**
 * Splits [text] into chunks of at most [maxChars]: whole paragraphs (blank-line separated) packed
 * together, and a paragraph longer than that split at line boundaries, then hard-cut if one line
 * is still too long. Code splits at blank lines too, which keeps functions mostly whole.
 */
fun chunkText(text: String, maxChars: Int = 1_200): List<String> {
    require(maxChars > 0)
    // Each piece remembers whether it starts a paragraph, so packing keeps blank lines where they were.
    val pieces = text.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotEmpty).flatMap { paragraph ->
        if (paragraph.length <= maxChars) {
            listOf(paragraph to true)
        } else {
            paragraph.lines()
                .flatMap { line -> if (line.length <= maxChars) listOf(line) else line.chunked(maxChars) }
                .mapIndexed { i, line -> line to (i == 0) }
        }
    }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    for ((piece, startsParagraph) in pieces) {
        val separator = if (startsParagraph) "\n\n" else "\n"
        if (current.isNotEmpty() && current.length + separator.length + piece.length > maxChars) {
            chunks += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append(separator)
        current.append(piece)
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks
}
