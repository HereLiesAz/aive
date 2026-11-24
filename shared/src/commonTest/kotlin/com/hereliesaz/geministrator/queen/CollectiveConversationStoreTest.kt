package com.hereliesaz.geministrator.queen

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectiveConversationStoreTest {
    @Test
    fun restoredHistoryUsesOnlyItsOwnCompletedRedactedTurns() = runTest {
        val settings = MapSettings()
        val first = CollectiveConversationStore(settings) { 100L }
        val a = first.create()
        val b = first.create()
        val answer = CollectiveAnswer("Answer", listOf(CollectiveAssessment("reviewer", "token=abcdefgh123456", "source", "model")), emptyList())
        first.append(a.id, "Check token=abcdefgh123456", answer)
        val restarted = CollectiveConversationStore(settings) { 200L }
        val restored = requireNotNull(restarted.get(a.id))
        assertTrue("Answer" in restored.context())
        assertFalse("abcdefgh123456" in restored.context())
        assertFalse("abcdefgh123456" in restored.turns.single().result.assessments.single().text)
        assertEquals("source", restored.turns.single().result.assessments.single().sourceId)
        assertTrue(requireNotNull(restarted.get(b.id)).turns.isEmpty())
        restarted.delete(a.id)
        assertNull(first.get(a.id))
        assertNull(first.append(a.id, "Cannot resurrect", answer))
        restarted.clear()
        assertTrue(first.list().isEmpty())
    }

    @Test
    fun boundedConversationsAndTurnsKeepNewestData() = runTest {
        val store = CollectiveConversationStore(MapSettings()) { 100L }
        val oldest = store.create()
        repeat(12) { store.create() }
        assertNull(store.get(oldest.id))
        assertEquals(12, store.list().size)
        val current = store.list().first()
        repeat(17) { index ->
            store.append(current.id, "Question $index", CollectiveAnswer("Answer $index", emptyList(), emptyList()))
        }
        val turns = requireNotNull(store.get(current.id)).turns
        assertEquals(16, turns.size)
        assertEquals("Question 1", turns.first().question)
        assertEquals("Answer 16", turns.last().result.answer)
    }
}
