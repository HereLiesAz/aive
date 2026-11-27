package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialHiveOpticsTest {
    @Test fun focusTracksGazeRatherThanOrbitRadius() {
        val view=SpatialRenderCamera(SpatialPoint(0f,0f,10f),SpatialPoint(3f,0f,6f),false)
        assertEquals(5f,view.optics().focusDepth)
        assertEquals(10f,view.copy(gaze=SpatialPoint.Origin).optics().focusDepth)
        assertTrue(view.optics().aperture > view.copy(memory=true).optics().aperture)
    }

    @Test fun focusDistanceNeverBecomesZero() {
        val point=SpatialPoint(1f,2f,3f)
        assertEquals(.1f,SpatialRenderCamera(point,point,false).optics().focusDepth)
    }
}
