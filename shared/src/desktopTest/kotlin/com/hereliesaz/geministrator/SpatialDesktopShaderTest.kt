package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialDesktopShaderTest {
    @Test fun adaptsOnlyEsHeaderAndPrecision() {
        val sample = "#version 300 es\nprecision highp float;\nvoid main(){ gl_Position=vec4(1.0); }"
        assertEquals("#version 150\nvoid main(){ gl_Position=vec4(1.0); }", spatialDesktopShader(sample))
        assertFailsWith<IllegalArgumentException> { spatialDesktopShader("#version 120\n") }
    }

    @Test fun liveShadersKeepTheirProjectionAndMaterialExpressions() {
        val vertex = spatialDesktopShader(SPATIAL_VERTEX_SHADER)
        val fragment = spatialDesktopShader(SPATIAL_FRAGMENT_SHADER)
        assertTrue(vertex.startsWith("#version 150\n"))
        assertTrue(fragment.startsWith("#version 150\n"))
        assertTrue(vertex.contains("gl_Position=vec4(dot(d,right)/(.52*aspect)"))
        assertTrue(fragment.contains("frag=vec4(mix("))
        assertFalse(vertex.contains("precision highp"))
        assertFalse(fragment.contains("precision highp"))
    }

    @Test fun opticalPassKeepsDepthTextureAndFullscreenTriangle() {
        val vertex=spatialDesktopShader(SPATIAL_OPTICS_VERTEX_SHADER)
        val fragment=spatialDesktopShader(SPATIAL_OPTICS_FRAGMENT_SHADER)
        assertTrue(vertex.contains("gl_VertexID"))
        assertTrue(fragment.contains("texelFetch(sceneDepth,p,0)"))
        assertTrue(fragment.contains("texelFetch(sceneColor,centerPixel,0)"))
        assertTrue(fragment.contains("sampleColor.a*255.-128."))
        assertTrue(spatialDesktopShader(SPATIAL_VERTEX_SHADER).contains("(focal-range)*(focal/range)"))
        assertTrue(spatialDesktopShader(SPATIAL_FRAGMENT_SHADER).contains("(128.+127.*blurAmount)/255."))
        assertTrue(fragment.contains("nearCoc>0. && nearZ<centerZ-.12"))
        assertTrue(fragment.startsWith("#version 150\n"))
        assertFalse(fragment.contains("precision highp"))
    }

    @Test fun materialResponseAndGlowDoNotOverwriteSignedDefocus() {
        val vertex=spatialDesktopShader(SPATIAL_VERTEX_SHADER)
        val fragment=spatialDesktopShader(SPATIAL_FRAGMENT_SHADER)
        val optics=spatialDesktopShader(SPATIAL_OPTICS_FRAGMENT_SHADER)
        assertTrue(vertex.contains("materialResponse=material"))
        assertTrue(fragment.contains("lit+=pigment*emission"))
        assertTrue(fragment.contains("(128.+127.*blurAmount)/255."))
        assertTrue(optics.contains("resolved+=glow/max(1.,glowWeight)*.64"))
        assertTrue(optics.contains("frag=vec4(resolved,1.)"))
    }
}
