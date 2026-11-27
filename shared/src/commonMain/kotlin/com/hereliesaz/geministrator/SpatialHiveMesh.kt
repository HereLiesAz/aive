package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One portable triangle stream: position, normal, RGB, nine floats per vertex. */
internal const val SPATIAL_VERTEX_FLOATS = 9
internal const val SPATIAL_FOCAL_SCALE = .52f

/** Shared rendering basis and ray tests use the same perspective as both GPU backends. */
internal data class SpatialViewBasis(val eye: SpatialPoint, val forward: SpatialPoint, val right: SpatialPoint, val up: SpatialPoint)

internal fun SpatialRenderCamera.basis(): SpatialViewBasis {
    val forward = (gaze - eye).unit().takeUnless { it == SpatialPoint.Origin } ?: SpatialPoint(0f, 0f, -1f)
    val reference = if (abs(forward.y) > .999f) SpatialPoint(0f, 0f, 1f) else SpatialPoint(0f, 1f, 0f)
    val right = forward.cross(reference).unit()
    return SpatialViewBasis(eye, forward, right, right.cross(forward))
}

private fun dot(a: SpatialPoint, b: SpatialPoint): Float = a.x*b.x + a.y*b.y + a.z*b.z

/** Nearest positive intersection with a selectable organism's bounding sphere. */
internal fun SpatialRenderFrame.pick(camera: SpatialRenderCamera, x: Float, y: Float, aspect: Float): SpatialRenderBody? {
    if (!x.isFinite() || !y.isFinite() || !aspect.isFinite() || aspect <= 0f) return null
    val view = camera.basis()
    val ray = (view.forward + view.right*(x*aspect*SPATIAL_FOCAL_SCALE) + view.up*(y*SPATIAL_FOCAL_SCALE)).unit()
    var nearest = Float.POSITIVE_INFINITY
    var hit: SpatialRenderBody? = null
    val candidates = if (camera.memory) spores else bodies + spores
    for (body in candidates) {
        val d = body.position - view.eye
        val t = dot(d,ray)
        val disc = body.radius*body.radius - dot(d,d) + t*t
        if (disc < 0f) continue
        val near = t - sqrt(disc)
        val far = t + sqrt(disc)
        val at = if (near > 0f) near else far
        if (at > 0f && at < nearest) { nearest = at; hit = body }
    }
    return hit
}

/** Builds actual depth-tested world geometry, independent of camera, viewport and runtime. */
internal fun SpatialRenderFrame.triangles(): FloatArray = SpatialMeshBuilder().apply {
    cords.forEach { cord -> tube(cord.points, cord.width, cordTint(cord.kind)) }
    (bodies + spores).forEach(::organism)
}.finish()

private fun rgb(r: Float, g: Float, b: Float) = SpatialPoint(r,g,b)

private val bodyPalette = listOf(rgb(.12f,.62f,.57f),rgb(.28f,.42f,.85f),rgb(.55f,.24f,.75f),rgb(.12f,.67f,.83f),rgb(.8f,.39f,.2f))

private fun bodyTint(b: SpatialRenderBody): SpatialPoint = when {
    b.form == "Nucleus" -> rgb(.95f,.33f,.075f)
    b.form == "Spore" -> when (b.state) {
        "Source" -> rgb(.32f,.66f,.8f)
        "Context" -> rgb(.1f,.85f,.65f)
        "Tag" -> rgb(.95f,.6f,.18f)
        "Phrase" -> rgb(.57f,.36f,.9f)
        "Summary" -> rgb(.4f,.7f,.95f)
        "Category" -> rgb(.9f,.36f,.3f)
        "Generalization" -> rgb(.9f,.7f,.32f)
        "Contrast" -> rgb(.95f,.28f,.55f)
        "Keeper" -> rgb(.95f,.85f,.53f)
        "Habit" -> rgb(.2f,.9f,.4f)
        "Procedure" -> rgb(.28f,.7f,.95f)
        else -> rgb(.2f,.7f,.65f)
    }
    b.state == "Failed" || b.form == "WitheredEgg" -> rgb(.5f,.12f,.18f)
    b.state == "Gate" -> rgb(.9f,.55f,.15f)
    else -> bodyPalette[(b.seed.toUInt() % bodyPalette.size.toUInt()).toInt()]
}

private fun cordTint(kind: String): SpatialPoint = when (kind) {
    "Neural" -> rgb(.15f,.54f,.62f)
    "Exploratory" -> rgb(.2f,.48f,.37f)
    "Reticulate" -> rgb(.32f,.3f,.62f)
    "Cord" -> rgb(.7f,.27f,.16f)
    "Memory" -> rgb(.28f,.58f,.34f)
    "Dependency" -> rgb(.18f,.36f,.5f)
    "Transfer" -> rgb(1f,.55f,.18f)
    else -> rgb(.15f,.5f,.46f)
}

private class SpatialMeshBuilder {
    private var data = FloatArray(8192)
    private var size = 0
    private val pi = PI.toFloat()

    fun finish(): FloatArray = data.copyOf(size)

