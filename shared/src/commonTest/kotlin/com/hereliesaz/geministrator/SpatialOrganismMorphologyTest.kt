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
        val soma=canopy.take(24*48*6*16)
        val x=(0 until soma.size step 16).map { soma[it] }
        val y=(1 until soma.size step 16).map { soma[it] }
        assertTrue(x.max()-x.min() > 2f*(y.max()-y.min()))
    }
    @Test fun irregularLidAndCurvedIrisAreRendered() {
        val a=spatialEyelidProfile(0f,1f)
        val b=spatialEyelidProfile(3.1415927f,1f)
        assertNotEquals(a,b)
        assertTrue(a.first in .7f..1f && b.first in .7f..1f)
        val creature=SpatialRenderBody("agent",SpatialPoint.Origin,3,"Creature","Pending",0f,1f)
        val data=SpatialRenderFrame(listOf(creature),emptyList(),emptyList()).triangles()
        val colors=(6 until data.size step 16).map { Triple(data[it],data[it+1],data[it+2]) }.toSet()
        assertTrue(Triple(.012f,.025f,.038f) in colors)
        assertTrue(colors.size>20)
    }

    @Test fun sculptPullsRootShouldersOutAndRecessesEyeSockets() {
        val x=SpatialPoint(1f,0f,0f)
        val y=SpatialPoint(0f,1f,0f)
        val shape=SpatialPoint(1f,1f,1f)
        val neutral=spatialSomaOffset(x,shape,3,.15f,0f,emptyList(),emptyList())
        val shoulder=spatialSomaOffset(x,shape,3,.15f,0f,listOf(x),emptyList())
        val socket=spatialSomaOffset(x,shape,3,.15f,0f,emptyList(),listOf(x))
        assertTrue(shoulder.x-neutral.x>.25f)
        assertTrue(neutral.x-socket.x>.17f)
        assertEquals(spatialSomaOffset(y,shape,3,.15f,0f,emptyList(),emptyList()),
            spatialSomaOffset(y,shape,3,.15f,0f,listOf(x),listOf(x)))
        assertEquals(1f,spatialRootProfile(0f,0f),.0001f)
        assertEquals(.025f,spatialRootProfile(1f,0f),.0001f)
        assertTrue(spatialRootProfile(.75f,0f)<.25f)
    }

    @Test fun liveSculptHasUnitNormalsAndBranchingHairGeometry() {
        val creature=SpatialRenderBody("weaver",SpatialPoint.Origin,3,"Creature","Pending",0f,1f)
        val data=SpatialRenderFrame(listOf(creature),emptyList(),emptyList()).triangles()
        val somaVertices=24*48*6
        for(i in 0 until somaVertices*16 step 16) {
            val n=SpatialPoint(data[i+3],data[i+4],data[i+5])
            assertEquals(1f,n.length(),.001f)
        }
        // Canopy has 84 primary hairs; each fourth hair also has a four-segment fork.
        // Seven segments * four sides * six vertices, plus 21 four-segment branches.
        val hairVertices=(0 until data.size step 16).count {
            data[it+13]==.38f && data[it+14]==.22f && data[it+15]==0f
        }
        assertEquals(16128,hairVertices)
        assertTrue(data.all(Float::isFinite))
    }

    @Test fun dominantTaperedExtensionBelongsToSomeFamiliesOnly() {
        assertTrue(spatialMorphology(2).dominantAxon)
        assertTrue(spatialMorphology(3).dominantAxon)
        assertTrue(spatialMorphology(5).dominantAxon)
        assertTrue(!spatialMorphology(0).dominantAxon)
        assertTrue(!spatialMorphology(1).dominantAxon)
        assertTrue(!spatialMorphology(4).dominantAxon)
        assertTrue(!spatialMorphology(7).dominantAxon)

        // Measure the first root in the actual mesh: average the ten ring positions at
        // its base and tip. The first root follows the 24-by-48 soma grid.
        fun rootSpan(seed: Int): Float {
            val b=SpatialRenderBody("agent",SpatialPoint.Origin,seed,"Creature","Pending",0f,1f)
            val data=SpatialRenderFrame(listOf(b),emptyList(),emptyList()).triangles()
            val start=24*48*6*16
            fun ringCenter(segment: Int, tip: Boolean): SpatialPoint {
                var sum=SpatialPoint.Origin
                for(j in 0 until 10) {
                    val i=start+(segment*60+j*6+(if(tip) 1 else 0))*16
                    sum+=SpatialPoint(data[i],data[i+1],data[i+2])
                }
                return sum*.1f
            }
            return (ringCenter(15,true)-ringCenter(0,false)).length()
        }
        for(seed in listOf(2,3,5)) assertTrue(rootSpan(seed) in 1.64f..2.51f)
        for(seed in listOf(0,1,4,7)) assertTrue(rootSpan(seed) in .47f.. .97f)
    }
}
