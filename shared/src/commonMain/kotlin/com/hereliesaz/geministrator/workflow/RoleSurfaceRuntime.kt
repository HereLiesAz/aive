package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.FlowchartLanguage
import com.hereliesaz.geministrator.domain.RoleSurface
import kotlinx.serialization.Serializable

@Serializable
data class AiveSurfaceTable(
    val columns: List<String>,
    val rows: List<Map<String, String?>> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class AiveFlowchartNode(
    val id: String,
    val label: String,
    val shape: String = "rectangle",
)

@Serializable
data class AiveFlowchartEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val style: String = "arrow",
)

@Serializable
data class AiveFlowchartGraph(
    val language: String = "mermaid",
    val direction: String = "TD",
    val nodes: List<AiveFlowchartNode>,
    val edges: List<AiveFlowchartEdge>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class AiveRoleSurfaceEnvelope(
    val alias: String,
    val kind: String,
    val writable: Boolean = false,
    val table: AiveSurfaceTable? = null,
    val flowchart: AiveFlowchartGraph? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
enum class AiveSurfaceMutationOperation {
    AppendRows,
    ReplaceRows,
    ExecuteSql,
}

@Serializable
data class AiveSurfaceMutation(
    val alias: String,
    val operation: AiveSurfaceMutationOperation,
    val rows: List<Map<String, String?>> = emptyList(),
    val sql: String? = null,
)

interface RoleSurfaceIntegration {
    fun supports(surface: RoleSurface): Boolean

    suspend fun resolve(surface: RoleSurface): AiveRoleSurfaceEnvelope

    suspend fun apply(
        surface: RoleSurface,
        mutation: AiveSurfaceMutation,
        mutationKey: String,
    )
}

class RoleSurfaceRuntimeRegistry(
    private val integrations: List<RoleSurfaceIntegration> = emptyList(),
) {
    suspend fun resolve(surfaces: List<RoleSurface>): List<AiveRoleSurfaceEnvelope> {
        require(surfaces.all { it.alias.isNotBlank() }) { "Role surface aliases must not be blank" }
        require(surfaces.map { it.alias }.distinct().size == surfaces.size) {
            "Role surface aliases must be unique"
        }
        return surfaces.map { surface ->
            when (surface) {
                is RoleSurface.Flowchart -> resolveFlowchart(surface)
                else -> integrationFor(surface).resolve(surface)
            }
        }
    }

    suspend fun apply(
        surfaces: List<RoleSurface>,
        mutations: List<AiveSurfaceMutation>,
        executionKey: String,
    ) {
        if (mutations.isEmpty()) return
        val byAlias = surfaces.associateBy(RoleSurface::alias)
        require(byAlias.size == surfaces.size) { "Role surface aliases must be unique" }

        mutations.forEachIndexed { index, mutation ->
            val surface = requireNotNull(byAlias[mutation.alias]) {
                "Script returned a mutation for unknown surface alias ${mutation.alias}"
            }
            require(surface.writableSurface()) {
                "Surface ${surface.alias} is read-only"
            }
            when (surface) {
                is RoleSurface.Flowchart -> error("Flowchart surfaces are read-only runtime inputs")
                else -> integrationFor(surface).apply(
                    surface = surface,
                    mutation = mutation,
                    mutationKey = "$executionKey:$index",
                )
            }
        }
    }

    private fun integrationFor(surface: RoleSurface): RoleSurfaceIntegration =
        integrations.firstOrNull { it.supports(surface) }
            ?: error("No runtime is configured for role surface ${surface.alias} (${surface::class.simpleName})")

    companion object {
        val Empty = RoleSurfaceRuntimeRegistry()
    }
}

fun RoleSurface.writableSurface(): Boolean = when (this) {
    is RoleSurface.Spreadsheet -> writable && when (source) {
        is com.hereliesaz.geministrator.domain.SpreadsheetSource.AppFile,
        is com.hereliesaz.geministrator.domain.SpreadsheetSource.DocumentUri,
        -> true
        is com.hereliesaz.geministrator.domain.SpreadsheetSource.Inline,
        is com.hereliesaz.geministrator.domain.SpreadsheetSource.Https,
        is com.hereliesaz.geministrator.domain.SpreadsheetSource.GoogleSheet,
        -> false
    }
    is RoleSurface.Sql -> writable &&
        source is com.hereliesaz.geministrator.domain.SqlDatabaseSource.AppDatabase
    is RoleSurface.Flowchart -> false
}

private fun resolveFlowchart(surface: RoleSurface.Flowchart): AiveRoleSurfaceEnvelope {
    require(surface.language == FlowchartLanguage.Mermaid) {
        "Unsupported flowchart language ${surface.language}"
    }
    return AiveRoleSurfaceEnvelope(
        alias = surface.alias,
        kind = "flowchart",
        writable = false,
        flowchart = MermaidFlowchartParser.parse(surface.source),
        metadata = mapOf("language" to "mermaid"),
    )
}

object MermaidFlowchartParser {
    private val headerPattern = Regex("""^(?:flowchart|graph)\s+([A-Za-z]{2})\s*$""", RegexOption.IGNORE_CASE)
    fun parse(source: String): AiveFlowchartGraph {
        val nodes = linkedMapOf<String, AiveFlowchartNode>()
        val edges = mutableListOf<AiveFlowchartEdge>()
        val warnings = mutableListOf<String>()
        var direction = "TD"
        var sawHeader = false

        source.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore("%%").trim()
            if (line.isBlank()) return@forEachIndexed

            val header = headerPattern.matchEntire(line)
            if (header != null) {
                direction = header.groupValues[1].uppercase()
                sawHeader = true
                return@forEachIndexed
            }

            parseEdge(line)?.let { parsed ->
                val from = parseNode(parsed.from)
                val to = parseNode(parsed.to)
                nodes.putIfAbsent(from.id, from)
                nodes.putIfAbsent(to.id, to)
                edges += AiveFlowchartEdge(
                    from = from.id,
                    to = to.id,
                    label = parsed.label,
                    style = parsed.style,
                )
                return@forEachIndexed
            }

            runCatching { parseNode(line) }
                .onSuccess { node -> nodes.putIfAbsent(node.id, node) }
                .onFailure { warnings += "Line ${index + 1}: unsupported Mermaid flowchart statement" }
        }

        if (!sawHeader) {
            warnings += "No flowchart/graph direction header was found; TD is assumed"
        }
        require(nodes.isNotEmpty()) { "Mermaid flowchart contains no nodes" }
        return AiveFlowchartGraph(
            direction = direction,
            nodes = nodes.values.toList(),
            edges = edges,
            warnings = warnings,
        )
    }

    private data class ParsedEdge(
        val from: String,
        val to: String,
        val label: String?,
        val style: String,
    )

    private fun parseEdge(line: String): ParsedEdge? {
        val labeled = Regex("""^(.+?)\s*-->\|([^|]+)\|\s*(.+)$""").matchEntire(line)
        if (labeled != null) {
            return ParsedEdge(
                from = labeled.groupValues[1],
                label = labeled.groupValues[2].trim().takeIf(String::isNotEmpty),
                to = labeled.groupValues[3],
                style = "arrow",
            )
        }
        val variants = listOf(
            Regex("""^(.+?)\s*-->\s*(.+)$""") to "arrow",
            Regex("""^(.+?)\s*-\.->\s*(.+)$""") to "dotted",
            Regex("""^(.+?)\s*---\s*(.+)$""") to "line",
        )
        variants.forEach { (pattern, style) ->
            val match = pattern.matchEntire(line) ?: return@forEach
            return ParsedEdge(match.groupValues[1], match.groupValues[2], null, style)
        }
        return null
    }

    private fun parseNode(raw: String): AiveFlowchartNode {
        val value = raw.trim()
        val patterns = listOf(
            Triple(Regex("""^([A-Za-z0-9_.:-]+)\(\[([^]]+)]\)$"""), "stadium", 2),
            Triple(Regex("""^([A-Za-z0-9_.:-]+)\[([^]]+)]$"""), "rectangle", 2),
            Triple(Regex("""^([A-Za-z0-9_.:-]+)\{([^}]+)}$"""), "decision", 2),
            Triple(Regex("""^([A-Za-z0-9_.:-]+)\(\((.+)\)\)$"""), "circle", 2),
            Triple(Regex("""^([A-Za-z0-9_.:-]+)\((.+)\)$"""), "rounded", 2),
        )
        patterns.forEach { (pattern, shape, labelGroup) ->
            val match = pattern.matchEntire(value) ?: return@forEach
            return AiveFlowchartNode(
                id = match.groupValues[1],
                label = match.groupValues[labelGroup].trim(),
                shape = shape,
            )
        }
        require(value.matches(Regex("""[A-Za-z0-9_.:-]+"""))) {
            "Invalid Mermaid flowchart node: $raw"
        }
        return AiveFlowchartNode(id = value, label = value)
    }
}