    private fun vertex(p: SpatialPoint, n: SpatialPoint, c: SpatialPoint) {
        if (size + 9 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = p.x; data[size++] = p.y; data[size++] = p.z
        data[size++] = n.x; data[size++] = n.y; data[size++] = n.z
        data[size++] = c.x; data[size++] = c.y; data[size++] = c.z
    }

    private fun triangle(a: Sample,b: Sample,c: Sample,color: SpatialPoint) {
        vertex(a.p,a.n,color); vertex(b.p,b.n,color); vertex(c.p,c.n,color)
    }

    private data class Sample(val p: SpatialPoint,val n: SpatialPoint)

    private fun sphere(center: SpatialPoint,radius: Float,color: SpatialPoint,seed: Int,form: String,detail: Int = 18) {
        val unsigned = seed.toUInt()
        val phase = (unsigned % 628u).toFloat()/100f
        val lobes = if (form == "Nucleus") 5 else 3 + (unsigned % 4u).toInt()
        val shape = form in listOf("Nucleus","Creature","ProgrammaticNeuron")
        val squash = if (shape && form != "Nucleus") .7f + ((seed ushr 8) and 255)/510f else 1f
        fun sample(i: Int,j: Int): Sample {
            val phi = pi*i/detail
            val theta = pi*j/detail
            val n = SpatialPoint(sin(phi)*cos(theta),cos(phi),sin(phi)*sin(theta))
            val ripple = if (shape) 1f+.13f*sin(theta*lobes+phase)*sin(phi*3f)+.09f*cos(phi*5f+theta*2f+phase) else 1f
            return Sample(center+SpatialPoint(n.x,n.y*squash,n.z)*(radius*ripple),n)
        }
        for (i in 0 until detail) for (j in 0 until detail*2) {
            val a = sample(i,j); val b = sample(i+1,j); val c = sample(i+1,j+1); val d = sample(i,j+1)
            triangle(a,b,c,color); triangle(a,c,d,color)
        }
    }

    /** Transport one ring frame along the whole curve, sharing joints rather than leaving cracks. */
    fun tube(points: List<SpatialPoint>, width: Float, color: SpatialPoint, sides: Int = 7) {
        if (width <= 0f || !width.isFinite()) return
        val path = mutableListOf<SpatialPoint>()
        for (p in points) if (p.finite() && (path.isEmpty() || (p-path.last()).length() > .00001f)) path += p
        if (path.size < 2) return
        var right = SpatialPoint.Origin
        val rings = path.mapIndexed { i,p ->
            val incoming = if (i > 0) (p-path[i-1]).unit() else SpatialPoint.Origin
            val outgoing = if (i < path.lastIndex) (path[i+1]-p).unit() else SpatialPoint.Origin
            val tangent = (incoming+outgoing).unit().takeUnless { it == SpatialPoint.Origin } ?: outgoing
            val projected = (right-tangent*dot(right,tangent)).unit()
            right = if (projected != SpatialPoint.Origin) projected else tangent.cross(
                if (abs(tangent.y) > .9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()
            val up = right.cross(tangent)
            List(sides) { j ->
                val angle = 2f*pi*j/sides
                val n = right*cos(angle)+up*sin(angle)
                Sample(p+n*width,n)
            }
        }
        for (i in 1 until rings.size) for (j in 0 until sides) {
            val k = (j+1)%sides
            val a = rings[i-1][j]; val b = rings[i][j]; val c = rings[i][k]; val d = rings[i-1][k]
            triangle(a,b,c,color); triangle(a,c,d,color)
        }
    }

    fun organism(b: SpatialRenderBody) {
        val c = b.position; val color = bodyTint(b); val r = b.radius; val seed = b.seed.toUInt()
        sphere(c,r,color,b.seed,b.form,if (b.form == "Spore") 7 else 18)
        if (b.form !in listOf("Nucleus","Creature","ProgrammaticNeuron")) return
        val count = if (b.form == "Nucleus") 12 else 5+(seed%9u).toInt()
        for (k in 0 until count) {
            val angle = k*2.399963f+(seed%100u).toFloat()/100f
            val y = 1f-2f*(k+.5f)/count
            val radial = sqrt((1f-y*y).coerceAtLeast(0f))
            val d = SpatialPoint(cos(angle)*radial,y,sin(angle)*radial)
            val a = c+d*(r*.88f); val e = c+d*(r*(1.4f+(seed%7u).toFloat()*.05f))
            tube(listOf(a,a+d*(r*.22f),e),r*.035f,color,5)
            if (b.form != "Nucleus" && k < 2+(seed%3u).toInt()) {
                val eye = c+d*(r*.94f)
                sphere(eye,r*.22f,rgb(.56f,.91f,.8f),b.seed,"Eye",8)
                sphere(eye+d*(r*.17f),r*.105f,rgb(.016f,.035f,.04f),b.seed,"Eye",8)
            }
            if (b.form == "Nucleus") sphere(c+d*(r*.68f),r*.32f,rgb(.88f,.21f,.055f),b.seed+k,"Nucleus",10)
        }
    }
}

/** OpenGL ES 3 / WebGL2 share the same projection and material shaders. */
internal const val SPATIAL_VERTEX_SHADER = """#version 300 es
precision highp float;
in vec3 p; in vec3 n; in vec3 c;
uniform vec3 eye,forward,right,up;
uniform float aspect;
out vec3 normal,color,world;
void main(){
    vec3 d=p-eye; float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)/(.52*aspect),dot(d,up)/.52,1.0002*z-.20002,z);
    normal=n; color=c; world=p;
}"""

internal const val SPATIAL_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 normal,color,world;
uniform vec3 eye;
out vec4 frag;
void main(){
    vec3 n=normalize(normal),v=normalize(eye-world),l=normalize(vec3(-.4,.9,.7));
    float diffuse=max(dot(n,l),0.),rim=pow(1.-abs(dot(n,v)),2.),spec=pow(max(dot(n,normalize(l+v)),0.),42.);
    vec3 lit=color*(.48+.76*diffuse+.48*rim)+vec3(.55,.84,.92)*spec*.8;
    float fog=exp(-length(eye-world)*.026);
    frag=vec4(mix(vec3(.012,.045,.047),lit,fog),1.);
}"""
