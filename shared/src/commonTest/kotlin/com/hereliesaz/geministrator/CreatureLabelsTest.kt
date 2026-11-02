package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreatureLabelsTest {
    @Test
    fun aSmallHiveLabelsEveryCreature() {
        assertTrue(creatureWearsLabel(selected = false, creatureCount = NAMED_CREATURE_LIMIT, alwaysShowLabels = false))
    }

    @Test
    fun aCrowdedHiveLabelsOnlyTheSelectedCreature() {
        val crowded = NAMED_CREATURE_LIMIT + 1
        assertFalse(creatureWearsLabel(selected = false, creatureCount = crowded, alwaysShowLabels = false))
        assertTrue(creatureWearsLabel(selected = true, creatureCount = crowded, alwaysShowLabels = false))
    }

    @Test
    fun alwaysShowLiftsTheCap() {
        assertTrue(creatureWearsLabel(selected = false, creatureCount = NAMED_CREATURE_LIMIT + 1, alwaysShowLabels = true))
        assertTrue(creatureWearsLabel(selected = false, creatureCount = 200, alwaysShowLabels = true))
    }
}
