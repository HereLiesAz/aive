package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpatialHiveOpticsTest {
    @Test fun equalCameraRangeRatherThanEqualDepthOrElementDistance() {
        val camera=SpatialRenderCamera(SpatialPoint(0f,0f,0f),SpatialPoint(0f,0f,5f),false)
        val optics=camera.optics()
        assertEquals(5f,optics.focusDepth)
        assertEquals(0f,optics.blurAt(SpatialPoint(3f,0f,4f),1f))
        assertEquals(0f,optics.blurAt(SpatialPoint(0f,0f,5f),4f))
        // At focal range 5, range 4 has positive 1/4 - 1/5 = .05; range 6
        // has negative 1/6 - 1/5 = -1/30. Gain is 25 * .065 * 6 = 9.75.
        assertEquals(.4875f,optics.blurAt(SpatialPoint(0f,0f,4f),1f),.0001f)
        assertEquals(-.325f,optics.blurAt(SpatialPoint(0f,0f,6f),1f),.0001f)
        assertTrue(optics.blurAt(SpatialPoint(3f,0f,5f),1f)<0f)
        assertEquals(-.06f,camera.copy(memory=true).optics().blurAt(SpatialPoint(0f,0f,6f),1f),.0001f)
    }

    @Test fun multiplierAndMovingCameraChangeDefocusIndependently() {
        val camera=SpatialRenderCamera(SpatialPoint.Origin,SpatialPoint(0f,0f,5f),false)
        val point=SpatialPoint(0f,0f,6f)
        assertEquals(0f,camera.optics().blurAt(point,0f))
        assertEquals(-.1625f,camera.optics().blurAt(point,.5f),.0001f)
        assertEquals(-.65f,camera.optics().blurAt(point,2f),.0001f)
        // An eye halfway between two anchors puts both at the same camera range.
        assertEquals(0f,camera.copy(eye=SpatialPoint(0f,0f,5.5f)).optics().blurAt(point,2f))
        assertEquals(1f,spatialBlurScale(Float.NaN))
        val next=advanceSpatialFocus(SpatialPoint.Origin,point,.1f,false)
        assertTrue(next.z in 2.35f..2.37f)
        assertEquals(point,advanceSpatialFocus(SpatialPoint.Origin,point,.1f,true))
    }

    @Test fun entireCreatureSharesAnchorAndIndependentBlurScale() {
        val site=SpatialPoint(2f,3f,4f)
        val body=SpatialRenderBody("focus",site,3,"Creature","Pending",0f,1f,.25f)
        val vertices=SpatialRenderFrame(listOf(body),emptyList(),emptyList()).triangles()
        assertTrue(vertices.isNotEmpty() && vertices.size%39==0)
        for(i in 0 until vertices.size step 13) {
            assertEquals(2f,vertices[i+9]);assertEquals(3f,vertices[i+10]);assertEquals(4f,vertices[i+11])
            assertEquals(.25f,vertices[i+12])
        }
        val line=SpatialRenderCord(listOf(SpatialPoint.Origin,SpatialPoint(0f,0f,2f)),.1f,"Dependency",2f)
        val cord=SpatialRenderFrame(emptyList(),emptyList(),listOf(line)).triangles()
        assertEquals(setOf(0f,2f),(0 until cord.size step 13).map { cord[it+11] }.toSet())
        assertTrue((0 until cord.size step 13).all { cord[it+12]==2f })
    }

    @Test fun selectedMovingElementRetainsOpticalFocusDuringManualAim() {
        val target=SpatialPoint(3f,0f,0f)
        val camera=SpatialCameraState().pan(SpatialPoint(1f,1f,0f)).renderCamera(1.3f,target)
        assertTrue(camera.focusPoint!=camera.gaze)
        assertEquals(0f,camera.optics().blurAt(spatialMotionPoint(target,1.3f),4f))
    }

    @Test fun reciprocalDefocusIsBoundedAndContinuousThroughFocus() {
        val camera=SpatialRenderCamera(SpatialPoint.Origin,SpatialPoint(0f,0f,5f),false)
        val optics=camera.optics()
        assertEquals(1f,optics.blurAt(SpatialPoint.Origin,1f))
        assertEquals(-1f,optics.blurAt(SpatialPoint(0f,0f,100f),4f))
        assertEquals(0f,optics.blurAt(SpatialPoint.Origin,0f))
        val near=optics.blurAt(SpatialPoint(0f,0f,4.999f),1f)
        val far=optics.blurAt(SpatialPoint(0f,0f,5.001f),1f)
        assertTrue(near in 0f.. .001f && far in -.001f..0f)
        assertEquals(0f,camera.copy(focusPoint=SpatialPoint.Origin).optics().blurAt(SpatialPoint.Origin,1f))
    }
}
