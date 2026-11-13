package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.retrieval.ContextChunkSource
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The keeper's switch for the queen's research before planning (Connections › MCP servers). On by default:
 * research reads only what is already connected, and its one outside question still asks first. Persisted.
 */
class ResearchSettings(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
) {
    private val _enabled = MutableStateFlow(settings.getBoolean(storageKey, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        settings.putBoolean(storageKey, enabled)
        _enabled.value = enabled
    }

    companion object {
        const val DEFAULT_STORAGE_KEY = "aive.research.enabled.v1"
    }
}

/**
 * Adapts the retrieval chunk sources to [RepositoryDocsResearch]: the names of the linked repository's README and
 * documentation files, taken from the labels the sources already produce. Reads nothing the retrieval would not.
 */
class ChunkSourceDocNames(
    private val sources: List<ContextChunkSource>,
    private val repository: () -> RepositoryRef?,
) {
    suspend fun names(): List<String> {
        val linked = repository() ?: return emptyList()
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("research-repository-docs"),
            objective = "README documentation overview",
            roleInstructions = "",
            acceptanceCriteria = emptyList(),
            repository = linked,
        )
        val paths = LinkedHashSet<String>()
        for (source in sources) {
            val chunks = try {
                source.chunks(request)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                emptyList()
            }
            chunks.forEach { chunk -> docPath(chunk.label)?.let(paths::add) }
        }
        return paths.toList()
    }

    companion object {
        private const val PREFIX = "Repository file "
        private val PART = Regex(" \\(part \\d+\\)$")

        /** The file path inside a retrieval label when it names a README or documentation file; otherwise null. */
        fun docPath(label: String): String? {
            if (!label.startsWith(PREFIX)) return null
            val path = label.removePrefix(PREFIX).replace(PART, "").trim()
            val lower = path.lowercase()
            val isDoc = lower.substringAfterLast('/').startsWith("readme") || lower.startsWith("docs/") || lower.contains("/docs/")
            return path.takeIf { it.isNotEmpty() && isDoc }
        }
    }
}
