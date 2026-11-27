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
        // Both points are one world unit away in camera range, on opposite sides of focus.
        assertEquals(.3354f,optics.blurAt(SpatialPoint(0f,0f,4f),1f),.0001f)
        assertEquals(.3354f,optics.blurAt(SpatialPoint(0f,0f,6f),1f),.0001f)
        assertTrue(optics.blurAt(SpatialPoint(3f,0f,5f),1f)>0f)
        assertTrue(camera.copy(memory=true).optics().blurAt(SpatialPoint(0f,0f,6f),1f)<.3354f)
    }

    @Test fun multiplierAndMovingCameraChangeDefocusIndependently() {
        val camera=SpatialRenderCamera(SpatialPoint.Origin,SpatialPoint(0f,0f,5f),false)
        val point=SpatialPoint(0f,0f,6f)
        assertEquals(0f,camera.optics().blurAt(point,0f))
        assertEquals(.1677f,camera.optics().blurAt(point,.5f),.0001f)
        assertEquals(.6708f,camera.optics().blurAt(point,2f),.0001f)
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
}
