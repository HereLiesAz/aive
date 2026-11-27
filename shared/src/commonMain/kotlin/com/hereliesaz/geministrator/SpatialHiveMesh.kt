package com.hereliesaz.geministrator

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Portable vertex: XYZ, normal, RGB, optical anchor XYZ, blur multiplier, roughness, transmission, emission. */
internal const val SPATIAL_VERTEX_FLOATS = 16
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
        val d = spatialMotionPoint(body.position, camera.motionPhase) - view.eye
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
    b.form == "Nucleus" -> rgb(.29f,.22f,.39f)
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

/** Artist-tuned optical response, not task health, activity or agent identity. */
internal data class SpatialMaterial(val roughness: Float, val transmission: Float, val emission: Float)

internal object SpatialMaterials {
    val Solid = SpatialMaterial(.58f,0f,0f)
    val Membrane = SpatialMaterial(.23f,.72f,0f)
    val Tissue = SpatialMaterial(.27f,.58f,0f)
    val Cornea = SpatialMaterial(.09f,.16f,0f)
    val Hair = SpatialMaterial(.38f,.22f,0f)
    val Organelle = SpatialMaterial(.29f,.24f,2.1f)
    val Spore = SpatialMaterial(.32f,.32f,.35f)
    val Transfer = SpatialMaterial(.28f,.28f,.85f)
}

private class SpatialMeshBuilder {
    private var data = FloatArray(8192)
    private var size = 0
    private val pi = PI.toFloat()
    private var opticalAnchor: SpatialPoint? = null
    private var opticalMultiplier = 1f
    private var surfaceMaterial = SpatialMaterials.Solid

    fun finish(): FloatArray = data.copyOf(size)

    private fun vertex(p: SpatialPoint, n: SpatialPoint, c: SpatialPoint, material: SpatialMaterial) {
        if (size + SPATIAL_VERTEX_FLOATS > data.size) data = data.copyOf(data.size * 2)
        data[size++] = p.x; data[size++] = p.y; data[size++] = p.z
        data[size++] = n.x; data[size++] = n.y; data[size++] = n.z
        data[size++] = c.x; data[size++] = c.y; data[size++] = c.z
        val anchor = opticalAnchor ?: p
        data[size++] = anchor.x; data[size++] = anchor.y; data[size++] = anchor.z
        data[size++] = opticalMultiplier
        data[size++] = material.roughness; data[size++] = material.transmission; data[size++] = material.emission
    }

    private fun triangle(a: Sample,b: Sample,c: Sample,color: SpatialPoint,material: SpatialMaterial = surfaceMaterial) {
        vertex(a.p,a.n,a.tint ?: color,material); vertex(b.p,b.n,b.tint ?: color,material); vertex(c.p,c.n,c.tint ?: color,material)
    }

    private data class Sample(val p: SpatialPoint,val n: SpatialPoint,val tint: SpatialPoint? = null)

