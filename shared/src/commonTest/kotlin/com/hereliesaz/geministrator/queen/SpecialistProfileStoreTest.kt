package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpecialistProfileStoreTest {
    @Test
    fun instanceIdentityDistinguishesRoleRevisionModelAndSource() {
        val role = BuiltInRoles.Architect
        val source = ModelUse("provider-a", "model-a", true)
        val first = specialistInstanceId(role, listOf(source))
        assertEquals(first, specialistInstanceId(role.copy(), listOf(source.copy())))
        assertNotEquals(first, specialistInstanceId(role.copy(instructions = "Different revision"), listOf(source)))
        assertNotEquals(first, specialistInstanceId(role, listOf(source.copy(modelId = "model-b"))))
        assertNotEquals(first, specialistInstanceId(role, listOf(source.copy(sourceId = "provider-b"))))
        assertNotEquals(first, specialistInstanceId(BuiltInRoles.Reviewer, listOf(source)))
        assertEquals(64, first.length)
    }

    @Test
    fun actualCallOutcomesSurviveRestartWithoutCountingDeniedCalls() = runTest {
        val settings = MapSettings()
        val first = SpecialistProfileStore(settings) { 100L }
        val specialist = "a".repeat(64)
        val second = "b".repeat(64)
        val facilitator = "c".repeat(64)
        val result = answerCollectively("Question", "", listOf(
            CollectiveVoice("architect", QueenModel { "Actual assessment" }, specialist),
            CollectiveVoice("reviewer", QueenModel { delay(61_000L); "Too late" }, second),
        ), CollectiveVoice("queen", QueenModel { error("Must not be called") }, facilitator),
            observeCall = { voice, outcome -> first.observe(requireNotNull(voice.specialistId), voice.role, outcome) },
        ) { _, calls -> calls < 2 }
        assertEquals(specialist, result.assessments.single().specialistId)
        assertEquals(listOf("reviewer", "queen"), result.unavailable)
        val restarted = SpecialistProfileStore(settings) { 200L }
        val profiles = restarted.profiles().associateBy { it.id }
        assertEquals(setOf(specialist, second), profiles.keys)
        assertEquals(1L, profiles.getValue(specialist).answered)
        assertEquals(1L, profiles.getValue(second).timedOut)
        assertEquals(0L, profiles.getValue(second).failed)
        restarted.observe(specialist, "architect", SpecialistCallOutcome.Empty)
        restarted.observe(specialist, "architect", SpecialistCallOutcome.Failed)
        val updated = restarted.profiles().single { it.id == specialist }
        assertEquals(1L, updated.answered)
        assertEquals(1L, updated.empty)
        assertEquals(1L, updated.failed)
        assertEquals(100L, updated.firstSeen)
        assertEquals(200L, updated.lastSeen)
        restarted.clear()
        assertTrue(first.profiles().isEmpty())
    }

    @Test
    fun profilesRetainOnlyTheNewestBoundedInstances() = runTest {
        val store = SpecialistProfileStore(MapSettings()) { 100L }
        repeat(129) { i ->
            store.observe(i.toString(16).padStart(64, '0'), "architect", SpecialistCallOutcome.Answered)
        }
        assertEquals(128, store.profiles().size)
        assertTrue(store.profiles().none { it.id == "0".repeat(64) })
    }
}
