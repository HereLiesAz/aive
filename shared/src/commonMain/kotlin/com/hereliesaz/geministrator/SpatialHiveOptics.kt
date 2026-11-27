package com.hereliesaz.geministrator

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

/**
 * Signed, normalized circle of confusion: positive in front of focus, negative behind it.
 * Reciprocal camera range gives the lens-like asymmetry. Multiplying by squared focal range
 * preserves the previous artist-tuned local blur slope as focus moves through the colony.
 * Equal camera ranges remain sharp; zero multiplier disables defocus. The .1 minimum
 * matches the projection's near plane and keeps coincident camera/anchor positions finite.
 */
internal fun SpatialOptics.blurAt(anchor: SpatialPoint, multiplier: Float): Float {
    if(!anchor.finite() || !eye.finite() || !focusDepth.isFinite()) return 0f
    val scale=spatialBlurScale(multiplier)
    if(scale==0f) return 0f
    val focal=focusDepth.coerceAtLeast(.1f)
    val range=(anchor-eye).length().coerceAtLeast(.1f)
    return ((focal-range)*(focal/range)*aperture*6f*scale).coerceIn(-1f,1f)
}

/** Equivalent to (1/range - 1/focal) * focal^2, without reciprocal cancellation near focus. */
internal const val SPATIAL_ELEMENT_BLUR_GLSL = """
float elementBlur(vec3 anchor,vec3 eye,float focusDepth,float aperture,float multiplier){
    float focal=max(.1,focusDepth);
    float range=max(.1,distance(anchor,eye));
    return clamp((focal-range)*(focal/range)*aperture*6.*multiplier,-1.,1.);
}
"""

/** RGBA8 alpha uses byte 128 for exact zero, 1 for maximum far blur, 255 for maximum near blur. */
internal const val SPATIAL_FAR_CLEAR_ALPHA = 1f/255f

/** Fullscreen triangle, requiring no extra mesh or vertex buffer. */
internal const val SPATIAL_OPTICS_VERTEX_SHADER = """#version 300 es
precision highp float;
out vec2 uv;
void main(){
    uv=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
    gl_Position=vec4(uv*2.-1.,0.,1.);
}"""

/**
 * Resolve signed defocus using depth-tested, matched color/depth samples.
 * The far layer gathers only into an already defocused center, while a nearer defocused
 * silhouette can contribute coverage over the farther layer. Background highlights get
 * a restrained bokeh emphasis and a radially compressed aperture toward frame edges.
 * This is a bounded screen-space approximation, not a traced lens or HDR bloom.
 */
internal const val SPATIAL_OPTICS_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D sceneColor,sceneDepth;
out vec4 frag;
float eyeDepth(ivec2 p){
    float ndc=texelFetch(sceneDepth,p,0).r*2.-1.;
    return .20002/max(.0002,1.0002-ndc);
}
float signedCoc(vec4 sampleColor){
    return clamp((sampleColor.a*255.-128.)/127.,-1.,1.);
}
float diskWeight(float radius,float footprint){
    return 1.-smoothstep(max(0.,footprint-.75),footprint+.75,radius);
}
vec3 farHighlight(vec3 color,float defocus){
    float luminance=dot(color,vec3(.2126,.7152,.0722));
    return color*(1.+.18*smoothstep(.55,.95,luminance)*defocus);
}
void main(){
    ivec2 extent=textureSize(sceneColor,0);
    vec2 size=vec2(extent);
    ivec2 centerPixel=clamp(ivec2(uv*size),ivec2(0),extent-1);
    vec4 center=texelFetch(sceneColor,centerPixel,0);
    float centerZ=eyeDepth(centerPixel),centerCoc=signedCoc(center);
    float limit=clamp(size.y*.045,5.,32.);
    float centerRadius=abs(centerCoc)*limit*(centerCoc>0.?1.15:1.);
    vec2 edge=uv*2.-1.;
    vec2 radial=length(edge)>.001?normalize(edge):vec2(1.,0.);
    float compression=1.-.35*smoothstep(.35,1.25,length(edge));
    vec3 base=farHighlight(center.rgb,max(0.,-centerCoc));
    float baseWeight=1.;
    vec3 foreground=vec3(0.);
    float foregroundWeight=0.;
    for(int i=0;i<32;i++){
        float q=(float(i)+.5)/32.;
        float radius=limit*1.15*pow(q,1.5);
        float angle=float(i)*2.39996323;
        vec2 offset=vec2(cos(angle),sin(angle))*radius;
        // Only the far gather uses cat-eye compression; near coverage keeps a circular footprint.
        vec2 farOffset=offset-radial*dot(offset,radial)*(1.-compression);
        vec2 baseOffset=centerCoc<0.?farOffset:offset;
        ivec2 p=clamp(ivec2(vec2(centerPixel)+.5+baseOffset),ivec2(0),extent-1);
        vec4 sampleColor=texelFetch(sceneColor,p,0);
        float z=eyeDepth(p),sampleCoc=signedCoc(sampleColor);
        // A sharp center never gathers background. A far center does not gather nearer surfaces.
        // A near center may reveal farther colors as its defocused silhouette softens.
        if(centerRadius>=.35 && (z>=centerZ-.12 || abs(z-centerZ)<.12)){
            float w=diskWeight(radius,centerRadius);
            if(centerCoc<0. && sampleCoc>=0.)w=0.;
            base+=farHighlight(sampleColor.rgb,max(0.,-sampleCoc))*w;
            baseWeight+=w;
        }
        ivec2 nearPixel=clamp(ivec2(vec2(centerPixel)+.5+offset),ivec2(0),extent-1);
        vec4 nearColor=texelFetch(sceneColor,nearPixel,0);
        float nearCoc=signedCoc(nearColor),nearZ=eyeDepth(nearPixel);
        if(nearCoc>0. && nearZ<centerZ-.12){
            float footprint=nearCoc*limit*1.15;
            if(footprint>=.35){
                float w=diskWeight(radius,footprint);
                foreground+=nearColor.rgb*w;
                foregroundWeight+=w;
            }
        }
    }
    vec3 resolved=base/baseWeight;
    if(foregroundWeight>0.){
        float coverage=clamp(foregroundWeight/12.,0.,1.);
        resolved=mix(resolved,foreground/foregroundWeight,coverage);
    }
    frag=vec4(resolved,1.);
}"""
