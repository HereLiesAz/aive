package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.memory.procedural.FormedHabit
import com.hereliesaz.geministrator.memory.procedural.FormedSequence
import com.hereliesaz.geministrator.memory.procedural.ProcedureDefinition
import kotlin.math.ln
import kotlin.math.min

/*
 * The whole memory layer as one web of linked notes, the way Obsidian reads a folder of markdown (docs/Memory-Web.md).
 *
 * Pure and read-only: what the layer holds in, a [WebGraph] out. The mind map, the notes view and the Obsidian export all read this
 * one model, so they cannot disagree about what a memory is called or what it links to. Everything the layer keeps is in it: the
 * contexts, keywords and tags, phrases, summaries (and the summary trees and pair summaries under them), categories, generalizations
 * and what they were condensed from, the contrasts and deliberations, the episodes and sections everything came from, the keeper's
 * own memories, the habits and sequences the keeper's swarms formed, and the procedures. Nothing here writes to the bank; a keeper's
 * own memory goes in through [MemoryLayerController.bankKeeperMemory] like every other memory.
 */

/** What a node is, coarsely: the Memory Web colours by this, so each family of the layer reads at a glance. */
enum class WebGroup {
    /** Episodes and their sections: where memories came from. */
    Source,

    /** A retained context. */
    Context,

    /** Keywords: noun and verb tags. */
    Tag,

    Phrase,

    /** Summaries, with the summary tree's outlines and the pair summaries on links. */
    Summary,

    /** Category and subject tags. */
    Category,

    /** A memory condensed from others (a generalization, a compression). */
    Generalization,

    /** Frames, variants and deliberations: where memories disagree, and what was concluded. */
    Contrast,

    /** Something the keeper wrote in the Memory Web. */
    Keeper,

    /** Habits and sequences. */
    Habit,

    Procedure,
}

/** One thing in the web. [size] is its weight in the layer (0..1 and above): salience, recalls, uses. */
data class WebNode(
    val id: String,
    val group: WebGroup,
    /** The specific kind within the group ("NounTag", "Episode", "Habit"...). */
    val kind: String,
    /** Unique within the web, safe as a file name and as a `[[link]]`. */
    val title: String,
    val text: String,
    val size: Float,
    val createdAtEpochMillis: Long,
    /** Where it came from, one line each. */
    val sources: List<String>,
)

/** A connection; [weight] (0..1) is how strong it is. */
data class WebEdge(
    val from: String,
    val to: String,
    val relation: String,
    val weight: Float,
    /** What the two memories say together (the link's pair summary), when it has one. */
    val summary: String? = null,
)

data class WebGraph(val nodes: List<WebNode>, val edges: List<WebEdge>)

/** One link as a note shows it: the other note, how it relates, how strongly. */
data class WebLink(val target: String, val title: String, val relation: String, val weight: Float, val summary: String? = null)

/** A node as a note: its words, and what it links to and what links to it. */
data class WebNote(val node: WebNode, val outgoing: List<WebLink>, val backlinks: List<WebLink>)

/** One file of an exported vault, [path] relative to the vault's folder. */
data class WebFile(val path: String, val content: String)

/** Everything the web reads. Only [snapshot] is needed; the rest are the layer's other parts, where a bank has them. */
data class MemoryWebSource(
    val snapshot: MemorySnapshot,
    val habits: List<FormedHabit> = emptyList(),
    val sequences: List<FormedSequence> = emptyList(),
    val procedures: List<ProcedureDefinition> = emptyList(),
)

object MemoryWeb {
    private const val TITLE_MAX = 60
    private val titleIllegal = Regex("[\\\\/:*?\"<>|#^\\[\\]\\u0000-\\u001F]+")
    private const val KEEPER_SESSION_PREFIX = "keeper:"

