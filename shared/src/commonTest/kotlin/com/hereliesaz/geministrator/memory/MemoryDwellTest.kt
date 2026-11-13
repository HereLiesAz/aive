package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals

class MemoryDwellTest {
    @Test
    fun eachHitOnATopicGoesOneLevelDeeperUpToTheDetail() {
        val dwell = MemoryDwell(tokensPerStep = 100)
        assertEquals(MemoryDepth.Gist, dwell.hit("gradle cache"))
        assertEquals(MemoryDepth.Phrases, dwell.hit("gradle cache"))
        assertEquals(MemoryDepth.Summaries, dwell.hit("gradle cache"))
        assertEquals(MemoryDepth.Detail, dwell.hit("gradle cache"))
        assertEquals(MemoryDepth.Detail, dwell.hit("gradle cache"), "the ladder stops at the detail")
        assertEquals(5, dwell.dwell("gradle cache"))
    }

    @Test
    fun dwellFallsAsTokensPassAndATopicLeftAloneStartsAtTheTop() {
        val dwell = MemoryDwell(tokensPerStep = 100)
        repeat(3) { dwell.hit("keystore") }
        assertEquals(MemoryDepth.Summaries, dwell.depth("keystore"))
        dwell.consumeTokens(99)
        assertEquals(3, dwell.dwell("keystore"), "less than a step has not yet worn it down")
        dwell.consumeTokens(1)
        assertEquals(2, dwell.dwell("keystore"))
        assertEquals(MemoryDepth.Phrases, dwell.depth("keystore"))
        dwell.consumeTokens(200)
        assertEquals(0, dwell.dwell("keystore"))
        assertEquals(MemoryDepth.Gist, dwell.hit("keystore"), "back at the top of the ladder")
    }

    @Test
    fun topicsAreSeparateAndTagSpellingsAreOne() {
        val dwell = MemoryDwell(tokensPerStep = 100)
        dwell.hit("#gradle-cache")
        assertEquals(MemoryDepth.Phrases, dwell.hit("Gradle Cache"))
        assertEquals(MemoryDepth.Gist, dwell.hit("keystore"))
        assertEquals(0, dwell.dwell("never mentioned"))
    }
}
