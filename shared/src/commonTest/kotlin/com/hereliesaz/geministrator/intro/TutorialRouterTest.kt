package com.hereliesaz.geministrator.intro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TutorialRouterTest {
    @Test
    fun theSeededGoalIsABroadTutorial() {
        assertEquals(TutorialScope.Broad, TutorialRouter.classify("Teach me how to use the Aive"))
        assertEquals(TutorialScope.Broad, TutorialRouter.classify("  teach me the ins and outs of this app"))
    }

    @Test
    fun aNarrowRequestIsFocused() {
        assertEquals(TutorialScope.Focused, TutorialRouter.classify("Teach me how the Story works"))
        assertEquals(TutorialScope.Focused, TutorialRouter.classify("Show me the Nanny"))
    }

    @Test
    fun aCodingGoalIsNeverATutorial() {
        assertNull(TutorialRouter.classify("Explain the login code"))
        assertNull(TutorialRouter.classify("Teach me Kotlin coroutines"))
        assertNull(TutorialRouter.classify("Fix the build"))
        assertNull(TutorialRouter.classify(""))
    }
}
