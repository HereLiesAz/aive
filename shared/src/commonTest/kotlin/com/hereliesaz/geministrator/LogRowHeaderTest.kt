package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class LogRowHeaderTest {
    @Test
    fun aRoleShowsEvenWhenTheLineHasNoScope() {
        assertEquals("12:00:00  ·  Architect", logRowHeader("12:00:00", "", "Architect"))
    }

    @Test
    fun scopeAndRoleAreEachOptional() {
        assertEquals("12:00:00", logRowHeader("12:00:00", "", null))
        assertEquals("12:00:00  ·  plan", logRowHeader("12:00:00", "plan", null))
        assertEquals("12:00:00  ·  plan  ·  Architect", logRowHeader("12:00:00", "plan", "Architect"))
    }
}
