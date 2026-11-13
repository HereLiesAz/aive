package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainingSuggestionTest {
    private fun role(id: String, parents: List<String> = emptyList(), enabled: Boolean = true, hatched: String? = null) =
        RoleDefinition(
            RoleDefinitionId(id), id, "d", "i",
            enabled = enabled, parents = parents.map { RoleDefinitionId(it) }, hatchedFromRunId = hatched,
        )

    private fun uses(roleId: String, domain: String, count: Int, runs: Int) =
        (0 until count).map { RoleUse(roleId, "run-${it % runs}", domain) }

    @Test
    fun repeatedUseInOneDomainSuggestsTraining() {
        val found = trainingCandidates(uses("qa", "p1", 5, 3), listOf(role("qa")))
        assertEquals(1, found.size)
        assertEquals(QueenAction.TrainSpecialist, found.single().action)
        assertEquals("qa", found.single().subject)
        assertEquals(listOf("qa", "5"), found.single().args)
    }

    @Test
    fun useSpreadAcrossDomainsOrTooFewRunsOrTooFewUsesSuggestsNothing() {
        val qa = listOf(role("qa"))
        assertTrue(trainingCandidates(uses("qa", "p1", 3, 3) + uses("qa", "p2", 3, 3), qa).isEmpty())
        assertTrue(trainingCandidates(uses("qa", "p1", 6, 2), qa).isEmpty())
        assertTrue(trainingCandidates(uses("qa", "p1", 4, 4), qa).isEmpty())
        assertTrue(trainingCandidates(emptyList(), qa).isEmpty())
    }

    @Test
    fun onlyAGenericRoleWithoutASpecialistQualifies() {
        val spec = role("qa-web", parents = listOf("qa"), hatched = "run-x")
        assertTrue(trainingCandidates(uses("qa", "p1", 6, 4), listOf(role("qa"), spec)).isEmpty())
        assertTrue(trainingCandidates(uses("qa-web", "p1", 6, 4), listOf(role("qa"), spec)).isEmpty())
        assertTrue(trainingCandidates(uses("qa", "p1", 6, 4), listOf(role("qa", enabled = false))).isEmpty())
        assertTrue(trainingCandidates(uses("gone", "p1", 6, 4), listOf(role("qa"))).isEmpty())
        assertTrue(trainingCandidates(uses("qa-draft", "p1", 6, 4), listOf(role("qa-draft"))).isEmpty())
    }

    @Test
    fun mostUsedRoleComesFirst() {
        val roles = listOf(role("a"), role("b"))
        val found = trainingCandidates(uses("a", "p", 5, 3) + uses("b", "p", 9, 3), roles)
        assertEquals(listOf("b", "a"), found.map { it.subject })
    }

    @Test
    fun acceptingNeverAppliesItAndTheKeeperAlwaysDecidesAdoption() {
        assertTrue(!QueenAction.TrainSpecialist.appliesHere)
        assertTrue(needsKeeper(QueenAction.TrainSpecialist.keeperSituation()!!) != null)
    }
}
