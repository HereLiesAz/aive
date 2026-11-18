package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TutorialReplayTest {
    private val teach = "Teach me how to use the Aive"

    @Test
    fun theSourceLineShowsOnlyForALessonGoalWithNoSourceAndNoneBeingPicked() {
        assertTrue(tutorialNeedsSourceLine(teach, FeedGate.Provider, sourcePickedForKeeper = false))
        assertFalse(tutorialNeedsSourceLine(teach, FeedGate.Provider, sourcePickedForKeeper = true))
        assertFalse(tutorialNeedsSourceLine(teach, FeedGate.Open, sourcePickedForKeeper = false))
        assertFalse(tutorialNeedsSourceLine("Fix the build", FeedGate.Provider, sourcePickedForKeeper = false))
    }

    @Test
    fun aRequestIsTakenOnce() {
        TutorialRequests.pending = true
        assertTrue(TutorialRequests.take())
        assertFalse(TutorialRequests.take())
    }

    @Test
    fun aLiveHiveQueuesTheGoalAndNoHiveFillsTheForm() {
        assertEquals(TeachTarget.QueueOnHive, teachTarget(hasLiveRun = true))
        assertEquals(TeachTarget.FillLaunchForm, teachTarget(hasLiveRun = false))
    }
}
