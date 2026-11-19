package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.TaskResource
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.resources.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's own written docs (README.md and docs/*.md), bundled read-only at build time (shared/build.gradle.kts,
 * `bundleAiveDocs`) so the queen and her creatures can look up how the app works. Read from the app's resources
 * only; nothing here touches the network or the keeper's files.
 */
object AiveDocsResources {
    private const val DIRECTORY = "files/aive-docs"

    /** Bundled path (`docs/Keepers-Room.md`) to text. Empty when the bundle is missing; never throws except for cancellation. */
    suspend fun load(): Map<String, String> = try {
        val index = Res.readBytes("$DIRECTORY/index.txt").decodeToString().lines().map(String::trim).filter(String::isNotEmpty)
        buildMap {
            for (name in index) {
                try {
                    put(name, Res.readBytes("$DIRECTORY/$name").decodeToString())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // One unreadable file is left out.
                }
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        emptyMap()
    }
}

/**
 * Chunks of the app's own docs, for a task the queen gave an [TaskResource.AiveDocs] resource and for no other:
 * a request without one gets nothing, so ordinary hives are untouched. Each file is split on Markdown headings into
 * chunks of at most [maxChars] characters labelled `docs/File.md > Heading`; the topics of the resource narrow them
 * to the sections that mention one. The text is the app's own, but goes through the same Context Packer as every
 * other chunk and is never marked as written by the keeper. A source that fails contributes nothing.
 */
class AiveDocsChunkSource(
    private val loader: suspend () -> Map<String, String> = { AiveDocsResources.load() },
    private val maxChars: Int = DEFAULT_MAX_CHARS,
) : ContextChunkSource {
    init {
        require(maxChars >= MIN_CHARS)
    }

    private val mutex = Mutex()
    private var cached: List<TextChunk>? = null

    /** Every chunk of every doc, built once. Empty (and not cached) when the docs cannot be read. */
    suspend fun allChunks(): List<TextChunk> = mutex.withLock {
        cached ?: try {
            splitDocs(loader(), maxChars).also { if (it.isNotEmpty()) cached = it }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun chunks(request: AgentTaskRequest): List<TextChunk> {
        val assigned = request.resources.filterIsInstance<TaskResource.AiveDocs>().firstOrNull() ?: return emptyList()
        return try {
            narrowedByTopics(allChunks(), assigned.topics)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 1_200
        private const val MIN_CHARS = 300
        private const val HEADING_PREFIX_MAX = 100
        private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")

        /** The chunks that mention one of [topics] in their label or text; all of them when there are no topics or none matches. */
        fun narrowedByTopics(chunks: List<TextChunk>, topics: List<String>): List<TextChunk> {
            val words = topics.map { it.trim().lowercase() }.filter(String::isNotEmpty)
            if (words.isEmpty()) return chunks
            val hits = chunks.filter { chunk ->
                val hay = (chunk.label + "\n" + chunk.text).lowercase()
                words.any { it in hay }
            }
            return hits.ifEmpty { chunks }
        }

        /**
         * Splits each file of [docs] on Markdown headings (a `#` inside a fenced code block is not one), then each section into
         * chunks of at most [maxChars] characters, the heading repeated at the head of each. Never throws.
         */
        fun splitDocs(docs: Map<String, String>, maxChars: Int = DEFAULT_MAX_CHARS): List<TextChunk> {
            if (maxChars < MIN_CHARS) return emptyList()
            val out = mutableListOf<TextChunk>()
            for ((path, raw) in docs.toSortedPairs()) {
                try {
                    var index = 0
                    for ((title, body) in sections(raw)) {
                        val prefix = title?.take(HEADING_PREFIX_MAX)?.let { "$it\n" }.orEmpty()
                        val label = if (title == null) path else "$path > ${title.take(HEADING_PREFIX_MAX)}"
                        for (part in chunkText(body, maxChars - prefix.length)) {
                            out += TextChunk(id = "aive-docs:$path#$index", label = label, text = prefix + part)
                            index++
                        }
                    }
                } catch (_: Exception) {
                    // A file that cannot be split contributes nothing.
                }
            }
            return out
        }

        private fun Map<String, String>.toSortedPairs(): List<Pair<String, String>> = entries.map { it.key to it.value }.sortedBy { it.first }

        /** A file's sections: the heading text (null before the first heading) and the body under it. */
        private fun sections(text: String): List<Pair<String?, String>> {
            val result = mutableListOf<Pair<String?, String>>()
            var title: String? = null
            val body = StringBuilder()
            var fenced = false
            fun flush() {
                if (body.isNotBlank()) result += title to body.toString().trim()
                body.clear()
            }
            for (line in text.lines()) {
                if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fenced = !fenced
                val match = if (fenced) null else heading.matchEntire(line)
                if (match != null) {
                    flush()
                    title = match.groupValues[2].trim()
                } else {
                    body.append(line).append('\n')
                }
            }
            flush()
            return result
        }
    }
}

/**
 * Narrows a repository source to the files a task's [TaskResource.RepositoryFiles] names: only chunks of those paths
 * (exact paths, `*` and `**` globs, or a bare file name) come through. The repository source picks files by how their
 * paths resemble the request, so the named paths are added to the request it sees. A task without that resource gets
 * the inner source exactly as before.
 */
class RepositoryFilesNarrowedSource(private val inner: ContextChunkSource) : ContextChunkSource {
    override suspend fun chunks(request: AgentTaskRequest): List<TextChunk> {
        val named = request.resources.filterIsInstance<TaskResource.RepositoryFiles>().firstOrNull()
            ?: return inner.chunks(request)
        val patterns = named.paths
        val seen = request.copy(objective = request.objective + "\n" + patterns.joinToString(" "))
        return inner.chunks(seen).filter { chunk ->
            val path = pathOf(chunk.label) ?: return@filter false
            patterns.any { pathMatches(it, path) }
        }
    }

    companion object {
        private const val PREFIX = "Repository file "
        private val PART = Regex(" \\(part \\d+\\)$")

        fun pathOf(label: String): String? =
            if (label.startsWith(PREFIX)) label.removePrefix(PREFIX).replace(PART, "").trim().takeIf(String::isNotEmpty) else null

        /** Whether [path] is named by [pattern]: equal, a glob match, or (for a pattern with no slash) the same file name. */
        fun pathMatches(pattern: String, path: String): Boolean {
            val wanted = pattern.trim().removePrefix("./").removePrefix("/")
            if (wanted.isEmpty()) return false
            if (wanted == path) return true
            if (!wanted.contains('/') && !wanted.contains('*') && path.substringAfterLast('/') == wanted) return true
            if (!wanted.contains('*') && !wanted.contains('?')) return false
            val regex = buildString {
                var i = 0
                while (i < wanted.length) {
                    val c = wanted[i]
                    when {
                        c == '*' && i + 1 < wanted.length && wanted[i + 1] == '*' -> { append(".*"); i++ }
                        c == '*' -> append("[^/]*")
                        c == '?' -> append("[^/]")
                        else -> append(Regex.escape(c.toString()))
                    }
                    i++
                }
            }
            return Regex(regex).matches(path)
        }
    }
}
