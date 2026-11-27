package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

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
        val radius = if (body.form == "Creature" || body.form == "ProgrammaticNeuron") body.radius * maxOf(spatialMorphology(body.seed).shape.x, spatialMorphology(body.seed).shape.y, spatialMorphology(body.seed).shape.z) else body.radius
        val disc = radius*radius - dot(d,d) + t*t
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
    cords.forEach { cord -> cord(cord) }
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
        val morph = spatialMorphology(seed)
        val lobes = if (form == "Nucleus") 7 else morph.lobes
        val shape = form in listOf("Nucleus","Creature","ProgrammaticNeuron")
        val scale = if (shape && form != "Nucleus") morph.shape else SpatialPoint(1f,1f,1f)
        val relief = if (form == "Nucleus") .26f else morph.relief
        fun sample(i: Int,j: Int): Sample {
            val phi = pi*i/detail
            val theta = pi*j/detail
            val n = SpatialPoint(sin(phi)*cos(theta),cos(phi),sin(phi)*sin(theta))
            val ripple = if (shape) 1f+relief*sin(theta*lobes+phase)*sin(phi*3f)+.09f*cos(phi*5f+theta*2f+phase)+.035f*sin(phi*17f+theta*13f+phase) else 1f
            return Sample(center+SpatialPoint(n.x*scale.x,n.y*scale.y,n.z*scale.z)*(radius*ripple),SpatialPoint(n.x/scale.x,n.y/scale.y,n.z/scale.z).unit())
        }
        for (i in 0 until detail) for (j in 0 until detail*2) {
            val a = sample(i,j); val b = sample(i+1,j); val c = sample(i+1,j+1); val d = sample(i,j+1)
            triangle(a,b,c,color); triangle(a,c,d,color)
        }
    }

    /** Transport one ring frame along the whole curve, sharing joints rather than leaving cracks. */
    fun tube(points: List<SpatialPoint>, width: Float, color: SpatialPoint, sides: Int = 7, profile: (Float) -> Float = { 1f }) {
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
                Sample(p+n*(width*profile(i.toFloat()/path.lastIndex)),n)
            }
        }
        for (i in 1 until rings.size) for (j in 0 until sides) {
            val k = (j+1)%sides
            val a = rings[i-1][j]; val b = rings[i][j]; val c = rings[i][k]; val d = rings[i-1][k]
            triangle(a,b,c,color); triangle(a,c,d,color)
        }
    }

    /** Decorative tissue is sculpted independently of logical dependency and transfer overlays. */
    fun cord(c: SpatialRenderCord) {
        if (c.kind in listOf("Memory","Transfer","Dependency")) {
            tube(c.points,c.width,cordTint(c.kind))
            return
        }
        val phase = (c.points.firstOrNull()?.let { it.x*2f+it.z*3f } ?: 0f)
        tube(c.points,c.width*2.1f,cordTint(c.kind),9) { spatialNeuriteProfile(it,phase) }
        if (c.points.size < 3) return
        for (strand in 0..1) {
            val path = c.points.mapIndexed { i,p ->
                val t = i.toFloat()/c.points.lastIndex
                val envelope = sin(pi*t)*c.width*.65f
                p+SpatialPoint(cos(t*19f+phase+strand*pi),sin(t*19f+phase+strand*pi),0f)*envelope
            }
            tube(path,c.width*.18f,if (strand==0) rgb(.72f,.22f,.09f) else rgb(.22f,.36f,.88f),5) { spatialNeuriteProfile(it,phase) }
        }
    }

    private fun direction(rng: Random): SpatialPoint {
        val y = rng.nextFloat()*2f-1f
        val angle = rng.nextFloat()*2f*pi
        val radial = sqrt((1f-y*y).coerceAtLeast(0f))
        return SpatialPoint(cos(angle)*radial,y,sin(angle)*radial)
    }

    /** Irregular ocular socket, raised iris and dark pupil share the same outward axis. */
    private fun eye(center: SpatialPoint,d: SpatialPoint,r: Float,seed: Int,membrane: SpatialPoint) {
        val phase = (seed.toUInt()%628u).toFloat()/100f
        val side = d.cross(if (abs(d.y)>.9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()
        val up = side.cross(d)
        val palette = when ((seed.toUInt()%4u).toInt()) {
            0 -> rgb(.94f,.29f,.065f)
            1 -> rgb(.88f,.17f,.25f)
            2 -> rgb(.13f,.62f,.69f)
            else -> rgb(.56f,.23f,.69f)
        }
        sphere(center,r*1.12f,membrane,seed,"Eye",10)
        sphere(center+d*(r*.37f),r*.90f,palette,seed,"Eye",12)
        sphere(center+d*(r*1.06f),r*.40f,rgb(.018f,.025f,.035f),seed,"Eye",10)
        val lid = (0..24).map { i ->
            val a = i*2f*pi/24f
            val rad = r*(.86f+.045f*sin(a+phase)+.026f*cos(3f*a-phase))
            center+(side*cos(a)+up*sin(a))*rad+d*(r*(.43f+.085f*sin(a-phase)+.035f*sin(2f*a+phase)))
        }
        tube(lid,r*.12f,membrane,6)
    }

    fun organism(b: SpatialRenderBody) {
        val c = b.position; val color = bodyTint(b); val r = b.radius
        val organic = b.form in listOf("Nucleus","Creature","ProgrammaticNeuron")
        sphere(c,r,color,b.seed,b.form,if (b.form == "Spore") 7 else if (organic) 24 else 18)
        if (!organic) return
        val rng = Random(b.seed)
        val morph = spatialMorphology(b.seed)
        val nucleus = b.form == "Nucleus"
        val shape = if (nucleus) SpatialPoint(1f,1f,1f) else morph.shape
        fun surface(d: SpatialPoint) = c+SpatialPoint(d.x*shape.x,d.y*shape.y,d.z*shape.z)*(r*.86f)
        val rootCount = if (nucleus) 12 else morph.roots
        repeat(rootCount) {
            val d = direction(rng)
            val a = surface(d)
            val bend = direction(rng)
            val path = (0..10).map { i ->
                val t = i/10f
                a+d*(r*(.8f*t))+bend*(r*.28f*sin(pi*t))
            }
            tube(path,r*.15f,color,7) { t -> .08f+.92f*(1f-t)*(1f-t) }
        }
        if (nucleus) {
            repeat(11) { k ->
                val d = direction(rng)
                val a = surface(d)
                sphere(a,r*(.20f+rng.nextFloat()*.15f),rgb(1f,.34f,.075f),b.seed+k,"Eye",14)
                sphere(a+d*(r*.15f),r*.095f,rgb(1f,.84f,.39f),b.seed+k,"Eye",9)
            }
        } else {
            repeat(morph.eyes) { k ->
                val d = direction(rng)
                val eyeRadius = r*morph.eye*(if (k==0) 1.42f else .65f+rng.nextFloat()*.48f)
                eye(surface(d),d,eyeRadius,rng.nextInt(),color)
            }
            repeat(morph.stalks) {
                val d = SpatialPoint(rng.nextFloat()-.5f,.7f+rng.nextFloat(),rng.nextFloat()-.5f).unit()
                val a = surface(d)
                val bend = direction(rng)
                val length = r*(.7f+rng.nextFloat()*.65f)
                val path = (0..12).map { i ->
                    val t=i/12f
                    a+d*(length*t)+bend*(r*.22f*sin(pi*t))
                }
                tube(path,r*.12f,color,8) { t -> .45f+.55f*(1f-t) }
                eye(path.last(),d,r*morph.eye*.72f,rng.nextInt(),color)
            }
        }
        // Individual roots, lengths, curl, thickness and tint; no uniform radial spikes.
        val count = if (nucleus) 84 else 48+(morph.hair*32f).toInt()
        repeat(count) { index ->
            val random = direction(rng)
            val d = (random+SpatialPoint(.15f,morph.crown*.55f,-.18f)).unit()
            val a = surface(d)
            val side = d.cross(if (abs(d.y)>.9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()
            val up = side.cross(d)
            val phase = rng.nextFloat()*2f*pi
            val sample = rng.nextFloat()
            val length = r*(.12f+morph.hair*sample*sample*(if (index%7==0) 1.8f else .9f))
            val width = r*(.0025f+rng.nextFloat()*.006f)
            val path = (0..7).map { i ->
                val t = i/7f
                a+d*(length*(t-.30f*t*t))+side*(length*.26f*t*sin(phase+t*6f))+
                    up*(length*.17f*t*cos(phase*.7f+t*5f))
            }
            val tint = when(index%4) {
                0 -> rgb(.85f,.22f,.075f)
                1 -> rgb(.22f,.32f,.91f)
                2 -> rgb(.56f,.16f,.59f)
                else -> rgb(.19f,.62f,.63f)
            }
            tube(path,width,tint,4) { t -> 1f-.92f*t }
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
    float membrane=0.5+0.5*sin(world.x*37.+sin(world.y*29.)*2.+world.z*31.);
    float warm=max(0.,color.r-max(color.g,color.b));
    vec3 lit=color*(.29+.85*diffuse+.55*rim)*(0.88+0.20*membrane)+vec3(.42,.82,.98)*spec*1.2;
    lit+=vec3(1.,.25,.04)*warm*(.48+.55*rim)+vec3(.03,.22,.32)*rim;
    lit=lit/(vec3(1.)+lit*.35);
    float fog=exp(-length(eye-world)*.026);
    frag=vec4(mix(vec3(.012,.045,.047),lit,fog),1.);
}"""
