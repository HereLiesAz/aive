package com.hereliesaz.geministrator

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/** The field can fall back while migration is in progress if device GPU initialization fails. */
internal actual val spatialHiveSupported: Boolean get() = true

@Composable
internal actual fun SpatialHiveSurface(frame: SpatialRenderFrame, camera: SpatialRenderCamera,
    onInput: (SpatialRenderInput) -> Unit, modifier: Modifier) {
    val mesh by rememberSpatialMesh(frame)
    AndroidView(
        factory = { context ->
            SpatialGlView(context).apply { input = onInput }
        },
        modifier = modifier,
        update = { view -> view.input = onInput; view.updateScene(mesh, camera) },
        onRelease = { view -> view.input = {} },
    )
}

/** Native surface lifecycle owns the GL thread. Compose owns camera, scene, and selection. */
private class SpatialGlView(context: Context) : GLSurfaceView(context) {
    var input: (SpatialRenderInput) -> Unit = {}
    private val renderer = SpatialGlRenderer { post { input(SpatialRenderInput("error")) } }
    private val supported = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.deviceConfigurationInfo?.reqGlEsVersion?.let { it >= 0x30000 } == true
    private var snapshot: SpatialMeshSnapshot? = null
    private var camera: SpatialRenderCamera? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multi = false
    private var paused = false
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (factor.isFinite() && factor > 0f) input(SpatialRenderInput("zoom", x = 1f/factor))
            return true
        }
    })

    init {
        isFocusable = true
        contentDescription = "The Aive spatial hive"
        if (supported) {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8,8,8,0,24,0)
            setRenderer(renderer)
            renderMode = RENDERMODE_WHEN_DIRTY
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!supported) post { input(SpatialRenderInput("error")) }
        else if (paused) { onResume(); paused = false }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!supported || !isAttachedToWindow) return
        if (visibility != View.VISIBLE && !paused) { onPause(); paused = true }
        else if (visibility == View.VISIBLE && paused) { onResume(); paused = false }
    }

    fun updateScene(mesh: SpatialMeshSnapshot?, view: SpatialRenderCamera) {
        if (!supported) return
        if (snapshot === mesh && camera == view) return
        snapshot = mesh; camera = view
        renderer.mesh = mesh
        renderer.camera = view
        requestRender()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!supported) return false
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                moved = false; multi = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true; moved = true
                lastX = event.getX(0); lastY = event.getY(0)
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.getX(0); val y = event.getY(0)
                val dx = x-lastX; val dy = y-lastY
                if (abs(x-downX)+abs(y-downY) > android.view.ViewConfiguration.get(context).scaledTouchSlop) moved = true
                if (moved && !multi) input(SpatialRenderInput("orbit",x=-dx*.25f,y=dy*.25f))
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!moved && !multi && width > 0 && height > 0) {
                    performClick()
                    val selected = camera?.let { snapshot?.frame?.pick(it, event.x/width*2f-1f, 1f-event.y/height*2f, width.toFloat()/height) }
                    input(selected?.let { SpatialRenderInput(if (it.form=="Spore") "memory" else "agent",it.id) }
                        ?: SpatialRenderInput("empty"))
                }
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

