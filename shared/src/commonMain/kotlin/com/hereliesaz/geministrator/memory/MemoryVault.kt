package com.hereliesaz.geministrator.memory

/*
 * The memory bank read as a vault of linked notes, the way Obsidian reads a folder of markdown (docs/Memory-Vault.md).
 *
 * Pure and read-only: a [MemorySnapshot] in, notes out. The mind map, the notes view and the vault export all read this one
 * model, so they cannot disagree about what a memory is called or what it links to. Nothing here writes to the bank; a keeper's
 * own memory goes in through [MemoryLayerController.bankKeeperMemory] like every other memory.
 */

/** One link from a note to another (an outgoing link) or from another to it (a backlink): [title] is the other note's. */
data class VaultLink(
    val target: MemoryNodeId,
    val title: String,
    val relation: MemoryRelationKind,
    val weight: Float,
    /** What the pair of linked memories says together (the link's pair summary), when it has one. */
    val summary: String? = null,
)

/** One memory as a note: its text, where it came from, what it links to and what links to it. */
data class VaultNote(
    val id: MemoryNodeId,
    /** Unique within the vault, safe as a file name and as a `[[link]]`. */
    val title: String,
    val kind: MemoryNodeKind,
    val text: String,
    val salience: Float,
    val createdAtEpochMillis: Long,
    /** The episodes this memory was made from, one line each. */
    val sources: List<String>,
    val outgoing: List<VaultLink>,
    val backlinks: List<VaultLink>,
)

/** One file of an exported vault, [path] relative to the vault's folder. */
data class VaultFile(val path: String, val content: String)

object MemoryVault {
    /** Engine bookkeeping, not memories the keeper reads: the summary tree's nodes and the pair summaries (shown on their link). */
    private val bookkeepingKinds = setOf(MemoryNodeKind.Outline, MemoryNodeKind.PairSummary)

    /** Edges that record machinery (a recall happened, a tree's shape), not a relation between two memories. */
    private val bookkeepingRelations = setOf(MemoryRelationKind.Recalled, MemoryRelationKind.Outlines)

    private const val TITLE_MAX = 60
    private val titleIllegal = Regex("[\\\\/:*?\"<>|#^\\[\\]\\u0000-\\u001F]+")

    /** The notes of [snapshot], oldest first. Titles are stable for a given snapshot. */
    fun notes(snapshot: MemorySnapshot): List<VaultNote> {
        val nodes = snapshot.nodes
            .filter { it.kind !in bookkeepingKinds }
            .sortedWith(compareBy({ it.createdAtEpochMillis }, { it.id.value }))
        val titles = titlesFor(nodes)
        val byId = nodes.associateBy { it.id }
        val pairSummaries = snapshot.nodes
            .filter { it.kind == MemoryNodeKind.PairSummary }
            .mapNotNull { node -> node.metadata[PAIR_OF]?.let { it to node.text } }
            .toMap()
        val episodes = snapshot.episodes.associateBy { it.id }
        val edges = snapshot.edges.filter { it.relation !in bookkeepingRelations && it.from in byId && it.to in byId }
        val outgoing = edges.groupBy { it.from }
        val incoming = edges.groupBy { it.to }

        fun link(edge: MemoryEdge, other: MemoryNodeId) = VaultLink(
            target = other,
            title = titles.getValue(other),
            relation = edge.relation,
            weight = edge.weight,
            summary = pairSummaries[edge.id.value],
        )

        return nodes.map { node ->
            VaultNote(
                id = node.id,
                title = titles.getValue(node.id),
                kind = node.kind,
                text = node.text,
                salience = node.salience,
                createdAtEpochMillis = node.createdAtEpochMillis,
                sources = node.sourceEpisodeIds.sortedBy { it.value }.map { id ->
                    episodes[id]?.let { "${id.value} (${it.sourceSessionId})" } ?: id.value
                },
                outgoing = outgoing[node.id].orEmpty().sortedWith(compareBy({ -it.weight }, { it.id.value })).map { link(it, it.to) },
                backlinks = incoming[node.id].orEmpty().sortedWith(compareBy({ -it.weight }, { it.id.value })).map { link(it, it.from) },
            )
        }
    }

    /** [note] as a markdown file Obsidian opens as it is: front matter, the text, then `[[links]]` and backlinks. */
    fun markdown(note: VaultNote): String = buildString {
        appendLine("---")
        appendLine("id: ${note.id.value}")
        appendLine("kind: ${note.kind.name}")
        appendLine("salience: ${(note.salience * 100).toInt() / 100.0}")
        appendLine("created: ${note.createdAtEpochMillis}")
        if (note.sources.isNotEmpty()) {
            appendLine("sources:")
            note.sources.forEach { appendLine("  - \"${it.replace("\"", "'")}\"") }
        }
        appendLine("---")
        appendLine()
        appendLine("# ${note.title}")
        appendLine()
        appendLine(note.text.trim())
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

    /** Every note as a file, in a folder per kind (`Summary/Title.md`); a `[[Title]]` link finds it wherever it lives. */
    fun files(snapshot: MemorySnapshot): List<VaultFile> =
        notes(snapshot).map { VaultFile("${it.kind.name}/${it.title}.md", markdown(it)) }

    private fun linkLine(link: VaultLink): String = buildString {
        append("- [[${link.title}]] (${link.relation.name})")
        link.summary?.takeIf(String::isNotBlank)?.let { append(" — ").append(it.trim().replace('\n', ' ')) }
    }

    /** A unique, file-safe title per node: its first line, then its kind, then its id when two would be the same. */
    private fun titlesFor(nodes: List<MemoryNode>): Map<MemoryNodeId, String> {
        val taken = mutableSetOf<String>()
        val titles = LinkedHashMap<MemoryNodeId, String>()
        nodes.forEach { node ->
            val base = titleOf(node)
            val candidates = sequenceOf(base, "$base (${node.kind.name})", "$base (${node.id.value.takeLast(6)})")
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

    private fun titleOf(node: MemoryNode): String {
        val firstLine = node.text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
        val cleaned = firstLine.replace(titleIllegal, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        return cleaned.take(TITLE_MAX).trim().ifEmpty { node.kind.name }
    }
}
