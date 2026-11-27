package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.math.abs

class SpatialHiveMeshTest {
    private fun body(id: String,z: Float,r: Float,form: String="Site") =
        SpatialRenderBody(id,SpatialPoint(0f,0f,z),11,form,"Pending",0f,r)

    @Test fun trianglesHaveVolumeAndFiniteNormals() {
        val frame=SpatialRenderFrame(listOf(body("site",0f,1f)),emptyList(),emptyList())
        val data=frame.triangles()
        assertTrue(data.isNotEmpty() && data.size%48==0)
        assertTrue(data.all(Float::isFinite))
        val z=(2 until data.size step 16).map { data[it] }
        assertTrue(z.min() < -.99f && z.max() > .99f)
        for (i in 0 until data.size step 16) {
            val n=data[i+3]*data[i+3]+data[i+4]*data[i+4]+data[i+5]*data[i+5]
            assertTrue(abs(n-1f)<.001f)
        }
    }

    @Test fun tubeHasSharedRingAtCornerAndNoZeroSegment() {
        val a=SpatialPoint(0f,0f,0f); val b=SpatialPoint(0f,0f,2f); val c=SpatialPoint(2f,0f,2f)
        val data=SpatialRenderFrame(emptyList(),emptyList(),listOf(SpatialRenderCord(listOf(a,b,b,c),.1f,"Dependency"))).triangles()
        assertEquals(2*7*2*3*16,data.size)
        val first=(0 until data.size/2 step 16).map { SpatialPoint(data[it],data[it+1],data[it+2]) }.toSet()
        val second=(data.size/2 until data.size step 16).map { SpatialPoint(data[it],data[it+1],data[it+2]) }.toSet()
        assertEquals(7,first.intersect(second).size)
    }

    @Test fun nearestRayHitAndMemoryContext() {
        val camera=SpatialRenderCamera(SpatialPoint(0f,0f,10f),SpatialPoint.Origin,false)
        val frame=SpatialRenderFrame(listOf(body("far",0f,1f),body("near",4f,.5f)),listOf(body("spore",-2f,.3f,"Spore")),emptyList())
        assertEquals("near",frame.pick(camera,0f,0f,1f)?.id)
        assertNull(frame.pick(camera,.9f,.9f,1f))
        assertEquals("spore",frame.pick(camera.copy(memory=true),0f,0f,1f)?.id)
        assertNull(frame.pick(camera,0f,0f,0f))
    }

    @Test fun opticalMaterialsAreBoundToActualPrimitivesNotPigmentOrExecutionState() {
        val nucleus=body("queen",0f,1f,"Nucleus")
        val data=SpatialRenderFrame(listOf(nucleus),emptyList(),emptyList()).triangles()
        val materials=(0 until data.size step 16).map { Triple(data[it+13],data[it+14],data[it+15]) }.toSet()
        assertTrue(Triple(.23f,.72f,0f) in materials)
        assertTrue(Triple(.29f,.24f,2.1f) in materials)
        assertTrue(Triple(.38f,.22f,0f) in materials)
        assertTrue(materials.all { it.first in 0f..1f && it.second in 0f..1f && it.third>=0f })
        val neuron=nucleus.copy(id="neuron",form="Creature",state="Running")
        val neuronData=SpatialRenderFrame(listOf(neuron),emptyList(),emptyList()).triangles()
        val neuronMaterials=(0 until neuronData.size step 16).map {
            Triple(neuronData[it+13],neuronData[it+14],neuronData[it+15])
        }.toSet()
        assertTrue(Triple(.09f,.16f,0f) in neuronMaterials)
        assertTrue(neuronMaterials.all { it.third==0f })
        val idle=SpatialRenderFrame(listOf(neuron.copy(state="Pending")),emptyList(),emptyList()).triangles()
        assertTrue((0 until idle.size step 16).all { idle[it+15]==neuronData[it+15] })
    }

    @Test fun onlyRecordedTransferCordReceivesTransferEmission() {
        val points=listOf(SpatialPoint.Origin,SpatialPoint(0f,0f,2f))
        val dependency=SpatialRenderFrame(emptyList(),emptyList(),listOf(SpatialRenderCord(points,.1f,"Dependency"))).triangles()
        val transfer=SpatialRenderFrame(emptyList(),emptyList(),listOf(SpatialRenderCord(points,.1f,"Transfer"))).triangles()
        assertTrue((0 until dependency.size step 16).all { dependency[it+15]==0f })
        assertTrue((0 until transfer.size step 16).all { transfer[it+15]==.85f })
    }
}