    /**
     * Sculpted closed soma. The same directional function anchors its roots and sockets.
     * Cache grid vertices; finite-difference tangents follow the actual displaced surface,
     * including folds, rather than shading a smooth ellipsoid underneath the sculpt.
     */
    private fun sphere(center: SpatialPoint,radius: Float,color: SpatialPoint,seed: Int,form: String,
        detail: Int = 18,material: SpatialMaterial = surfaceMaterial,
        roots: List<SpatialPoint> = emptyList(),sockets: List<SpatialPoint> = emptyList()) {
        val phase=(seed.toUInt()%628u).toFloat()/100f
        val morph=spatialMorphology(seed)
        val shaped=form in listOf("Nucleus","Creature","ProgrammaticNeuron")
        val axes=if(shaped && form!="Nucleus") morph.shape else SpatialPoint(1f,1f,1f)
        val lobes=if(form=="Nucleus") 7 else morph.lobes
        val relief=if(form=="Nucleus") .26f else morph.relief
        fun offset(d: SpatialPoint)=if(shaped) spatialSomaOffset(d,axes,lobes,relief,phase,roots,sockets) else d
        fun sample(i: Int,j: Int): Sample {
            val phi=pi*i/detail
            val theta=pi*j/detail
            val d=when(i) {
                0 -> SpatialPoint(0f,1f,0f)
                detail -> SpatialPoint(0f,-1f,0f)
                else -> SpatialPoint(sin(phi)*cos(theta),cos(phi),sin(phi)*sin(theta))
            }
            val pos=offset(d)
            val normal=if(!shaped) d else {
                val side=d.cross(if(abs(d.y)>.9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()
                val up=d.cross(side).unit()
                val du=offset((d+side*.002f).unit())-offset((d-side*.002f).unit())
                val dv=offset((d+up*.002f).unit())-offset((d-up*.002f).unit())
                du.cross(dv).unit().let { if(it==SpatialPoint.Origin) d else if(dot(it,d)<0f) it*(-1f) else it }
            }
            val warm=if(shaped) ((.12f-d.y)*.72f).coerceIn(0f,.78f) else 0f
            val grain=if(shaped) .88f+.12f*sin(d.x*19f+d.z*11f+phase)*sin(d.y*17f) else 1f
            val tint=(color*(1f-warm)+rgb(.97f,.29f,.105f)*warm)*grain
            return Sample(center+pos*radius,normal,tint)
        }
        val rings=List(detail+1) { i -> List(detail*2) { j -> sample(i,j) } }
        for(i in 0 until detail) for(j in 0 until detail*2) {
            val k=(j+1)%(detail*2)
            val a=rings[i][j];val b=rings[i+1][j];val c=rings[i+1][k];val d=rings[i][k]
            triangle(a,b,c,color,material);triangle(a,c,d,color,material)
        }
    }

    /** Transport one ring frame along the whole curve, sharing joints rather than leaving cracks. */
    fun tube(points: List<SpatialPoint>, width: Float, color: SpatialPoint, sides: Int = 7, material: SpatialMaterial = surfaceMaterial, profile: (Float) -> Float = { 1f }) {
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
            triangle(a,b,c,color,material); triangle(a,c,d,color,material)
        }
    }

    /** Decorative tissue is sculpted independently of logical dependency and transfer overlays. */
    fun cord(c: SpatialRenderCord) {
        opticalAnchor = null
        opticalMultiplier = spatialBlurScale(c.blurScale)
        surfaceMaterial = when(c.kind) {
            "Transfer" -> SpatialMaterials.Transfer
            "Dependency", "Memory" -> SpatialMaterials.Solid
            else -> SpatialMaterials.Tissue
        }
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

    /** Convex iris with radially varying pigment, a dark pupil and an irregular wet eyelid. */
    private fun eye(center: SpatialPoint,d: SpatialPoint,r: Float,seed: Int,membrane: SpatialPoint,
        socket: ((SpatialPoint) -> SpatialPoint)? = null) {
        val phase=(seed.toUInt()%628u).toFloat()/100f
        val side=d.cross(if(abs(d.y)>.9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()
        val up=side.cross(d)
        val iris=when((seed.toUInt()%4u).toInt()) {
            0 -> rgb(.94f,.29f,.065f)
            1 -> rgb(.88f,.17f,.25f)
            2 -> rgb(.13f,.62f,.69f)
            else -> rgb(.56f,.23f,.69f)
        }
        val globe=center+d*(r*(if(socket!=null) -.16f else .26f))
        sphere(globe,r,membrane,seed,"Eye",16,SpatialMaterials.Cornea)
        val pupil=.31f+.07f*sin(phase*2f)
        fun sample(t: Float,angle: Float): Sample {
            val radial=side*cos(angle)+up*sin(angle)
            val n=(d*sqrt((1f-t*t).coerceAtLeast(0f))+radial*t).unit()
            val shade=when {
                t<=pupil -> rgb(.012f,.025f,.038f)
                t>=.82f -> rgb(.16f,.065f,.09f)
                else -> iris*(.69f+.31f*sin(angle*37f+phase+t*13f).let { it*it })
            }
            return Sample(globe+n*(r*1.012f),n,shade)
        }
        val bands=floatArrayOf(0f,pupil*.94f,pupil+.015f,.57f,.79f,.88f)
        val lid=mutableListOf<SpatialPoint>()
        for(j in 0..48) {
            val angle=2f*pi*j/48
            val (opening,height)=spatialEyelidProfile(angle,phase)
            lid+=globe+(side*cos(angle)+up*sin(angle))*(r*opening)+d*(r*height)
            if(j==48) continue
            val next=2f*pi*(j+1)/48
            for(i in 0 until bands.lastIndex) {
                val a=sample(bands[i],angle); val b=sample(bands[i+1],angle)
                val c=sample(bands[i+1],next); val e=sample(bands[i],next)
                triangle(a,b,c,iris,SpatialMaterials.Cornea); triangle(a,c,e,iris,SpatialMaterials.Cornea)
            }
        }
        if(socket!=null) {
            // A fleshy annular skirt blends the irregular opening into the actual soma.
            // The outer ring lies just inside the sculpt, so it never floats above the body.
            val collar=List(4) { ring ->
                val t=ring/3f
                List(48) { j ->
                    val angle=2f*pi*j/48
                    val radial=side*cos(angle)+up*sin(angle)
                    val outer=socket(center+radial*(r*1.48f))
                    val inner=lid[j]
                    val p=outer+(inner-outer)*t+d*(r*.13f*sin(pi*t))
                    val n=(radial*(.68f-.36f*t)+d*(.5f+.5f*t)).unit()
                    val tint=membrane*(1f-.25f*t)+rgb(.76f,.20f,.07f)*(.25f*t)
                    Sample(p,n,tint)
                }
            }
            for(i in 0 until collar.lastIndex) for(j in 0 until 48) {
                val k=(j+1)%48
                val a=collar[i][j];val b=collar[i+1][j];val c=collar[i+1][k];val e=collar[i][k]
                triangle(a,b,c,membrane,SpatialMaterials.Membrane)
                triangle(a,c,e,membrane,SpatialMaterials.Membrane)
            }
        }
        tube(lid,r*.085f,membrane,6)
    }

    fun organism(b: SpatialRenderBody) {
        opticalAnchor = b.position
        opticalMultiplier = spatialBlurScale(b.blurScale)
        val c = b.position; val color = bodyTint(b); val r = b.radius
        val organic = b.form in listOf("Nucleus","Creature","ProgrammaticNeuron")
        surfaceMaterial = when {
            organic -> SpatialMaterials.Membrane
            b.form == "Spore" -> SpatialMaterials.Spore
            b.form == "Egg" -> SpatialMaterials.Membrane
            else -> SpatialMaterials.Solid
        }
        if(!organic) {
            sphere(c,r,color,b.seed,b.form,if(b.form=="Spore") 7 else 18)
            return
        }
        val rng=Random(b.seed)
        val morph=spatialMorphology(b.seed)
        val nucleus=b.form=="Nucleus"
        val shape=if(nucleus) SpatialPoint(1f,1f,1f) else morph.shape
        val phase=(b.seed.toUInt()%628u).toFloat()/100f
        val rootCount=if(nucleus) 12 else morph.roots
        // A long dominant axon may hang downward, lean sideways or be inverted. This is
        // immutable seeded anatomy, not movement or a new workflow connection.
        val axis=if((b.seed.toUInt()%5u)==0u) 1f else -1f
        val primary=SpatialPoint((rng.nextFloat()-.5f)*.72f,axis,(rng.nextFloat()-.5f)*.72f).unit()
        val rootDirs=mutableListOf<SpatialPoint>()
        val eyeDirs=mutableListOf<SpatialPoint>()
        fun spaced(existing: List<SpatialPoint>, preferred: SpatialPoint?=null): SpatialPoint {
            var best=preferred ?: direction(rng)
            fun crowd(d: SpatialPoint)=existing.maxOfOrNull { dot(it,d) } ?: -1f
            repeat(12) {
                val candidate=direction(rng)
                if(crowd(candidate)<crowd(best)) best=candidate
            }
            return best
        }
        repeat(rootCount) { i -> rootDirs+=if(i==0&&!nucleus) primary else spaced(rootDirs) }
        if(!nucleus) repeat(morph.eyes) { i ->
            val preferred=if(morph==SpatialMorphology.Canopy && i==0) SpatialPoint(.35f,-axis,.28f).unit() else null
            eyeDirs+=preferred ?: spaced(eyeDirs+rootDirs.take(1))
        }
        sphere(c,r,color,b.seed,b.form,24,SpatialMaterials.Membrane,rootDirs,eyeDirs)
        fun offset(d: SpatialPoint)=spatialSomaOffset(d,shape,if(nucleus) 7 else morph.lobes,
            if(nucleus) .26f else morph.relief,phase,rootDirs,eyeDirs)
        fun surface(d: SpatialPoint)=c+offset(d)*(r*.91f)
        fun surfaceThrough(p: SpatialPoint): SpatialPoint {
            val local=p-c
            val d=SpatialPoint(local.x/shape.x,local.y/shape.y,local.z/shape.z).unit()
            return surface(d)
        }
        rootDirs.forEachIndexed { i,d ->
            val a=surface(d)
            val outward=(a-c).unit()
            val bend=direction(rng)
            val dominant=i==0&&!nucleus
            val length=r*(if(dominant) 1.65f+rng.nextFloat()*.85f else .48f+rng.nextFloat()*.48f)
            val bendPhase=rng.nextFloat()*2f*pi
            val path=(0..16).map { step ->
                val t=step/16f
                a+outward*(length*t)+bend*(length*.24f*sin(pi*t)*sin(bendPhase+t*4f))
            }
            tube(path,r*(if(dominant) .26f else .18f),color,10,SpatialMaterials.Tissue) { t ->
                spatialRootProfile(t,bendPhase)
            }
        }
        if (nucleus) {
            repeat(11) { k ->
                val d = direction(rng)
                val a = surface(d)
                val lobeRadius=r*(.29f+rng.nextFloat()*.14f)
                sphere(a,lobeRadius,color,b.seed+k,"Nucleus",16)
                val pocket=a+d*(lobeRadius*.73f)
                sphere(pocket,lobeRadius*.56f,rgb(1.45f,.55f,.13f),b.seed+k,"Eye",14,SpatialMaterials.Organelle)
            }
        } else {
            eyeDirs.forEachIndexed { k,d ->
                val center=surface(d)
                val outward=(center-c).unit()
                val eyeRadius=r*morph.eye*(if(k==0) 1.78f else .48f+rng.nextFloat()*.62f)
                eye(center,outward,eyeRadius,rng.nextInt(),color,::surfaceThrough)
            }
            repeat(morph.stalks) {
                val d = SpatialPoint(rng.nextFloat()-.5f,-axis*(.7f+rng.nextFloat()),rng.nextFloat()-.5f).unit()
                val a = surface(d)
                val bend = direction(rng)
                val length = r*(.7f+rng.nextFloat()*.65f)
                val path = (0..12).map { i ->
                    val t=i/12f
                    a+d*(length*t)+bend*(r*.35f*sin(pi*t)*sin(t*4.8f+.7f))
                }
                tube(path,r*.12f,color,8) { t -> .45f+.55f*(1f-t) }
                eye(path.last(),d,r*morph.eye*.72f,rng.nextInt(),color)
            }
        }
        // Individual roots, lengths, curl, thickness and tint; no uniform radial spikes.
        val count = if (nucleus) 84 else 48+(morph.hair*32f).toInt()
        repeat(count) { index ->
            val random = direction(rng)
            val d = (random+SpatialPoint(.15f,-axis*morph.crown*.55f,-.18f)).unit()
            val a = surface(d)
            val side = d.cross(if (abs(d.y)>.9f) SpatialPoint(1f,0f,0f) else SpatialPoint(0f,1f,0f)).unit()
            val up = side.cross(d)
            val phase = rng.nextFloat()*2f*pi
            val sample = rng.nextFloat()
            val length = r*(.14f+morph.hair*sample*sample*(if (index%7==0) 2.0f else 1.12f))
            val width = r*(.0025f+rng.nextFloat()*.006f)
            val path = (0..7).map { i ->
                val t = i/7f
                a+d*(length*(t-.30f*t*t))+side*(length*.26f*t*sin(phase+t*6f))+
                    up*(length*.17f*t*cos(phase*.7f+t*5f)+length*.06f*t*sin(phase+t*17f))
            }
            val tint = when(index%4) {
                0 -> rgb(.85f,.22f,.075f)
                1 -> rgb(.22f,.32f,.91f)
                2 -> rgb(.56f,.16f,.59f)
                else -> rgb(.19f,.62f,.63f)
            }
            tube(path,width,tint,4,SpatialMaterials.Hair) { t -> 1f-.92f*t }
            if(index%4==0) {
                // A fork begins on the parent centerline, not on an unrelated floating point.
                val fork=3+index%3
                val origin=path[fork]
                val tangent=(path[fork+1]-origin).unit()
                val branchDirection=(tangent+side*(if(index%8==0) .85f else -.85f)+up*.35f).unit()
                val branchLength=length*(.28f+.22f*sample)
                val branch=(0..4).map { j ->
                    val t=j/4f
                    origin+branchDirection*(branchLength*t)+up*(branchLength*.22f*sin(pi*t))
                }
                tube(branch,width*.46f,tint,4,SpatialMaterials.Hair) { t -> 1f-.94f*t }
            }
        }
    }

}

/** OpenGL ES 3 / WebGL2 share the same projection and material shaders. */
internal const val SPATIAL_VERTEX_SHADER = """#version 300 es
precision highp float;
in vec3 p; in vec3 n; in vec3 c; in vec4 optical; in vec3 material;
uniform vec3 eye,forward,right,up;
uniform float aspect,motionPhase,focusDepth,aperture;
out vec3 normal,color,world,rest,materialResponse;
out float blurAmount;
${SPATIAL_MOTION_GLSL}
${SPATIAL_ELEMENT_BLUR_GLSL}
void main(){
    vec3 moved=deformSpatial(p,motionPhase);
    vec3 d=moved-eye; float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)/(.52*aspect),dot(d,up)/.52,1.0002*z-.20002,z);
    normal=n; color=c; world=moved; rest=p; materialResponse=material;
    blurAmount=elementBlur(deformSpatial(optical.xyz,motionPhase),eye,focusDepth,aperture,optical.w);
}"""

internal const val SPATIAL_FRAGMENT_SHADER = """#version 300 es
precision highp float;
in vec3 normal,color,world,rest,materialResponse;
in float blurAmount;
uniform vec3 eye;
uniform float encodeBlur;
out vec4 frag;
void main(){
    vec3 n=normalize(normal),v=normalize(eye-world);
    float rough=clamp(materialResponse.x,.06,1.);
    float transmission=clamp(materialResponse.y,0.,1.);
    float emission=max(0.,materialResponse.z);
    // Fine membrane relief remains anchored to the undeformed geometry, rather than swimming
    // through the material as the creature sways. Derivatives are not needed for the pattern.
    vec3 q=rest*vec3(38.,47.,43.);
    float grain=sin(q.x+1.7*sin(q.y))*sin(q.z+.8*sin(q.x));
    vec3 bump=vec3(cos(q.x+1.7*sin(q.y)),cos(q.y+q.z*.7),cos(q.z+.8*sin(q.x)));
    n=normalize(n+(bump-n*dot(bump,n))*.055*transmission);
    vec3 key=normalize(vec3(-.42,.86,.56)),fill=normalize(vec3(.68,.16,-.73));
    float ndv=max(.001,abs(dot(n,v)));
    float rim=pow(1.-ndv,2.4);
    float fresnel=.035+.965*pow(1.-ndv,5.);
    float diffuse=max(0.,dot(n,key));
    float back=max(0.,dot(-n,key));
    float gloss=mix(140.,12.,rough);
    float spec=pow(max(0.,dot(n,normalize(key+v))),gloss);
    float specFill=pow(max(0.,dot(n,normalize(fill+v))),gloss*.67);
    vec3 pigment=max(vec3(0.),color)*(.94+.06*grain*transmission);
    vec3 lit=pigment*(.22+.65*diffuse);
    // Thin tissue transmits more warm light at grazing angles and through backlit surfaces.
    // This approximates subsurface scattering; it does not sample geometry behind the membrane.
    vec3 transmitted=mix(pigment,vec3(1.,.30,.075),.44);
    lit+=transmitted*transmission*(.24+.52*back+.26*rim);
    lit+=vec3(.16,.56,.72)*rim*transmission*.43;
    lit+=vec3(.68,.88,1.)*(spec*.95+specFill*.48)*(1.-rough*.6);
    lit+=vec3(.17,.43,.57)*fresnel*(1.-rough*.75);
    lit+=pigment*emission;
    lit=lit/(vec3(1.)+lit*.45);
    float fog=exp(-length(eye-world)*.026);
    frag=vec4(mix(vec3(.012,.045,.047),lit,fog),mix(1.,(128.+127.*blurAmount)/255.,encodeBlur));
}"""
