package com.hereliesaz.geministrator.orchestration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanParentsTest {
    private val packet = OrchestrationPacket(
        objective = "o",
        availableAgents = listOf(OrchestrationRole("eng", "Engineer", emptyList()), OrchestrationRole("qa", "QA", emptyList())),
        instruction = "plan it",
    )

    private fun step(id: String, role: String, vararg after: String, parents: List<String> = emptyList()) =
        OrchestrationPlanStep(id, id, "do $id", role, after.toList(), parents = parents)

    private fun plan(vararg steps: OrchestrationPlanStep) = OrchestrationPlan(steps.toList())

    private val chain = plan(step("a", "eng"), step("b", "eng", "a"), step("c", "qa", "b"))
    private val small = plan(step("a", "eng"), step("c", "qa", "a"))
    private val wide = plan(step("a", "eng"), step("b", "qa"), step("c", "eng", "a", "b"))

    private class Planner(
        override val planners: List<OrchestrationAgentRuntime> = emptyList(),
        val answer: (OrchestrationPacket) -> OrchestrationPlan,
    ) : OrchestrationAgentRuntime {
        val seen = mutableListOf<OrchestrationPacket>()
        override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan = answer(packet).also { seen += packet }
        override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan = plan(packet)
    }

    @Test
    fun withNoOneElseToAskTheQueensDraftIsTheWholeOfIt() = runTest {
        val queen = Planner { chain }
        val result = planFromParents(packet, queen, chain) { 0 }
        assertEquals(chain, result.plan)
        assertEquals(1, result.calls)
        assertEquals(false, result.merged)
        assertNull(result.storyLine())
    }

    @Test
    fun theQueenWritesTheChildFromThreeDraftsAndItsParentTagsAreKeptOnlyWhenTheyNameADraft() = runTest {
        val fewest = Planner { small }
        val unusual = Planner { wide }
        val merged = plan(
            step("a", "eng", parents = listOf("queen", "planner-unusual", "nobody")),
            step("c", "qa", "a", parents = listOf("planner-fewest")),
        )
        val queen = Planner(listOf(fewest, unusual)) { merged }
        val result = planFromParents(packet, queen, chain) { 0 }
        assertTrue(result.merged)
        assertEquals(4, result.calls)
        assertEquals(listOf("queen", "planner-fewest", "planner-unusual"), result.drafts.map { it.origin })
        assertEquals(listOf("queen", "planner-unusual"), result.plan.steps.first().parents)
        // The planners were given their lens, and the merge was given every draft.
        assertTrue(PlannerLens.Fewest.instruction in fewest.seen.single().instruction)
        assertTrue(PlannerLens.Unusual.instruction in unusual.seen.single().instruction)
        val mergeAsk = queen.seen.last().instruction
        assertTrue("MERGE: 3 drafts" in mergeAsk)
        assertTrue("planner-fewest" in mergeAsk && "planner-unusual" in mergeAsk)
        assertTrue(result.storyLine().orEmpty().contains("merged"))
    }

    @Test
    fun aMergeThatFailsTheRulesLeavesTheMostUnusualDraftThatPasses() = runTest {
        // The child is one step that fails the rules; of the three drafts that pass, the wide one is least like the others.
        val failing = planFromParents(packet, Planner(listOf(Planner { small }, Planner { wide })) { plan(step("z", "qa")) }, chain) { candidate ->
            if (candidate.steps.singleOrNull()?.id == "z") 1 else 0
        }
        assertEquals(false, failing.merged)
        assertEquals("planner-unusual", failing.keptOrigin)
        assertEquals(wide, failing.plan)
        assertTrue(failing.storyLine().orEmpty().contains("did not pass"))
    }

    @Test
    fun aPlannerThatFailsIsNoParentAndAMergeThatFailsKeepsTheQueensWhenNothingElseDiffers() = runTest {
        val broken = Planner { error("offline") }
        val queen = Planner(listOf(broken, Planner { small })) { error("merge down") }
        // The queen's own first draft is passed in, so only her merge call throws.
        val result = planFromParents(packet, queen, chain) { 0 }
        assertEquals(listOf("queen", "planner-unusual"), result.drafts.map { it.origin })
        assertEquals(false, result.merged)
        // With two drafts each is as unusual as the other; the queen's comes first and wins the tie.
        assertEquals("queen", result.keptOrigin)
    }

    @Test
    fun aSourceThatAnswersAFewRequestsAMinuteGetsNoCompanionPlanners() {
        val limited = FailoverOrchestrationAgentRuntime(
            listOf(
                PlannerCandidate("other", "Other", Planner { chain }),
                PlannerCandidate(com.hereliesaz.geministrator.ProviderCatalog.OVHCLOUD_ID, "OVHcloud", Planner { chain }),
            ),
        )
        assertTrue(limited.planners.isEmpty())
        val roomy = FailoverOrchestrationAgentRuntime(
            listOf(PlannerCandidate("a", "A", Planner { chain }), PlannerCandidate("b", "B", Planner { chain })),
        )
        assertEquals(2, roomy.planners.size)
    }

    @Test
    fun planOfIdenticalShapeIsNotUnusualAndOneWithNothingInCommonIsAsUnusualAsCanBe() {
        assertEquals(0.0, distance(chain, chain))
        assertEquals(1.0, distance(plan(step("x", "eng")), plan(step("y", "qa", "x"))))
    }
}
