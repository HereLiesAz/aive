package com.hereliesaz.geministrator

import kotlin.math.abs
import kotlin.math.exp

/** Focal element range measured from the camera, not distance between elements or eye-space Z. */
internal data class SpatialOptics(val eye: SpatialPoint, val focusDepth: Float, val aperture: Float)

/** Retain the wider focus range of the memory overview. */
internal fun SpatialRenderCamera.optics(): SpatialOptics {
    val distance = (focusPoint-eye).length()
    return SpatialOptics(eye, if(distance.isFinite()) distance else (gaze-eye).length(),
        if(memory) .012f else .065f)
}

/** Ease optical focus independently of camera aim. */
internal fun advanceSpatialFocus(from: SpatialPoint, to: SpatialPoint, seconds: Float, reduced: Boolean): SpatialPoint {
    if(!to.finite()) return from
    if(reduced || !from.finite() || (to-from).length()<.0001f) return to
    if(!seconds.isFinite() || seconds<=0f) return from
    return from+(to-from)*(1f-exp(-seconds*5f))
}

/** Zero stays sharp; one is the normal response. Each element independently controls this scale. */
internal fun spatialBlurScale(value: Float): Float = if(value.isFinite()) value.coerceIn(0f,4f) else 1f

/** Normalized circle of confusion for a deformed element anchor. Equal camera ranges stay equally sharp. */
internal fun SpatialOptics.blurAt(anchor: SpatialPoint, multiplier: Float): Float {
    if(!anchor.finite()) return 0f
    val difference=abs((anchor-eye).length()-focusDepth)
    return ((difference-.14f).coerceAtLeast(0f)*aperture*6f*spatialBlurScale(multiplier)).coerceIn(0f,1f)
}

/** GPU contract: absolute difference of camera ranges, multiplied by the element's own response. */
internal const val SPATIAL_ELEMENT_BLUR_GLSL = """
float elementBlur(vec3 anchor,vec3 eye,float focusDepth,float aperture,float multiplier){
    float difference=abs(distance(anchor,eye)-focusDepth);
    return clamp(max(0.,difference-.14)*aperture*6.*multiplier,0.,1.);
}
"""

/** Fullscreen triangle, requiring no extra mesh or vertex buffer. */
internal const val SPATIAL_OPTICS_VERTEX_SHADER = """#version 300 es
precision highp float;
out vec2 uv;
void main(){
    uv=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
    gl_Position=vec4(uv*2.-1.,0.,1.);
}"""

/** Resolve per-element defocus stored in color alpha; depth is only for occlusion ordering. */
internal const val SPATIAL_OPTICS_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D sceneColor,sceneDepth;
out vec4 frag;
float eyeDepth(vec2 p){
    float ndc=texture(sceneDepth,p).r*2.-1.;
    return .20002/max(.0002,1.0002-ndc);
}
float coc(vec2 p,float limit){
    return texture(sceneColor,p).a*limit;
}
void main(){
    vec2 size=vec2(textureSize(sceneColor,0));
    vec2 halfTexel=.5/size;
    float limit=clamp(size.y*.045,5.,32.);
    float centerZ=eyeDepth(uv),centerRadius=coc(uv,limit);
    vec3 total=texture(sceneColor,uv).rgb;
    float weight=1.;
    for(int i=0;i<24;i++){
        float q=(float(i)+.5)/24.;
        float radius=limit*pow(q,1.5);
        float angle=float(i)*2.39996323;
        vec2 p=clamp(uv+vec2(cos(angle),sin(angle))*radius/size,halfTexel,1.-halfTexel);
        float z=eyeDepth(p),sampleRadius=coc(p,limit);
        float footprint=z<centerZ-.12?sampleRadius:centerRadius;
        if(footprint<.35)continue;
        float w=1.-smoothstep(max(0.,footprint-.75),footprint+.75,radius);
        total+=texture(sceneColor,p).rgb*w;
        weight+=w;
    }
    frag=vec4(total/weight,1.);
}"""