    /** The whole layer as a graph. Node and edge order are stable for a given source. */
    fun graph(source: MemoryWebSource): WebGraph {
        val snapshot = source.snapshot
        val nodes = mutableListOf<RawNode>()
        val edges = mutableListOf<WebEdge>()
        val keeperEpisodes = snapshot.episodes.filter { it.sourceSessionId.startsWith(KEEPER_SESSION_PREFIX) }.mapTo(mutableSetOf()) { it.id }
        val recalls = snapshot.edges.filter { it.relation == MemoryRelationKind.Recalled }.groupingBy { it.to }.eachCount()
        val generalized = snapshot.edges.filter { it.relation == MemoryRelationKind.CondensedFrom }.mapTo(mutableSetOf()) { it.from }
        val pairSummaryByEdge = snapshot.nodes
            .filter { it.kind == MemoryNodeKind.PairSummary }
            .mapNotNull { node -> node.metadata[PAIR_OF]?.let { it to node.text } }
            .toMap()
        val episodeById = snapshot.episodes.associateBy { it.id }

        // Episodes and sections: where everything came from.
        snapshot.episodes.forEach { episode ->
            val keeper = episode.id in keeperEpisodes
            nodes += RawNode(
                id = "episode:${episode.id.value}",
                group = if (keeper) WebGroup.Keeper else WebGroup.Source,
                kind = "Episode",
                base = episode.userPrompt.ifBlank { episode.sourceSessionId },
                text = episode.userPrompt.ifBlank { episode.sourceSessionId },
                size = if (keeper) 0.6f else 0.4f,
                createdAt = episode.createdAtEpochMillis,
                sources = listOf(episode.sourceSessionId),
            )
        }
        snapshot.sections.forEach { section ->
            nodes += RawNode(
                id = "section:${section.id.value}",
                group = WebGroup.Source,
                kind = "Section",
                base = section.text,
                text = section.text,
                size = 0.25f,
                createdAt = episodeById[section.episodeId]?.createdAtEpochMillis ?: 0L,
                sources = listOf(section.episodeId.value),
            )
            edges += WebEdge("section:${section.id.value}", "episode:${section.episodeId.value}", "PartOf", 0.3f)
        }

        // Memories of every kind, none held back.
        val nodeIds = snapshot.nodes.mapTo(mutableSetOf()) { it.id }
        snapshot.nodes.forEach { node ->
            val keeper = node.sourceEpisodeIds.any { it in keeperEpisodes }
            nodes += RawNode(
                id = "node:${node.id.value}",
                group = when {
                    keeper -> WebGroup.Keeper
                    node.id in generalized -> WebGroup.Generalization
                    else -> groupOf(node.kind)
                },
                kind = node.kind.name,
                base = node.text,
                text = node.text,
                size = min(1.5f, node.salience + 0.12f * ln(1f + (recalls[node.id] ?: 0))),
                createdAt = node.createdAtEpochMillis,
                sources = node.sourceEpisodeIds.sortedBy { it.value }.map { id ->
                    episodeById[id]?.let { "${id.value} (${it.sourceSessionId})" } ?: id.value
                },
            )
            node.sourceEpisodeIds.forEach { id ->
                if (id in episodeById) edges += WebEdge("episode:${id.value}", "node:${node.id.value}", "SourceOf", 0.3f)
            }
        }
        // Recalls make a memory bigger, not another line: an access event is not a relation.
        snapshot.edges.filter { it.relation != MemoryRelationKind.Recalled && it.from in nodeIds && it.to in nodeIds }.forEach { edge ->
            edges += WebEdge(
                from = "node:${edge.from.value}",
                to = "node:${edge.to.value}",
                relation = edge.relation.name,
                weight = edge.weight,
                summary = pairSummaryByEdge[edge.id.value],
            )
        }
        // A pair summary belongs to the link it summarizes, and so to both ends of it.
        val edgeById = snapshot.edges.associateBy { it.id.value }
        snapshot.nodes.filter { it.kind == MemoryNodeKind.PairSummary }.forEach { pair ->
            val link = pair.metadata[PAIR_OF]?.let(edgeById::get) ?: return@forEach
            listOf(link.from, link.to).filter { it in nodeIds }.forEach { end ->
                edges += WebEdge("node:${pair.id.value}", "node:${end.value}", "PairOf", 0.5f)
            }
        }

        // Habits, sequences and procedures: what the swarms learned to do.
        val habitIds = mutableMapOf<String, MutableList<String>>()
        source.habits.forEach { habit ->
            val id = "habit:${habit.executorKind}/${habit.operation}"
            habitIds.getOrPut(habit.operation) { mutableListOf() } += id
            nodes += RawNode(
                id = id,
                group = WebGroup.Habit,
                kind = "Habit",
                base = "${habit.operation} (${habit.executorKind})",
                text = "${habit.operation} via ${habit.executorKind}: ${habit.uses} uses, ${habit.completedUses} completed, ${habit.failedUses} failed, " +
                    "${habit.verifiedUses} verified, ${habit.rejectedUses} turned back. Strength: ${habit.strength.name}.",
                size = habit.uses / (habit.uses + 5f),
                createdAt = habit.firstUsedAtEpochMillis,
                sources = emptyList(),
            )
        }
        source.sequences.forEach { sequence ->
            val id = "sequence:${sequence.operations.joinToString(">")}"
            nodes += RawNode(
                id = id,
                group = WebGroup.Habit,
                kind = "Sequence",
                base = sequence.operations.joinToString(" then "),
                text = "${sequence.operations.joinToString(" then ")}: ${sequence.runs} runs, ${sequence.cleanRuns} clean.",
                size = sequence.runs / (sequence.runs + 5f),
                createdAt = sequence.lastSeenAtEpochMillis,
                sources = emptyList(),
            )
            sequence.operations.distinct().forEach { operation ->
                habitIds[operation].orEmpty().forEach { habitId -> edges += WebEdge(id, habitId, "Step", 0.5f) }
            }
        }
        source.procedures.forEach { procedure ->
            val id = "procedure:${procedure.ref.workflowId}/${procedure.ref.name}/${procedure.ref.version}"
            nodes += RawNode(
                id = id,
                group = WebGroup.Procedure,
                kind = "Procedure",
                base = procedure.ref.name,
                text = procedure.summary,
                size = 0.5f,
                createdAt = procedure.createdAtEpochMillis,
                sources = procedure.sourceEpisodeIds.sortedBy { it.value }.map { it.value },
            )
            procedure.sourceEpisodeIds.filter { it in episodeById }.forEach { episode ->
                edges += WebEdge(id, "episode:${episode.value}", "Evidence", 0.4f)
            }
        }

        val ordered = nodes.sortedWith(compareBy({ it.createdAt }, { it.id }))
        val titles = titlesFor(ordered)
        val known = ordered.mapTo(mutableSetOf()) { it.id }
        return WebGraph(
            nodes = ordered.map {
                WebNode(it.id, it.group, it.kind, titles.getValue(it.id), it.text, it.size, it.createdAt, it.sources)
            },
            edges = edges.filter { it.from in known && it.to in known },
        )
    }

