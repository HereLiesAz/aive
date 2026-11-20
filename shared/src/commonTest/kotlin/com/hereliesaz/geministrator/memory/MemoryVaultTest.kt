package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryVaultTest {
    private fun node(id: String, kind: MemoryNodeKind, text: String, at: Long = 1L, metadata: Map<String, String> = emptyMap()) =
        MemoryNode(MemoryNodeId(id), kind, text, createdAtEpochMillis = at, metadata = metadata)

    private fun edge(id: String, from: String, to: String, relation: MemoryRelationKind = MemoryRelationKind.AssociatedWith, weight: Float = 0.9f) =
        MemoryEdge(MemoryEdgeId(id), MemoryNodeId(from), MemoryNodeId(to), relation, weight, createdAtEpochMillis = 1L)

    private val snapshot = MemorySnapshot(
        nodes = listOf(
            node("a", MemoryNodeKind.Summary, "The build fails on Tuesdays.\nSecond line.", at = 1),
            node("b", MemoryNodeKind.Phrase, "The build fails on Tuesdays.", at = 2),
            node("c", MemoryNodeKind.Context, "Use [[brackets]] and #tags: here?", at = 3),
            node("tree", MemoryNodeKind.Outline, "a tree node", at = 4),
            node("pair", MemoryNodeKind.PairSummary, "Both say the build is flaky.", at = 5, metadata = mapOf(PAIR_OF to "e1")),
        ),
        edges = listOf(
            edge("e1", "a", "b"),
            edge("e2", "c", "a", MemoryRelationKind.Summarizes),
            edge("e3", "a", "tree", MemoryRelationKind.Outlines),
            edge("e4", "b", "c", MemoryRelationKind.Recalled),
        ),
    )

    @Test
    fun bookkeepingIsNotANoteAndTitlesAreUniqueAndFileSafe() {
        val notes = MemoryVault.notes(snapshot)
        assertEquals(listOf("a", "b", "c"), notes.map { it.id.value })
        assertEquals(notes.size, notes.map { it.title.lowercase() }.toSet().size)
        notes.forEach { note -> assertFalse(Regex("[\\\\/:*?\"<>|#^\\[\\]]").containsMatchIn(note.title), note.title) }
        // Two memories opening with the same line are told apart by kind.
        assertEquals("The build fails on Tuesdays", notes[0].title.trimEnd('.'))
        assertTrue(notes[1].title.contains("Phrase"), notes[1].title)
    }

    @Test
    fun backlinksMirrorOutgoingLinksAndMachineryEdgesAreLeftOut() {
        val notes = MemoryVault.notes(snapshot).associateBy { it.id.value }
        val a = notes.getValue("a")
        val b = notes.getValue("b")
        val c = notes.getValue("c")
        assertEquals(listOf("b"), a.outgoing.map { it.target.value })
        assertEquals(listOf("a"), b.backlinks.map { it.target.value })
        assertEquals(listOf("c"), a.backlinks.map { it.target.value })
        // Outlines and Recalled edges are machinery, and an edge to bookkeeping has no note to land on.
        assertTrue(c.backlinks.isEmpty())
        notes.values.forEach { note ->
            note.outgoing.forEach { link ->
                assertTrue(notes.getValue(link.target.value).backlinks.any { it.target == note.id }, "${note.id} -> ${link.target}")
            }
        }
    }

    @Test
    fun aPairSummaryRidesOnItsLink() {
        val a = MemoryVault.notes(snapshot).first { it.id.value == "a" }
        assertEquals("Both say the build is flaky.", a.outgoing.single().summary)
    }

    @Test
    fun markdownLinksResolveToAnotherNotesTitle() {
        val notes = MemoryVault.notes(snapshot)
        val titles = notes.map { it.title }.toSet()
        notes.forEach { note ->
            val markdown = MemoryVault.markdown(note)
            assertTrue(markdown.startsWith("---\nid: ${note.id.value}\n"), markdown)
            Regex("\\[\\[(.+?)\\]\\]").findAll(markdown).forEach { match ->
                assertTrue(match.groupValues[1] in titles, "dangling link ${match.value} in ${note.title}")
            }
        }
        val a = MemoryVault.markdown(notes.first { it.id.value == "a" })
        assertTrue("## Links" in a && "## Backlinks" in a)
        assertTrue("Both say the build is flaky." in a)
    }

    @Test
    fun filesLiveInAFolderPerKindWithDistinctPaths() {
        val files = MemoryVault.files(snapshot)
        assertEquals(3, files.size)
        assertEquals(files.size, files.map { it.path.lowercase() }.toSet().size)
        assertTrue(files.all { it.path.endsWith(".md") && '/' in it.path })
    }
}
