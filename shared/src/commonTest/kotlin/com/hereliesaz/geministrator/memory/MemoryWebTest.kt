package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryWebTest {
    private fun node(id: String, kind: MemoryNodeKind, text: String, at: Long = 1L, salience: Float = 0.5f, episodes: Set<String> = emptySet(), metadata: Map<String, String> = emptyMap()) =
        MemoryNode(
            MemoryNodeId(id), kind, text,
            sourceEpisodeIds = episodes.mapTo(mutableSetOf()) { MemoryEpisodeId(it) },
            salience = salience, createdAtEpochMillis = at, metadata = metadata,
        )

    private fun edge(id: String, from: String, to: String, relation: MemoryRelationKind = MemoryRelationKind.AssociatedWith, weight: Float = 0.9f) =
        MemoryEdge(MemoryEdgeId(id), MemoryNodeId(from), MemoryNodeId(to), relation, weight, createdAtEpochMillis = 1L)

    private fun episode(id: String, session: String, prompt: String) =
        MemoryEpisode(MemoryEpisodeId(id), session, userPrompt = prompt, chunks = emptyList(), createdAtEpochMillis = 1L)

    private val snapshot = MemorySnapshot(
        episodes = listOf(episode("e-agent", "run-1", "Fix the build"), episode("e-keeper", "keeper:vault:9", "Tuesdays are flaky")),
        nodes = listOf(
            node("a", MemoryNodeKind.Summary, "The build fails on Tuesdays.\nSecond line.", at = 1, episodes = setOf("e-agent")),
            node("b", MemoryNodeKind.Phrase, "The build fails on Tuesdays.", at = 2),
            node("c", MemoryNodeKind.Context, "Use [[brackets]] and #tags: here?", at = 3),
            node("kw", MemoryNodeKind.NounTag, "build", at = 3, salience = 0.9f),
            node("tree", MemoryNodeKind.Outline, "a tree node", at = 4),
            node("pair", MemoryNodeKind.PairSummary, "Both say the build is flaky.", at = 5, metadata = mapOf(PAIR_OF to "e1")),
            node("gen", MemoryNodeKind.Summary, "Builds fail on schedule.", at = 6),
            node("mine", MemoryNodeKind.Phrase, "Rerun twice before trusting a red build", at = 7, episodes = setOf("e-keeper")),
            node("frame", MemoryNodeKind.Frame, "Which day does the build fail", at = 8),
        ),
        edges = listOf(
            edge("e1", "a", "b"),
            edge("e2", "c", "a", MemoryRelationKind.Summarizes),
            edge("e3", "a", "tree", MemoryRelationKind.Outlines),
            edge("e4", "b", "c", MemoryRelationKind.Recalled),
            edge("e5", "b", "c", MemoryRelationKind.Recalled),
            edge("e6", "gen", "a", MemoryRelationKind.CondensedFrom, 0.7f),
        ),
    )

    private val graph = MemoryWeb.graph(MemoryWebSource(snapshot))

    @Test
    fun everyKindOfMemoryIsInTheWebAndColouredByItsFamily() {
        val byKind = graph.nodes.groupBy { it.kind }
        listOf("Summary", "Phrase", "Context", "NounTag", "Outline", "PairSummary", "Frame", "Episode").forEach { assertTrue(it in byKind, it) }
        fun group(id: String) = graph.nodes.first { it.id == "node:$id" }.group
        assertEquals(WebGroup.Tag, group("kw"))
        assertEquals(WebGroup.Summary, group("tree"))
        assertEquals(WebGroup.Summary, group("pair"))
        assertEquals(WebGroup.Contrast, group("frame"))
        assertEquals(WebGroup.Generalization, group("gen"))
        assertEquals(WebGroup.Keeper, group("mine"))
        assertEquals(WebGroup.Source, graph.nodes.first { it.id == "episode:e-agent" }.group)
        assertEquals(WebGroup.Keeper, graph.nodes.first { it.id == "episode:e-keeper" }.group)
    }

    @Test
    fun aRecallMakesAMemoryBiggerNotAnotherLine() {
        val b = graph.nodes.first { it.id == "node:b" }
        val c = graph.nodes.first { it.id == "node:c" }
        assertTrue(c.size > 0.5f, "two recalls grew c")
        assertEquals(0.5f, b.size)
        assertFalse(graph.edges.any { it.relation == "Recalled" })
    }

    @Test
    fun saliencyIsSizeAndStrengthIsWidth() {
        assertTrue(graph.nodes.first { it.id == "node:kw" }.size > graph.nodes.first { it.id == "node:b" }.size)
        assertEquals(0.7f, graph.edges.first { it.relation == "CondensedFrom" }.weight)
    }

    @Test
    fun titlesAreUniqueAndFileSafe() {
        assertEquals(graph.nodes.size, graph.nodes.map { it.title.lowercase() }.toSet().size)
        graph.nodes.forEach { assertFalse(Regex("[\\\\/:*?\"<>|#^\\[\\]]").containsMatchIn(it.title), it.title) }
    }

    @Test
    fun backlinksMirrorLinksAndEveryEdgeLandsOnANode() {
        val notes = MemoryWeb.notes(graph).associateBy { it.node.id }
        graph.edges.forEach { assertTrue(it.from in notes && it.to in notes, "${it.from} -> ${it.to}") }
        notes.values.forEach { note ->
            note.outgoing.forEach { link -> assertTrue(notes.getValue(link.target).backlinks.any { it.target == note.node.id }) }
        }
        // The source episode links to what was made from it; the pair summary to both ends of its link.
        assertTrue(notes.getValue("episode:e-agent").outgoing.any { it.target == "node:a" })
        assertEquals(setOf("node:a", "node:b"), notes.getValue("node:pair").outgoing.map { it.target }.toSet())
        assertEquals("Both say the build is flaky.", notes.getValue("node:a").outgoing.first { it.target == "node:b" && it.relation == "AssociatedWith" }.summary)
    }

    @Test
    fun markdownLinksResolveToAnotherNotesTitle() {
        val notes = MemoryWeb.notes(graph)
        val titles = notes.map { it.node.title }.toSet()
        notes.forEach { note ->
            val markdown = MemoryWeb.markdown(note)
            assertTrue(markdown.startsWith("---\nid: ${note.node.id}\n"), markdown)
            Regex("\\[\\[(.+?)\\]\\]").findAll(markdown).forEach { assertTrue(it.groupValues[1] in titles, "dangling ${it.value} in ${note.node.title}") }
        }
    }

    @Test
    fun filesLiveInAFolderPerGroupWithDistinctPaths() {
        val files = MemoryWeb.files(MemoryWebSource(snapshot))
        assertEquals(graph.nodes.size, files.size)
        assertEquals(files.size, files.map { it.path.lowercase() }.toSet().size)
        assertTrue(files.all { it.path.endsWith(".md") && '/' in it.path })
    }
}