    /** The notes of [source]: one per node, with its links and backlinks. */
    fun notes(source: MemoryWebSource): List<WebNote> = notes(graph(source))

    fun notes(graph: WebGraph): List<WebNote> {
        val titles = graph.nodes.associate { it.id to it.title }
        val outgoing = graph.edges.groupBy { it.from }
        val incoming = graph.edges.groupBy { it.to }
        fun link(edge: WebEdge, other: String) = WebLink(other, titles.getValue(other), edge.relation, edge.weight, edge.summary)
        return graph.nodes.map { node ->
            WebNote(
                node,
                outgoing[node.id].orEmpty().sortedByDescending { it.weight }.map { link(it, it.to) },
                incoming[node.id].orEmpty().sortedByDescending { it.weight }.map { link(it, it.from) },
            )
        }
    }

    /** [note] as a markdown file Obsidian opens as it is: front matter, the text, then `[[links]]` and backlinks. */
    fun markdown(note: WebNote): String = buildString {
        val node = note.node
        appendLine("---")
        appendLine("id: ${node.id}")
        appendLine("group: ${node.group.name}")
        appendLine("kind: ${node.kind}")
        appendLine("size: ${(node.size * 100).toInt() / 100.0}")
        appendLine("created: ${node.createdAtEpochMillis}")
        if (node.sources.isNotEmpty()) {
            appendLine("sources:")
            node.sources.forEach { appendLine("  - \"${it.replace("\"", "'")}\"") }
        }
        appendLine("---")
        appendLine()
        appendLine("# ${node.title}")
        appendLine()
        // A `[[` in the memory's own words must not become a link in Obsidian: only the links below are links.
        appendLine(node.text.trim().replace("[[", "\\[\\[").replace("]]", "\\]\\]"))
        if (note.outgoing.isNotEmpty()) {
            appendLine()
            appendLine("## Links")
            note.outgoing.forEach { appendLine(linkLine(it)) }
        }
        if (note.backlinks.isNotEmpty()) {
            appendLine()
            appendLine("## Backlinks")
            note.backlinks.forEach { appendLine(linkLine(it)) }
        }
    }

    /** Every note as a file, in a folder per group (`Summary/Title.md`); a `[[Title]]` link finds it wherever it lives. */
    fun files(source: MemoryWebSource): List<WebFile> =
        notes(source).map { WebFile("${it.node.group.name}/${it.node.title}.md", markdown(it)) }

    private fun linkLine(link: WebLink): String = buildString {
        append("- [[${link.title}]] (${link.relation})")
        link.summary?.takeIf(String::isNotBlank)?.let { append(" — ").append(it.trim().replace('\n', ' ')) }
    }

    private fun groupOf(kind: MemoryNodeKind): WebGroup = when (kind) {
        MemoryNodeKind.Context -> WebGroup.Context
        MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag -> WebGroup.Tag
        MemoryNodeKind.Phrase -> WebGroup.Phrase
        MemoryNodeKind.Summary, MemoryNodeKind.Outline, MemoryNodeKind.PairSummary -> WebGroup.Summary
        MemoryNodeKind.Category -> WebGroup.Category
        MemoryNodeKind.Frame, MemoryNodeKind.Variant, MemoryNodeKind.Deliberation -> WebGroup.Contrast
    }

    private class RawNode(
        val id: String,
        val group: WebGroup,
        val kind: String,
        val base: String,
        val text: String,
        val size: Float,
        val createdAt: Long,
        val sources: List<String>,
    )

    /** A unique, file-safe title per node: its first line, then its kind, then its id when two would be the same. */
    private fun titlesFor(nodes: List<RawNode>): Map<String, String> {
        val taken = mutableSetOf<String>()
        val titles = LinkedHashMap<String, String>()
        nodes.forEach { node ->
            val base = titleOf(node)
            val candidates = sequenceOf(base, "$base (${node.kind})", "$base (${node.id.takeLast(6)})")
            var title = candidates.firstOrNull { taken.add(it.lowercase()) }
            var n = 2
            while (title == null) {
                val numbered = "$base ($n)"
                if (taken.add(numbered.lowercase())) title = numbered else n += 1
            }
            titles[node.id] = title
        }
        return titles
    }

    private fun titleOf(node: RawNode): String {
        val firstLine = node.base.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
        val cleaned = firstLine.replace(titleIllegal, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        return cleaned.take(TITLE_MAX).trim().ifEmpty { node.kind }
    }
}
