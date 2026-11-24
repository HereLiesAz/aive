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
    fun priorOpposingAssessmentsSurviveSynthesisAndFailedFollowUp() = runTest {
        val settings = MapSettings()
        val store = CollectiveConversationStore(settings) { 100L }
        val id = store.create().id
        val other = store.create().id
        store.append(id, "Which option?", CollectiveAnswer("Choose X", listOf(
            CollectiveAssessment("reviewer", "Support X. " + "a".repeat(1100)),
            CollectiveAssessment("skeptic", "Reject X: missing independent evidence. " + "b".repeat(1100)),
        ), emptyList()))
        store.append(id, "Follow up " + "c".repeat(1400), CollectiveAnswer(null, emptyList(), listOf("Queen")))
        store.append(other, "Other conversation", CollectiveAnswer("Unrelated", listOf(
            CollectiveAssessment("private", "not shared with the first conversation"),
        ), emptyList()))
        val restored = requireNotNull(CollectiveConversationStore(settings) { 200L }.get(id))
        val history = restored.context()
        assertTrue("Support X." in history)
        assertTrue("Reject X: missing independent evidence." in history)
        assertTrue("unverified; may be from an earlier turn" in history)
        assertFalse("not shared with the first conversation" in history)
        assertTrue(history.length <= 1800)
        assertTrue(restored.turns.first().result.assessments[1].text.endsWith("b".repeat(1100)))
    }

    @Test
    fun recallGrantsAreDirectionalDurableAndNeverTransitive() = runTest {
        val settings = MapSettings()
        val store = CollectiveConversationStore(settings) { 100L }
        val a = store.create().id
        val b = store.create().id
        val c = store.create().id
        assertEquals(listOf(b), store.recallBanks(b))
        assertFalse(store.setRecallGrant(b, b, true))
        assertFalse(store.setRecallGrant("collective:missing", b, true))
        assertFalse(store.setRecallGrant(a, "collective:missing", true))
        assertTrue(store.setRecallGrant(a, b, true))
        assertTrue(store.setRecallGrant(b, c, true))
        val restarted = CollectiveConversationStore(settings) { 200L }
        assertEquals(listOf(b, a), restarted.recallBanks(b))
        assertEquals(listOf(a), restarted.recallBanks(a))
        assertEquals(listOf(c, b), restarted.recallBanks(c))
        assertTrue(restarted.setRecallGrant(a, b, false))
        assertEquals(listOf(b), store.recallBanks(b))
        assertTrue(restarted.setRecallGrant(a, b, true))
        restarted.delete(a)
        assertEquals(emptyList(), restarted.recallBanks(a))
        assertEquals(listOf(b), restarted.recallBanks(b))
        assertTrue(requireNotNull(restarted.get(b)).recallSourceIds.isEmpty())
        restarted.clear()
        assertTrue(restarted.recallBanks(b).isEmpty())
    }

    @Test
    fun recallGrantLimitAndEvictionCannotLeaveDanglingAccess() = runTest {
        val store = CollectiveConversationStore(MapSettings()) { 100L }
        val oldest = store.create().id
        val destination = store.create().id
        val sources = List(7) { store.create().id }
        assertTrue(store.setRecallGrant(oldest, destination, true))
        sources.take(6).forEach { assertTrue(store.setRecallGrant(it, destination, true)) }
        assertFalse(store.setRecallGrant(sources.last(), destination, true))
        assertEquals(8, store.recallBanks(destination).size)
        repeat(4) { store.create() }
        assertNull(store.get(oldest))
        assertFalse(oldest in store.recallBanks(destination))
        assertTrue(store.setRecallGrant(sources.last(), destination, true))
        assertEquals(8, store.recallBanks(destination).size)
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
