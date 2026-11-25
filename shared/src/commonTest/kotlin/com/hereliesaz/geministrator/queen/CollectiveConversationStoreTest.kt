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
    fun automaticEvidencePermissionIsPerConversationPersistentAndRevocable() = runTest {
        val settings = MapSettings()
        val store = CollectiveConversationStore(settings) { 100L }
        val a = store.create()
        val b = store.create()
        assertFalse(a.automaticEvidence)
        assertFalse(b.automaticEvidence)
        assertTrue(requireNotNull(store.setAutomaticEvidence(a.id, true)).automaticEvidence)
        store.append(a.id, "Question", CollectiveAnswer("Answer", emptyList(), emptyList()))
        val restored = CollectiveConversationStore(settings) { 200L }
        assertTrue(requireNotNull(restored.get(a.id)).automaticEvidence)
        assertFalse(requireNotNull(restored.get(b.id)).automaticEvidence)
        assertFalse(requireNotNull(restored.setAutomaticEvidence(a.id, false)).automaticEvidence)
        assertFalse(requireNotNull(store.get(a.id)).automaticEvidence)
        restored.delete(a.id)
        assertNull(store.setAutomaticEvidence(a.id, true))
        assertNull(store.get(a.id))
    }

    @Test
    fun unattendedPermissionAndAttemptSpacingSurviveRestartWithoutInventingKeeperUtterances() = runTest {
        val settings = MapSettings()
        var time = 100L
        val store = CollectiveConversationStore(settings) { time }
        val id = store.create().id
        assertNull(store.setBackgroundEvidence(id, true))
        assertNull(store.claimBackgroundEvidence(id))
        store.setAutomaticEvidence(id, true)
        assertNull(store.claimBackgroundEvidence(id))
        assertTrue(requireNotNull(store.setBackgroundEvidence(id, true)).backgroundEvidence)
        assertEquals(100L, requireNotNull(store.claimBackgroundEvidence(id)).lastBackgroundEvidenceAttempt)
        val restored = CollectiveConversationStore(settings) { time }
        time = 600_099L
        assertNull(restored.claimBackgroundEvidence(id))
        time = 600_100L
        assertEquals(600_100L, requireNotNull(restored.claimBackgroundEvidence(id)).lastBackgroundEvidenceAttempt)
        assertNull(restored.claimBackgroundEvidence(id))
        val result = CollectiveAnswer("Actual facilitator reply", listOf(CollectiveAssessment("reviewer", "Changed my mind.")), emptyList())
        store.append(id, "Earlier question", result, automaticReconsideration = true)
        val conversation = requireNotNull(restored.get(id))
        assertTrue(conversation.turns.single().automaticReconsideration)
        assertTrue("Host reconsideration of earlier question:" in conversation.context())
        assertEquals(listOf("Changed my mind.", "Actual facilitator reply"),
            conversation.historicalMemoryRequests(time).map { it.text })
        assertFalse(requireNotNull(restored.setAutomaticEvidence(id, false)).backgroundEvidence)
        time = 1_200_100L
        assertNull(store.claimBackgroundEvidence(id))
        store.delete(id)
        assertNull(restored.claimBackgroundEvidence(id))
    }

    @Test
    fun allAdaptiveExaminationsSurviveRestartAndHistoryImport() = runTest {
        val settings = MapSettings()
        val store = CollectiveConversationStore(settings) { 100L }
        val id = store.create().id
        val assessments = (1..9).map { CollectiveAssessment("role-$it", "Actual assessment $it",
            crossExamination = it > 2, respondsTo = if (it > 2) it - 1 else null,
            persuadedBy = if (it == 9) 8 else null) }
        store.append(id, "Question", CollectiveAnswer("Answer", assessments, emptyList(),
            reviewRequested = true, stop = DeliberationStop.RepeatedObjection))
        val restored = requireNotNull(CollectiveConversationStore(settings) { 200L }.get(id))
        assertEquals(assessments, restored.turns.single().result.assessments)
        assertEquals(11, restored.historicalMemoryRequests(200L).size)
        assertTrue("reports persuaded by role-8" in restored.context())
        assertTrue(restored.historicalMemoryRequests(200L).any { "persuadedBy=8" in it.label })
        assertEquals(DeliberationStop.RepeatedObjection, restored.turns.single().result.stop)
    }

    @Test
    fun crossExaminationSurvivesRestartAndFullTranscriptImport() = runTest {
        val settings = MapSettings()
        val store = CollectiveConversationStore(settings) { 100L }
        val id = store.create().id
        repeat(16) {
            store.append(id, "Question", CollectiveAnswer("Answer", listOf(
                CollectiveAssessment("reviewer", "Initial objection"),
                CollectiveAssessment("skeptic", "Initial estimate"),
                CollectiveAssessment("skeptic", "Measurement still missing", crossExamination = true),
            ), emptyList(), reviewRequested = true))
        }
        val restored = requireNotNull(CollectiveConversationStore(settings) { 200L }.get(id))
        assertTrue(restored.turns.last().result.reviewRequested)
        assertTrue(restored.turns.last().result.assessments.last().crossExamination)
        assertTrue("Measurement still missing" in restored.context())
        val requests = restored.historicalMemoryRequests(200L)
        assertEquals(80, requests.size)
        assertEquals(16, requests.count { it.text == "Measurement still missing" })
    }

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
