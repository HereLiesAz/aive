package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryGistTest {
    private fun node(id: String, text: String) =
        MemoryNode(MemoryNodeId(id), MemoryNodeKind.Summary, text, createdAtEpochMillis = 0L)

    @Test
    fun gistIsOneShortLineCutAtAWordBoundary() {
        assertEquals("Postgres was chosen.", gistOf("  Postgres  was\nchosen. "))
        assertEquals("word word word word…", gistOf("word ".repeat(100), 20))
        assertEquals("abcdefghij…", gistOf("abcdefghijklmnop", 10), "one long word is cut where it stands")
    }

    @Test
    fun divergenceIsNotedWithoutScoresOrIds() {
        val hit = MemoryRecallHit(node("c1", "I chose Postgres for the database."), 0.87f, conflicts = listOf(node("c2", "I chose MySQL for the database.")))
        val notes = hit.gistNotes()
        assertTrue("↔ diverges" in notes && "MySQL" in notes, notes)
        assertFalse("c2" in notes || "0.87" in notes || "memory-node" in notes || "memory-history" in notes, notes)
        assertEquals("", MemoryRecallHit(node("c3", "Alone."), 0.5f).gistNotes())
    }
}
