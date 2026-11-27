package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class SpatialOrganismMorphologyTest {
    @Test fun recoveredFamiliesRetainDistinctSilhouettesAndSeedOrder() {
        assertEquals(SpatialMorphology.Knotted,spatialMorphology(0))
        assertEquals(SpatialMorphology.Canopy,spatialMorphology(3))
        assertEquals(SpatialMorphology.Elongated,spatialMorphology(-1))
        assertEquals(7,(0..15).map(::spatialMorphology).toSet().size)
        assertTrue(spatialMorphology(3).shape.x > 3f*spatialMorphology(3).shape.y)
        assertTrue(spatialMorphology(2).shape.y > 2f*spatialMorphology(2).shape.x)
    }

    @Test fun tissueHasWideRootsAndNarrowAsymmetricNeck() {
        assertEquals(1f,spatialNeuriteProfile(0f,1f),.0001f)
        assertEquals(1f,spatialNeuriteProfile(1f,1f),.0001f)
        assertTrue(spatialNeuriteProfile(.5f,1f) < .4f)
        assertNotEquals(spatialNeuriteProfile(.2f,1f),spatialNeuriteProfile(.8f,1f))
    }

    @Test fun liveMeshConsumesTheMorphologyAndRemainsDeterministic() {
        fun mesh(seed: Int): FloatArray {
            val b=SpatialRenderBody("creature",SpatialPoint.Origin,seed,"Creature","Pending",0f,1f)
            return SpatialRenderFrame(listOf(b),emptyList(),emptyList()).triangles()
        }
        val compact=mesh(1)
        val canopy=mesh(3)
        assertTrue(compact.contentEquals(mesh(1)))
        assertTrue(canopy.all(Float::isFinite))
        assertTrue(!compact.contentEquals(canopy))
        // The first 24*48*6 vertices are the soma, before roots, eyes and hairs.
        val soma=canopy.take(24*48*6*9)
        val x=(0 until soma.size step 9).map { soma[it] }
        val y=(1 until soma.size step 9).map { soma[it] }
        assertTrue(x.max()-x.min() > 2f*(y.max()-y.min()))
    }
}
