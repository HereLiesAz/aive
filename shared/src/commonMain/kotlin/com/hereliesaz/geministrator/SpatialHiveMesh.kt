package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.random.Random

/** One portable triangle stream: position, normal, RGB, nine floats per vertex. */
internal const val SPATIAL_VERTEX_FLOATS = 9
internal const val SPATIAL_FOCAL_SCALE = .52f

/** Shared rendering basis and ray tests use the same perspective as all GPU backends. */
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
    cords.forEach(::cord)
    (bodies + spores).forEach(::organism)
}.finish()

private fun rgb(r: Float, g: Float, b: Float) = SpatialPoint(r,g,b)

private val bodyPalette = listOf(rgb(.12f,.62f,.57f),rgb(.28f,.42f,.85f),rgb(.55f,.24f,.75f),rgb(.12f,.67f,.83f),rgb(.8f,.39f,.2f))

private fun bodyTint(b: SpatialRenderBody): SpatialPoint = when {
    b.form == "Nucleus" -> rgb(.28f,.21f,.38f)
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
        vertex(a.p,a.n,a.tint ?: color); vertex(b.p,b.n,b.tint ?: color); vertex(c.p,c.n,c.tint ?: color)
    }

    private data class Sample(val p: SpatialPoint,val n: SpatialPoint,val tint: SpatialPoint? = null)

    /** Folded soma with independently variable axes, local relief and warm underside pigment. */
    private fun sphere(center: SpatialPoint,radius: Float,color: SpatialPoint,seed: Int,form: String,
        detail: Int = 18, axes: SpatialPoint = SpatialPoint(1f,1f,1f), lobes: Int = 3, relief: Float = 0f) {
        val phase=(seed.toUInt()%628u).toFloat()/100f
        fun direction(phi: Float,theta: Float)=SpatialPoint(sin(phi)*cos(theta),cos(phi),sin(phi)*sin(theta))
        fun point(phi: Float,theta: Float): SpatialPoint {
            val n=direction(phi,theta)
            val fold=sin(theta*lobes+phase)*sin(phi*3f)+.45f*cos(phi*5f+theta*2f+phase)*sin(phi)
            val texture=.22f*sin(theta*11f+phase)*sin(phi*13f)*sin(phi)
            return center+SpatialPoint(n.x*axes.x,n.y*axes.y,n.z*axes.z)*(radius*(1f+relief*(fold+texture)))
        }
        fun sample(i: Int,j: Int): Sample {
            val phi=pi*i/detail
            val theta=pi*j/detail
            val n=direction(phi,theta)
            val normal=if(i==0||i==detail) n else {
                val dp=point(phi+.002f,theta)-point(phi-.002f,theta)
                val dt=point(phi,theta+.002f)-point(phi,theta-.002f)
                dp.cross(dt).unit().let { if(it==SpatialPoint.Origin) n else if(dot(it,n)<0f) it*(-1f) else it }
            }
            val shade=if(relief>0f) {
                val warm=((.12f-n.y)*.72f).coerceIn(0f,.78f)
                (color*(1f-warm)+rgb(.97f,.29f,.105f)*warm)*(.85f+.15f*sin(theta*17f+phase)*sin(phi*12f))
            } else color
            return Sample(point(phi,theta),normal,shade)
        }
        for(i in 0 until detail) for(j in 0 until detail*2) {
            val a=sample(i,j); val b=sample(i+1,j); val c=sample(i+1,j+1); val d=sample(i,j+1)
            triangle(a,b,c,color); triangle(a,c,d,color)
        }
    }

    /** Change only the rendered radius, never the persisted branch centerline or logical links. */
    fun cord(cord: SpatialRenderCord) {
        val color=cordTint(cord.kind)
        val phase=cord.points.firstOrNull()?.let { it.x*1.7f+it.z*.9f } ?: 0f
        if(cord.kind in listOf("Memory","Dependency","Transfer")) tube(cord.points,cord.width,color)
        else tube(cord.points,cord.width,color,radiusAt={t -> .34f+.66f*spatialNeuriteProfile(t,phase)})
    }

    /** Transport one ring frame along the whole curve, sharing joints rather than leaving cracks. */
    fun tube(points: List<SpatialPoint>, width: Float, color: SpatialPoint, sides: Int = 7,
        radiusAt: (Float) -> Float = { 1f }) {
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
                Sample(p+n*(width*radiusAt(i.toFloat()/path.lastIndex).coerceAtLeast(.015f)),n)
            }
        }
        for (i in 1 until rings.size) for (j in 0 until sides) {
            val k = (j+1)%sides
            val a = rings[i-1][j]; val b = rings[i][j]; val c = rings[i][k]; val d = rings[i-1][k]
            triangle(a,b,c,color); triangle(a,c,d,color)
        }
    }

    private fun Random.direction(): SpatialPoint {
        val y=nextFloat()*2f-1f
        val angle=nextFloat()*2f*pi
        val radial=sqrt((1f-y*y).coerceAtLeast(0f))
        return SpatialPoint(cos(angle)*radial,y,sin(angle)*radial)
    }

    private fun perpendicular(d: SpatialPoint): SpatialPoint =
        d.cross(if(abs(d.y)>.9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()

    private fun surface(d: SpatialPoint,axes: SpatialPoint,r: Float)=SpatialPoint(d.x*axes.x,d.y*axes.y,d.z*axes.z)*r

    /** Convex iris with radial pigment, unequal pupils and irregular eyelid membrane. */
    private fun eye(center: SpatialPoint,direction: SpatialPoint,radius: Float,color: SpatialPoint,phase: Float) {
        val d=direction.unit()
        val right=perpendicular(d); val up=d.cross(right)
        sphere(center,radius,color,0,"Eye",12)
        val iris=if(sin(phase)>.35f) rgb(.24f,.66f,.77f) else if(cos(phase)<-.4f) rgb(.73f,.27f,.58f) else rgb(1f,.43f,.12f)
        val pupil=.31f+.07f*sin(phase*2f)
        fun sample(t: Float,angle: Float): Sample {
            val radial=right*cos(angle)+up*sin(angle)
            val n=(d*sqrt((1f-t*t).coerceAtLeast(0f))+radial*t).unit()
            val shade=when {
                t<=pupil -> rgb(.012f,.025f,.038f)
                t>=.82f -> rgb(.16f,.065f,.09f)
                else -> iris*(.69f+.31f*sin(angle*37f+phase+t*13f).let { it*it })
            }
            return Sample(center+n*(radius*1.012f),n,shade)
        }
        val bands=floatArrayOf(0f,pupil*.94f,pupil+.015f,.57f,.79f,.88f)
        val lid=mutableListOf<SpatialPoint>()
        for(j in 0..48) {
            val angle=2f*pi*j/48
            val (opening,height)=spatialEyelidProfile(angle,phase)
            lid+=center+(right*cos(angle)+up*sin(angle))*(radius*opening)+d*(radius*height)
            if(j==48) continue
            val next=2f*pi*(j+1)/48
            for(i in 0 until bands.lastIndex) {
                val a=sample(bands[i],angle);val b=sample(bands[i+1],angle)
                val c=sample(bands[i+1],next);val e=sample(bands[i],next)
                triangle(a,b,c,iris);triangle(a,c,e,iris)
            }
        }
        tube(lid,radius*.085f,color,6)
    }

    /** Individual seeded 3D hairs vary in root, diameter, length, direction and pigment. */
    private fun hairs(center: SpatialPoint,radius: Float,morph: SpatialMorphology,color: SpatialPoint,random: Random) {
        repeat((44*morph.hair).toInt()) { i ->
            val raw=random.direction()
            val d=SpatialPoint(raw.x,raw.y*.65f+morph.crown*.48f,raw.z).unit()
            val start=center+surface(d,morph.axes,radius*.88f)
            val length=radius*morph.hair*(.36f+random.nextFloat()*.98f)
            val bend=perpendicular(d)*(length*(random.nextFloat()-.5f))
            val sweep=SpatialPoint(.28f,.12f,.07f)*(length*morph.crown)
            val phase=random.nextFloat()*2f*pi
            val path=(0..6).map { step ->
                val t=step/6f
                start+d*(length*t)+bend*(sin(t*pi*1.6f+phase)-sin(phase))*t+sweep*(t*t)
            }
            val pigment=when(i%4) {
                0 -> rgb(.9f,.22f,.09f)
                1 -> rgb(.35f,.16f,.88f)
                2 -> rgb(.13f,.7f,.87f)
                else -> color
            }
            tube(path,radius*(.004f+random.nextFloat()*.008f),pigment,4,radiusAt={t -> 1f-t*.94f})
        }
    }

    /** One folded central ganglion, with warm organelles and tapered connective arms. */
    private fun nucleus(body: SpatialRenderBody) {
        val c=body.position;val r=body.radius;val rng=Random(body.seed);val color=bodyTint(body)
        sphere(c,r*.74f,color,body.seed,"Nucleus",24,SpatialPoint(1.1f,.84f,1f),7,.22f)
        repeat(9) { i ->
            val d=rng.direction()
            val lobe=c+d*(r*(.48f+rng.nextFloat()*.12f))
            val size=r*(.36f+rng.nextFloat()*.14f)
            sphere(lobe,size,color,body.seed+i,"Nucleus",16,SpatialPoint(1.15f,.8f,1f),5,.18f)
            sphere(lobe+d*(size*.73f),size*.52f,rgb(1.45f,.55f,.13f),body.seed+i,"Organelle",12)
            tube(listOf(lobe,lobe+d*(r*.5f),lobe+d*(r*.88f)+perpendicular(d)*(r*.18f)),
                r*.14f,rgb(.18f,.43f,.67f),8,radiusAt={t -> 1f-.86f*t})
        }
    }

    fun organism(b: SpatialRenderBody) {
        val c=b.position;val color=bodyTint(b);val r=b.radius
        if(b.form=="Nucleus") { nucleus(b);return }
        if(b.form !in listOf("Creature","ProgrammaticNeuron")) {
            sphere(c,r,color,b.seed,b.form,if(b.form=="Spore") 7 else 18)
            return
        }
        val morph=spatialOrganismMorphology(b.seed)
        val rng=Random(b.seed)
        sphere(c,r,color,b.seed,b.form,24,morph.axes,morph.lobes,morph.relief)
        val primary=rng.direction()
        repeat(morph.eyes) { i ->
            val d=if(i==0) primary else (rng.direction()+primary*.32f).unit()
            val size=r*morph.eyeSize*(if(i==0) 1.55f else .62f+rng.nextFloat()*.48f)
            eye(c+surface(d,morph.axes,r*.88f),d,size,color,rng.nextFloat()*2f*pi)
        }
        repeat(morph.stalks) { i ->
            val d=SpatialPoint(if(i==0) .32f else -.46f,1f,.16f).unit()
            val start=c+surface(d,morph.axes,r*.73f)
            val tip=start+d*(r*(.95f+rng.nextFloat()*.48f))
            val bend=perpendicular(d)*(r*.30f)
            val path=(0..12).map { j ->
                val t=j/12f
                start+(tip-start)*t+bend*sin(t*pi*1.55f)*sin(t*pi)
            }
            tube(path,r*.14f,color,9,radiusAt={t -> .40f+.60f*(1f-t)})
            eye(tip,(d+primary*.35f).unit(),r*morph.eyeSize*.74f,color,rng.nextFloat()*2f*pi)
        }
        repeat(morph.roots) { i ->
            val d=if(i==0) SpatialPoint(.10f,-1f,.12f).unit() else rng.direction()
            val start=c+surface(d,morph.axes,r*.68f)
            val length=r*(if(i==0) 1.65f else .38f+rng.nextFloat()*.48f)
            val bend=perpendicular(d)*(length*.25f)
            val path=(0..10).map { j ->
                val t=j/10f
                start+d*(length*t)+bend*sin(t*pi)*t
            }
            tube(path,r*(if(i==0) .20f else .105f),color,8,radiusAt={t -> (1f-t).pow(1.4f)+.035f})
        }
        hairs(c,r,morph,color,rng)
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
    vec3 cool=vec3(.12,.58,.83),warm=vec3(1.,.25,.055);
    float coat=pow(max(dot(n,normalize(vec3(.45,.7,-.5)+v)),0.),92.);
    float backlight=pow(max(dot(-n,normalize(vec3(.1,-.8,.45))),0.),2.);
    float mottling=.88+.12*sin(world.x*37.+sin(world.z*29.))*sin(world.y*43.);
    vec3 lit=color*mottling*(.24+.85*diffuse)+cool*rim*.32+warm*backlight*.24+vec3(.68,.9,1.)*(spec*.95+coat*.72);
    lit+=max(color-vec3(1.),vec3(0.))*1.8;
    float fog=exp(-length(eye-world)*.055);
    frag=vec4(mix(vec3(.012,.045,.047),lit,fog),1.);
}"""
