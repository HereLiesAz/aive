package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.HourlySpend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisputeSeamsTest {
    private val dispute = Dispute(
        question = "Which?",
        options = listOf(DisputeOption("a", "A", true), DisputeOption("b", "B", true)),
        facts = mapOf("f1" to "A fact."),
    )

    // --- the queen's Source and the voices -------------------------------------------------------------------------

    @Test
    fun theQueensPinIsTheOneOnHerRoleAndAutomaticWhenThereIsNone() {
        assertNull(orchestratorPin(listOf(BuiltInRoles.Orchestrator)))
        assertNull(orchestratorPin(emptyList()))
        assertEquals("kilo", orchestratorPin(listOf(BuiltInRoles.Orchestrator.copy(preferredProviderId = AgentProviderId("kilo")))))
    }

    @Test
    fun theTwoVoicesAreTheNannyAndTheAntagonistEachWithItsOwnRoleText() = runTest {
        val prompts = mutableListOf<String>()
        val voices = disputeVoices({ prompts += it; "ANSWER: AGREE" }, emptyList())
        assertEquals(setOf(HallMonitorRole.id.value, BuiltInRoles.Antagonist.id.value), voices.keys)
        voices.getValue(BuiltInRoles.Antagonist.id.value).answer("Question here")
        assertTrue(prompts.single().startsWith("You answer as the ${BuiltInRoles.Antagonist.name}."))
        assertTrue(prompts.single().endsWith("Question here"))
    }

    @Test
    fun aKeepersEditedRoleTextWinsForItsVoice() = runTest {
        val prompts = mutableListOf<String>()
        val edited = BuiltInRoles.Antagonist.copy(instructions = "Only ever ask for evidence.")
        disputeVoices({ prompts += it; "x" }, listOf(edited)).getValue(edited.id.value).answer("q")
        assertTrue("Only ever ask for evidence." in prompts.single())
    }

    @Test
    fun aVoiceIsReadLikeAnyAnswerAndOnlyAListedFactCounts() {
        assertEquals(ApproachAnswer(agrees = true), parseApproachAnswer(dispute, "ANSWER: AGREE"))
        val cited = parseApproachAnswer(dispute, "ANSWER: DISAGREE\nCITE: f1\nWHY: It is wrong.")!!
        assertFalse(cited.agrees)
        assertEquals("f1", cited.factId)
        assertNull(parseApproachAnswer(dispute, "ANSWER: DISAGREE\nCITE: f9\nWHY: Because.")!!.factId)
        assertNull(parseApproachAnswer(dispute, "I suppose so."))
    }

    @Test
    fun theHourlyCapLeavesRoomOnlyUnderItWithNothingUnpriced() = runTest {
        assertTrue(hourlyCapAllows(null, HourlySpend(100.0, true)))
        assertTrue(hourlyCapAllows(1.0, HourlySpend(0.5, false)))
        assertFalse(hourlyCapAllows(1.0, HourlySpend(1.0, false)))
        assertFalse(hourlyCapAllows(1.0, HourlySpend(0.1, true)))
        val calls = mutableListOf<String>()
        val base = QueenModel { calls += it; "ok" }
        assertEquals("ok", cappedModel(base) { true }.answer("q"))
        assertFailsWith<IllegalStateException> { cappedModel(base) { false }.answer("q2") }
        assertEquals(listOf("q"), calls)
    }

    // --- raised disputes are remembered ----------------------------------------------------------------------------

    @Test
    fun aRaisedDisputeIsNotRaisedAgainAfterARestart() {
        var stored: String? = null
        val before = RaisedDisputes({ stored }, { stored = it })
        assertTrue(before.add("run:1"))
        assertFalse(before.add("run:1"))
        val after = RaisedDisputes({ stored }, { stored = it })
        assertFalse(after.add("run:1"))
        assertTrue(after.add("run:2"))
    }

    @Test
    fun onlyTheNewestKeysAreKeptAndBadKeysAreIgnored() {
        var stored: String? = null
        val keys = RaisedDisputes({ stored }, { stored = it }, max = 3)
        (1..5).forEach { assertTrue(keys.add("k$it")) }
        assertEquals(listOf("k3", "k4", "k5"), keys.keys())
        assertEquals(listOf("k3", "k4", "k5"), RaisedDisputes({ stored }, { stored = it }, max = 3).keys())
        assertFalse(keys.add(""))
        assertFalse(keys.add("a\nb"))
    }

    @Test
    fun aStoreThatFailsStillRemembersInMemory() {
        val keys = RaisedDisputes({ error("no read") }, { error("no write") })
        assertTrue(keys.add("k"))
        assertFalse(keys.add("k"))
    }

    // --- the test swarm's report is read back ----------------------------------------------------------------------

    private fun report(status: TaskRunStatus, text: String? = null) = qTaskRun(
        TEST_REPORT_TASK_ID,
        status,
        artifacts = if (text == null) emptyList() else listOf(
            ArtifactRef(ArtifactId("a"), ArtifactKind.Review, TaskRunId("r-$TEST_REPORT_TASK_ID"), "Report", textContent = text, createdAtEpochMillis = 1),
        ),
    )

    @Test
    fun aCompleteReportIsReadBackAsCappedUntrustedFacts() {
        val text = "- Option A met it.\n\n* Option B failed on step two.\n" + (1..9).joinToString("\n") { "Extra $it" }
        val facts = testReportFacts(qRun(report(TaskRunStatus.Completed, text), status = WorkflowRunStatus.Completed))!!
        assertEquals(MAX_TEST_REPORT_FACTS, facts.size)
        assertEquals("[untrusted reference | test report] Option A met it.", facts.first())
        assertTrue(facts.all { it.startsWith("[untrusted reference | test report] ") })
    }

    @Test
    fun anIncompleteReportWaitsWhileTheRunGoesOnAndIsEmptyOnceItEnded() {
        assertNull(testReportFacts(qRun(report(TaskRunStatus.Running))))
        assertNull(testReportFacts(qRun()))
        assertTrue(testReportFacts(qRun(report(TaskRunStatus.Failed), status = WorkflowRunStatus.Failed))!!.isEmpty())
        assertTrue(testReportFacts(qRun(status = WorkflowRunStatus.Cancelled))!!.isEmpty())
    }
}
