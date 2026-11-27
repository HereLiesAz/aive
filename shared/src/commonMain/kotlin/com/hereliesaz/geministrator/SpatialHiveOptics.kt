package com.hereliesaz.geministrator

/** The focus plane follows the rendered gaze, not the nucleus or the original static site. */
internal data class SpatialOptics(val focusDepth: Float, val aperture: Float)

/** A wider focus range in the memory overview keeps the association network legible. */
internal fun SpatialRenderCamera.optics(): SpatialOptics {
    val depth=(gaze-eye).length()
    return SpatialOptics(if(depth.isFinite()) depth.coerceAtLeast(.1f) else 1f, if(memory) .012f else .065f)
}

/** Fullscreen triangle, requiring no extra mesh or vertex buffer. */
internal const val SPATIAL_OPTICS_VERTEX_SHADER = """#version 300 es
precision highp float;
out vec2 uv;
void main(){
    uv=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));
    gl_Position=vec4(uv*2.-1.,0.,1.);
}"""

/**
 * Depth-aware defocus gather on the GPU scene's color and depth attachments. Depth reconstructs
 * eye-space Z from SPATIAL_VERTEX_SHADER's perspective projection. The sharp focus band is
 * deliberately narrow; closer defocused surfaces can bleed over distant ones, not vice versa.
 * This is screen-space aperture blur, not a physically traced lens. Work is bounded per pixel.
 */
internal const val SPATIAL_OPTICS_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec2 uv;
uniform sampler2D sceneColor,sceneDepth;
uniform float focusDepth,aperture;
out vec4 frag;
float eyeDepth(vec2 p){
    float ndc=texture(sceneDepth,p).r*2.-1.;
    return .20002/max(.0002,1.0002-ndc);
}
float coc(float z,float height,float limit){
    return min(limit,aperture*height*max(0.,abs(z-focusDepth)-.14)/max(.1,z));
}
void main(){
    vec2 size=vec2(textureSize(sceneColor,0));
    vec2 halfTexel=.5/size;
    float limit=clamp(size.y*.045,5.,32.);
    float centerZ=eyeDepth(uv),centerRadius=coc(centerZ,size.y,limit);
    vec3 total=texture(sceneColor,uv).rgb;
    float weight=1.;
    for(int i=0;i<24;i++){
        float q=(float(i)+.5)/24.;
        float radius=limit*pow(q,1.5);
        float angle=float(i)*2.39996323;
        vec2 p=clamp(uv+vec2(cos(angle),sin(angle))*radius/size,halfTexel,1.-halfTexel);
        float z=eyeDepth(p),sampleRadius=coc(z,size.y,limit);
        float footprint=z<centerZ-.12?sampleRadius:centerRadius;
        if(footprint<.35)continue;
        float w=1.-smoothstep(max(0.,footprint-.75),footprint+.75,radius);
        total+=texture(sceneColor,p).rgb*w;
        weight+=w;
    }
    frag=vec4(total/weight,1.);
}"""
