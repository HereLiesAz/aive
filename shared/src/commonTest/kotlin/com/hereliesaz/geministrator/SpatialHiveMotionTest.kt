package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpatialHiveMotionTest {
    @Test fun restAndNucleusCenterAreInvariant() {
        val p=SpatialPoint(2f,-3f,4f)
        assertEquals(p,spatialMotionPoint(p,0f))
        assertEquals(SpatialPoint.Origin,spatialMotionPoint(SpatialPoint.Origin,1.7f))
        assertEquals(0f,advanceSpatialMotion(1f,2f,true))
        assertEquals(1f,advanceSpatialMotion(1f,Float.NaN,false))
    }

    @Test fun clockWrapAndLocalMotionRemainContinuousAndBounded() {
        val p=SpatialPoint(3f,1f,-2f)
        val period=2f*PI.toFloat()
        val before=spatialMotionPoint(p,period-.00001f)
        val after=spatialMotionPoint(p,.00001f)
        assertTrue((before-after).length()<.001f)
        val a=spatialMotionPoint(p,1.3f)-p
        val q=SpatialPoint(-3f,2f,1f)
        val b=spatialMotionPoint(q,1.3f)-q
        assertNotEquals(a,b)
        assertTrue(a.length()>.001f && b.length()>.001f)
        for (i in 0..128) {
            val moved=spatialMotionPoint(p,i*period/128f)
            assertTrue((moved-p).length()<.5f)
        }
        assertEquals(.5f,advanceSpatialMotion(.5f,64f,false),.00001f)
    }

    @Test fun nearbyTissueSamplesDoNotJumpAcrossTheField() {
        val a=SpatialPoint(2f,3f,4f)
        val b=SpatialPoint(2.0001f,3f,4f)
        assertTrue((spatialMotionPoint(a,2f)-spatialMotionPoint(b,2f)).length()<.001f)
    }

    @Test fun pickingAndAutomaticFocusUseMovingWorld() {
        val body=SpatialRenderBody("spore",SpatialPoint(2f,0f,0f),1,"Spore","Phrase",0f,.015f)
        val frame=SpatialRenderFrame(emptyList(),listOf(body),emptyList())
        val phase=1.3f
        val center=spatialMotionPoint(body.position,phase)
        assertTrue((center-body.position).length()>.03f)
        val camera=SpatialRenderCamera(center+SpatialPoint(0f,0f,5f),center,true,phase)
        assertEquals("spore",frame.pick(camera,0f,0f,1f)?.id)
        assertEquals(null,frame.pick(camera.copy(motionPhase=0f),0f,0f,1f))
        val state=SpatialCameraState().focus(body.position,true).advance(1f,true)
        assertEquals(center,state.renderCamera(phase).gaze)
        val manual=state.orbit(15f,0f)
        assertEquals(manual.pose.gaze,manual.renderCamera(phase).gaze)
    }
}
