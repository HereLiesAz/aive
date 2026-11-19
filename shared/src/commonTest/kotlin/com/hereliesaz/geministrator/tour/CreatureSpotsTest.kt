package com.hereliesaz.geministrator.tour

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which creature the tour's ring goes to after the hive moves. */
class CreatureSpotsTest {
    private fun line(id: String, stage: CreatureStage) = CreatureLine(id, id, stage)

    @Test
    fun theCreatureThatJustStartedWorkingIsLookedAt() {
        val before = listOf(line("a", CreatureStage.Waiting), line("b", CreatureStage.Egg))
        val after = listOf(line("a", CreatureStage.Working), line("b", CreatureStage.Egg))
        assertEquals("a", spotlightFor(before, after, null))
    }

    @Test
    fun aFinishedCreatureTakesTheLookFromTheOneBefore() {
        val before = listOf(line("a", CreatureStage.Working), line("b", CreatureStage.Egg))
        val after = listOf(line("a", CreatureStage.Done), line("b", CreatureStage.Working))
        assertEquals("a", spotlightFor(before, after, "a"))
    }

    @Test
    fun nothingChangingKeepsTheLookWhereItWas() {
        val lines = listOf(line("a", CreatureStage.Working), line("b", CreatureStage.Working))
        assertEquals("b", spotlightFor(lines, lines, "b"))
    }

    @Test
    fun aLookAtACreatureThatLeftTheListFallsToOneThatIsWorking() {
        val after = listOf(line("a", CreatureStage.Done), line("b", CreatureStage.Working))
        assertEquals("b", spotlightFor(after, after, "gone"))
        assertEquals(null, spotlightFor(emptyList(), emptyList(), null))
    }
}