/** ES3 uploads immutable triangles on the GL thread and redraws only for scene/camera changes. */
private class SpatialGlRenderer(private val failed: () -> Unit) : GLSurfaceView.Renderer {
    @Volatile var mesh: SpatialMeshSnapshot? = null
    @Volatile var camera: SpatialRenderCamera? = null
    private var uploaded: SpatialMeshSnapshot? = null
    private var program = 0
    private var buffer = 0
    private var vao = 0
    private var count = 0
    private var aspect = 1f
    private var uniforms = IntArray(6)
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var optics: SpatialAndroidOptics? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        uploaded = null; count = 0; program = 0; optics = null
        try {
            fun shader(type: Int, source: String): Int {
                val id = GLES30.glCreateShader(type)
                GLES30.glShaderSource(id,source); GLES30.glCompileShader(id)
                val ok = IntArray(1); GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,ok,0)
                if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); error(log) }
                return id
            }
            fun link(vertexSource: String, fragmentSource: String): Int {
                val vertex=shader(GLES30.GL_VERTEX_SHADER,vertexSource)
                val fragment=try { shader(GLES30.GL_FRAGMENT_SHADER,fragmentSource) }
                    catch(e: Exception) { GLES30.glDeleteShader(vertex); throw e }
                val p=GLES30.glCreateProgram()
                GLES30.glAttachShader(p,vertex); GLES30.glAttachShader(p,fragment); GLES30.glLinkProgram(p)
                GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
                val ok=IntArray(1); GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0)
                if(ok[0]==0) { val log=GLES30.glGetProgramInfoLog(p); GLES30.glDeleteProgram(p); error(log) }
                return p
            }
            val p=link(SPATIAL_VERTEX_SHADER,SPATIAL_FRAGMENT_SHADER)
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1,ids,0); vao=ids[0]; GLES30.glBindVertexArray(vao)
            GLES30.glGenBuffers(1,ids,0); buffer=ids[0]; GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffer)
            listOf("p","n","c").forEachIndexed { i,name ->
                val loc=GLES30.glGetAttribLocation(p,name)
                require(loc >= 0)
                GLES30.glEnableVertexAttribArray(loc)
                GLES30.glVertexAttribPointer(loc,3,GLES30.GL_FLOAT,false,SPATIAL_VERTEX_FLOATS*4,i*12)
            }
            uniforms=listOf("eye","forward","right","up","aspect","motionPhase").map { GLES30.glGetUniformLocation(p,it) }.toIntArray()
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            program=p
            optics=runCatching { SpatialAndroidOptics(link(SPATIAL_OPTICS_VERTEX_SHADER,SPATIAL_OPTICS_FRAGMENT_SHADER)) }.getOrNull()
        } catch (_: Exception) { failed() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0,0,width,height)
        viewportWidth=width.coerceAtLeast(1); viewportHeight=height.coerceAtLeast(1)
        aspect=viewportWidth.toFloat()/viewportHeight
    }

    override fun onDrawFrame(gl: GL10?) {
        val view=camera
        val pass=if(view!=null && program!=0) optics?.takeIf { it.begin(viewportWidth,viewportHeight) } else null
        GLES30.glClearColor(.012f,.045f,.047f,1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (view==null || program==0) return
        val current=mesh
        if (uploaded !== current) {
            if (current != null) {
                val data=current.vertices
                val bytes=ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                bytes.put(data); bytes.flip()
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffer)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,data.size*4,bytes,GLES30.GL_STATIC_DRAW)
                count=data.size/SPATIAL_VERTEX_FLOATS
            } else count=0
            uploaded=current
        }
        GLES30.glUseProgram(program); GLES30.glBindVertexArray(vao)
        val basis=view.basis()
        listOf(basis.eye,basis.forward,basis.right,basis.up).forEachIndexed { i,p ->
            GLES30.glUniform3f(uniforms[i],p.x,p.y,p.z)
        }
        GLES30.glUniform1f(uniforms[4],aspect)
        GLES30.glUniform1f(uniforms[5],view.motionPhase)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,count)
        pass?.finish(view.optics())
    }
}


/** Context-local color/depth target and a bounded depth-of-field pass. A failed target uses direct 3D. */
private class SpatialAndroidOptics(private val program: Int) {
    private var framebuffer=0
    private var textures=IntArray(2)
    private var width=0
    private var height=0
    private var ready=false
    private var previousFramebuffer=IntArray(1)
    private var previousViewport=IntArray(4)
    private val uniforms=listOf("sceneColor","sceneDepth","focusDepth","aperture").map { GLES30.glGetUniformLocation(program,it) }.toIntArray()

    /** Preserve the drawable's framebuffer, especially GLJPanel's nonzero backing target. */
    fun begin(w: Int,h: Int): Boolean {
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING,previousFramebuffer,0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT,previousViewport,0)
        if(w!=width || h!=height) {
            releaseTargets()
            width=w;height=h
            try {
                val ids=IntArray(1)
                GLES30.glGenFramebuffers(1,ids,0);framebuffer=ids[0]
                GLES30.glGenTextures(2,textures,0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                fun allocate(id: Int,internal: Int,format: Int,type: Int,filter: Int) {
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,id)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,filter)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,filter)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D,0,internal,w,h,0,format,type,null)
                }
                allocate(textures[0],GLES30.GL_RGBA8,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,GLES30.GL_LINEAR)
                allocate(textures[1],GLES30.GL_DEPTH_COMPONENT24,GLES30.GL_DEPTH_COMPONENT,GLES30.GL_UNSIGNED_INT,GLES30.GL_NEAREST)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,framebuffer)
                GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,textures[0],0)
                GLES30.glFramebufferTexture2D(GLES30.GL_DRAW_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_TEXTURE_2D,textures[1],0)
                ready=GLES30.glCheckFramebufferStatus(GLES30.GL_DRAW_FRAMEBUFFER)==GLES30.GL_FRAMEBUFFER_COMPLETE
            } catch(_: RuntimeException) { ready=false }
            if(!ready)releaseTargets()
        }
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,if(ready) framebuffer else previousFramebuffer[0])
        if(ready)GLES30.glViewport(0,0,w,h)
        return ready
    }

    fun finish(params: SpatialOptics) {
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,previousFramebuffer[0])
        GLES30.glViewport(previousViewport[0],previousViewport[1],previousViewport[2],previousViewport[3])
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0])
        GLES30.glUniform1i(uniforms[0],0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[1])
        GLES30.glUniform1i(uniforms[1],1)
        GLES30.glUniform1f(uniforms[2],params.focusDepth)
        GLES30.glUniform1f(uniforms[3],params.aperture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun releaseTargets() {
        if(framebuffer!=0)GLES30.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        if(textures.any { it!=0 })GLES30.glDeleteTextures(2,textures,0)
        framebuffer=0;textures=IntArray(2);ready=false
    }
}
