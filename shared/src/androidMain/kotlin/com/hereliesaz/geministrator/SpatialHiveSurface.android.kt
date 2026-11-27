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
    private var uniforms = IntArray(5)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        uploaded = null; count = 0; program = 0
        try {
            fun shader(type: Int, source: String): Int {
                val id = GLES30.glCreateShader(type)
                GLES30.glShaderSource(id,source); GLES30.glCompileShader(id)
                val ok = IntArray(1); GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,ok,0)
                if (ok[0] == 0) { val log = GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); error(log) }
                return id
            }
            val vertex = shader(GLES30.GL_VERTEX_SHADER,SPATIAL_VERTEX_SHADER)
            val fragment = shader(GLES30.GL_FRAGMENT_SHADER,SPATIAL_FRAGMENT_SHADER)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p,vertex); GLES30.glAttachShader(p,fragment); GLES30.glLinkProgram(p)
            GLES30.glDeleteShader(vertex); GLES30.glDeleteShader(fragment)
            val ok = IntArray(1); GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0)
            if (ok[0] == 0) { val log=GLES30.glGetProgramInfoLog(p); GLES30.glDeleteProgram(p); error(log) }
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1,ids,0); vao=ids[0]; GLES30.glBindVertexArray(vao)
            GLES30.glGenBuffers(1,ids,0); buffer=ids[0]; GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffer)
            listOf("p","n","c").forEachIndexed { i,name ->
                val loc=GLES30.glGetAttribLocation(p,name)
                require(loc >= 0)
                GLES30.glEnableVertexAttribArray(loc)
                GLES30.glVertexAttribPointer(loc,3,GLES30.GL_FLOAT,false,SPATIAL_VERTEX_FLOATS*4,i*12)
            }
            uniforms=listOf("eye","forward","right","up","aspect").map { GLES30.glGetUniformLocation(p,it) }.toIntArray()
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            program=p
        } catch (_: Exception) { failed() }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0,0,width,height)
        aspect=width.coerceAtLeast(1).toFloat()/height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(.012f,.045f,.047f,1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val view=camera ?: return
        if (program == 0) return
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
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,count)
    }
}
