package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class SpatialOrganismMorphologyTest {
    @Test fun prototypeFamiliesAreStableAndDistinct() {
        assertEquals(SpatialMorphologyFamily.Knotted,spatialOrganismMorphology(0).family)
        assertEquals(SpatialMorphologyFamily.Canopy,spatialOrganismMorphology(3).family)
        assertEquals(SpatialMorphologyFamily.Elongated,spatialOrganismMorphology(-1).family)
        assertEquals(7,(0..15).map { spatialOrganismMorphology(it).family }.toSet().size)
        val canopy=spatialOrganismMorphology(3)
        assertTrue(canopy.axes.x>canopy.axes.y*3f)
        assertEquals(1,canopy.stalks)
        assertEquals(2,canopy.eyes)
    }

    @Test fun neuritesHaveBroadAttachmentsAndNarrowNecks() {
        for(phase in listOf(0f,1.2f,3.5f,5.8f)) {
            assertEquals(1f,spatialNeuriteProfile(0f,phase),.00001f)
            assertEquals(1f,spatialNeuriteProfile(1f,phase),.00001f)
            assertTrue(spatialNeuriteProfile(.5f,phase) in .1f.. .5f)
        }
    }

    @Test fun eyelidsAreAsymmetric() {
        val a=spatialEyelidProfile(0f,1f)
        val b=spatialEyelidProfile(3.1415927f,1f)
        assertNotEquals(a,b)
        assertTrue(a.first in .7f..1f&&b.first in .7f..1f)
    }

    @Test fun liveMeshUsesDistinctAnatomyAndPigment() {
        fun mesh(seed: Int)=SpatialRenderFrame(listOf(
            SpatialRenderBody("agent",SpatialPoint.Origin,seed,"Creature","Pending",0f,1f)),
            emptyList(),emptyList()).triangles()
        val canopy=mesh(3)
        val knotted=mesh(0)
        assertTrue(canopy.size>100000&&knotted.size>100000)
        assertTrue(canopy.all(Float::isFinite)&&knotted.all(Float::isFinite))
        assertNotEquals(canopy.size,knotted.size)
        val colors=(6 until canopy.size step 9).map { Triple(canopy[it],canopy[it+1],canopy[it+2]) }.toSet()
        assertTrue(colors.size>20)
    }
}
