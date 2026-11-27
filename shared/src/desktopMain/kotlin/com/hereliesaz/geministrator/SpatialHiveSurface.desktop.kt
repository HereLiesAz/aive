package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.jogamp.opengl.GL
import com.jogamp.opengl.GL2ES2
import com.jogamp.opengl.GL3
import com.jogamp.opengl.GLAutoDrawable
import com.jogamp.opengl.GLCapabilities
import com.jogamp.opengl.GLEventListener
import com.jogamp.opengl.GLProfile
import com.jogamp.opengl.awt.GLJPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.exp

/** A headless desktop keeps the existing migration fallback, rather than starting a GL context. */
internal actual val spatialHiveSupported: Boolean get() = !GraphicsEnvironment.isHeadless()

/** A native desktop GL drawable in the Compose field. No second window or browser runtime. */
@Composable
internal actual fun SpatialHiveSurface(
    frame: SpatialRenderFrame, camera: SpatialRenderCamera,
    onInput: (SpatialRenderInput) -> Unit, modifier: Modifier,
) {
    val mesh by rememberSpatialMesh(frame)
    SwingPanel(
        factory = { SpatialDesktopPanel(onInput) },
        modifier = modifier,
        update = { panel -> panel.updateScene(mesh, camera, onInput) },
    )
}

/** Translate only the shader dialect header. GL3 uses the same world projection and materials. */
internal fun spatialDesktopShader(source: String): String {
    require(source.startsWith("#version 300 es\n"))
    return source.replaceFirst("#version 300 es\n", "#version 150\n")
        .replace("precision highp float;\n", "")
}

/** Swing owns drawable lifecycle; all input and scene ownership remain in Compose. */
private class SpatialDesktopPanel(private var input: (SpatialRenderInput) -> Unit) : JPanel(BorderLayout()) {
    private var drawable: GLJPanel? = null
    private val renderer = SpatialDesktopRenderer { reportFailure() }
    private var snapshot: SpatialMeshSnapshot? = null
    private var camera: SpatialRenderCamera? = null
    private var failed = false

    init {
        background = Color(3, 20, 20)
        try {
            val caps = GLCapabilities(GLProfile.get(GLProfile.GL3)).apply {
                depthBits = 24
                alphaBits = 0
            }
            val surface = GLJPanel(caps)
            surface.addGLEventListener(renderer)
            surface.isFocusable = true
            surface.accessibleContext?.accessibleName = "The Aive spatial hive"
            val gestures = object : MouseAdapter() {
                private var pressX = 0
                private var pressY = 0
                private var lastX = 0
                private var lastY = 0
                private var dragging = false
                private var panning = false

                override fun mousePressed(e: MouseEvent) {
                    surface.requestFocusInWindow()
                    pressX = e.x; pressY = e.y; lastX = e.x; lastY = e.y
                    dragging = false
                    panning = e.isShiftDown || SwingUtilities.isMiddleMouseButton(e) || SwingUtilities.isRightMouseButton(e)
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (abs(e.x - pressX) + abs(e.y - pressY) >= 3) dragging = true
                    if (!dragging) return
                    val dx = (e.x - lastX).toFloat()
                    val dy = (e.y - lastY).toFloat()
                    lastX = e.x; lastY = e.y
                    if (panning) {
                        camera?.basis()?.let { view ->
                            val delta = view.right * (-dx * .012f) + view.up * (dy * .012f)
                            input(SpatialRenderInput("pan", x = delta.x, y = delta.y, z = delta.z))
                        }
                    } else input(SpatialRenderInput("orbit", x = -dx * .34f, y = dy * .34f))
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (dragging || panning || !SwingUtilities.isLeftMouseButton(e)) return
                    if (surface.width <= 0 || surface.height <= 0) return
                    val view = camera ?: return
                    val mesh = snapshot ?: return
                    val hit = mesh.frame.pick(view, e.x * 2f / surface.width - 1f,
                        1f - e.y * 2f / surface.height, surface.width.toFloat() / surface.height)
                    input(hit?.let {
                        SpatialRenderInput(if (it.form == "Spore") "memory" else "agent", it.id)
                    } ?: SpatialRenderInput("empty"))
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    input(SpatialRenderInput("zoom", x = exp((e.preciseWheelRotation * .12).coerceIn(-1.0, 1.0)).toFloat()))
                }
            }
            surface.addMouseListener(gestures)
            surface.addMouseMotionListener(gestures)
            surface.addMouseWheelListener(gestures)
            drawable = surface
            add(surface, BorderLayout.CENTER)
        } catch (_: RuntimeException) {
            reportFailure()
        } catch (_: LinkageError) {
            reportFailure()
        }
    }

    /** Renderer failure is delivered once on the Swing event thread, outside composition. */
    private fun reportFailure() {
        SwingUtilities.invokeLater {
            if (!failed) {
                failed = true
                input(SpatialRenderInput("error"))
            }
        }
    }

    fun updateScene(mesh: SpatialMeshSnapshot?, view: SpatialRenderCamera, callback: (SpatialRenderInput) -> Unit) {
        input = callback
        if (failed || (snapshot === mesh && camera == view)) return
        snapshot = mesh; camera = view
        renderer.mesh = mesh; renderer.camera = view
        drawable?.repaint()
    }
}

/** GL resources are scoped to the drawable's context and recreated on context loss. */
private class SpatialDesktopRenderer(private val failed: () -> Unit) : GLEventListener {
    @Volatile var mesh: SpatialMeshSnapshot? = null
    @Volatile var camera: SpatialRenderCamera? = null
    private var uploaded: SpatialMeshSnapshot? = null
    private var program = 0
    private var vao = 0
    private var buffer = 0
    private var count = 0
    private var aspect = 1f
    private var uniforms = IntArray(6)
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var optics: SpatialDesktopOptics? = null

    override fun init(drawable: GLAutoDrawable) {
        val gl = drawable.gl.getGL3()
        uploaded = null; count = 0; optics = null
        try {
            fun compile(type: Int, source: String): Int {
                val id = gl.glCreateShader(type)
                gl.glShaderSource(id, 1, arrayOf(spatialDesktopShader(source)), null as java.nio.IntBuffer?)
                gl.glCompileShader(id)
                val ok = IntArray(1)
                gl.glGetShaderiv(id, GL2ES2.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) {
                    gl.glDeleteShader(id)
                    error("Spatial shader compilation failed")
                }
                return id
            }
            fun link(vertexSource: String, fragmentSource: String): Int {
                val vertex=compile(GL2ES2.GL_VERTEX_SHADER,vertexSource)
                val fragment=try { compile(GL2ES2.GL_FRAGMENT_SHADER,fragmentSource) }
                    catch(e: Exception) { gl.glDeleteShader(vertex); throw e }
                val p=gl.glCreateProgram()
                gl.glAttachShader(p,vertex);gl.glAttachShader(p,fragment);gl.glLinkProgram(p)
                gl.glDeleteShader(vertex);gl.glDeleteShader(fragment)
                val ok=IntArray(1);gl.glGetProgramiv(p,GL2ES2.GL_LINK_STATUS,ok,0)
                if(ok[0]==0) { gl.glDeleteProgram(p);error("Spatial shader link failed") }
                return p
            }
            program=link(SPATIAL_VERTEX_SHADER,SPATIAL_FRAGMENT_SHADER)
            val ids = IntArray(1)
            gl.glGenVertexArrays(1, ids, 0); vao = ids[0]
            gl.glBindVertexArray(vao)
            gl.glGenBuffers(1, ids, 0); buffer = ids[0]
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
            listOf("p", "n", "c").forEachIndexed { i, name ->
                val loc = gl.glGetAttribLocation(program, name)
                require(loc >= 0)
                gl.glEnableVertexAttribArray(loc)
                gl.glVertexAttribPointer(loc, 3, GL.GL_FLOAT, false, SPATIAL_VERTEX_FLOATS * 4, i * 12L)
            }
            uniforms = listOf("eye", "forward", "right", "up", "aspect", "motionPhase").map {
                gl.glGetUniformLocation(program, it)
            }.toIntArray()
            gl.glEnable(GL.GL_DEPTH_TEST)
            optics=runCatching { SpatialDesktopOptics(gl,link(SPATIAL_OPTICS_VERTEX_SHADER,SPATIAL_OPTICS_FRAGMENT_SHADER)) }.getOrNull()
        } catch (_: RuntimeException) {
            dispose(drawable)
            failed()
        }
    }

    override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
        drawable.gl.glViewport(x, y, width, height)
        viewportWidth=width.coerceAtLeast(1); viewportHeight=height.coerceAtLeast(1)
        aspect=viewportWidth.toFloat()/viewportHeight
    }

    override fun display(drawable: GLAutoDrawable) {
        val gl = drawable.gl.getGL3()
        val view=camera
        val pass=if(view!=null && program!=0) optics?.takeIf { it.begin(gl,viewportWidth,viewportHeight) } else null
        gl.glClearColor(.012f, .045f, .047f, 1f)
        gl.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        if(view==null || program==0) return
        val current = mesh
        if (uploaded !== current) {
            if (current != null) {
                val data = current.vertices
                val bytes = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                bytes.put(data); bytes.flip()
                gl.glBindBuffer(GL.GL_ARRAY_BUFFER, buffer)
                gl.glBufferData(GL.GL_ARRAY_BUFFER, data.size * 4L, bytes, GL.GL_STATIC_DRAW)
                count = data.size / SPATIAL_VERTEX_FLOATS
            } else count = 0
            uploaded = current
        }
        gl.glUseProgram(program)
        gl.glBindVertexArray(vao)
        val basis = view.basis()
        listOf(basis.eye, basis.forward, basis.right, basis.up).forEachIndexed { i, p ->
            gl.glUniform3f(uniforms[i], p.x, p.y, p.z)
        }
        gl.glUniform1f(uniforms[4], aspect)
        gl.glUniform1f(uniforms[5], view.motionPhase)
        gl.glDrawArrays(GL.GL_TRIANGLES, 0, count)
        pass?.finish(gl,view.optics())
    }

    override fun dispose(drawable: GLAutoDrawable) {
        val gl = drawable.gl.getGL3()
        optics?.dispose(gl); optics=null
        if (buffer != 0) gl.glDeleteBuffers(1, intArrayOf(buffer), 0)
        if (vao != 0) gl.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (program != 0) gl.glDeleteProgram(program)
        buffer = 0; vao = 0; program = 0; uploaded = null; count = 0
    }
}


/** Context-local color/depth target and a bounded depth-of-field pass. A failed target uses direct 3D. */
private class SpatialDesktopOptics(initialGl: GL3, private val program: Int) {
    private var framebuffer=0
    private var textures=IntArray(2)
    private var width=0
    private var height=0
    private var ready=false
    private var previousFramebuffer=IntArray(1)
    private var previousViewport=IntArray(4)
    private val uniforms=listOf("sceneColor","sceneDepth","focusDepth","aperture").map { initialGl.glGetUniformLocation(program,it) }.toIntArray()

    /** Preserve the drawable's framebuffer, especially GLJPanel's nonzero backing target. */
    fun begin(gl: GL3, w: Int,h: Int): Boolean {
        gl.glGetIntegerv(0x8CA6,previousFramebuffer,0)
        gl.glGetIntegerv(GL.GL_VIEWPORT,previousViewport,0)
        if(w!=width || h!=height) {
            releaseTargets(gl)
            width=w;height=h
            try {
                val ids=IntArray(1)
                gl.glGenFramebuffers(1,ids,0);framebuffer=ids[0]
                gl.glGenTextures(2,textures,0)
                gl.glActiveTexture(GL.GL_TEXTURE0)
                fun allocate(id: Int,internal: Int,format: Int,type: Int,filter: Int) {
                    gl.glBindTexture(GL.GL_TEXTURE_2D,id)
                    gl.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,filter)
                    gl.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,filter)
                    gl.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
                    gl.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
                    gl.glTexImage2D(GL.GL_TEXTURE_2D,0,internal,w,h,0,format,type,null as java.nio.Buffer?)
                }
                allocate(textures[0],0x8058,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,GL.GL_LINEAR)
                allocate(textures[1],0x81A6,0x1902,GL.GL_UNSIGNED_INT,GL.GL_NEAREST)
                gl.glBindTexture(GL.GL_TEXTURE_2D,0)
                gl.glBindFramebuffer(0x8CA9,framebuffer)
                gl.glFramebufferTexture2D(0x8CA9,GL.GL_COLOR_ATTACHMENT0,GL.GL_TEXTURE_2D,textures[0],0)
                gl.glFramebufferTexture2D(0x8CA9,GL.GL_DEPTH_ATTACHMENT,GL.GL_TEXTURE_2D,textures[1],0)
                ready=gl.glCheckFramebufferStatus(0x8CA9)==GL.GL_FRAMEBUFFER_COMPLETE
            } catch(_: RuntimeException) { ready=false }
            if(!ready)releaseTargets(gl)
        }
        gl.glBindFramebuffer(0x8CA9,if(ready) framebuffer else previousFramebuffer[0])
        if(ready)gl.glViewport(0,0,w,h)
        return ready
    }

    fun finish(gl: GL3, params: SpatialOptics) {
        gl.glBindFramebuffer(0x8CA9,previousFramebuffer[0])
        gl.glViewport(previousViewport[0],previousViewport[1],previousViewport[2],previousViewport[3])
        gl.glDisable(GL.GL_DEPTH_TEST)
        gl.glUseProgram(program)
        gl.glActiveTexture(GL.GL_TEXTURE0);gl.glBindTexture(GL.GL_TEXTURE_2D,textures[0])
        gl.glUniform1i(uniforms[0],0)
        gl.glActiveTexture(GL.GL_TEXTURE1);gl.glBindTexture(GL.GL_TEXTURE_2D,textures[1])
        gl.glUniform1i(uniforms[1],1)
        gl.glUniform1f(uniforms[2],params.focusDepth)
        gl.glUniform1f(uniforms[3],params.aperture)
        gl.glDrawArrays(GL.GL_TRIANGLES,0,3)
        gl.glBindTexture(GL.GL_TEXTURE_2D,0);gl.glActiveTexture(GL.GL_TEXTURE0)
        gl.glBindTexture(GL.GL_TEXTURE_2D,0)
        gl.glEnable(GL.GL_DEPTH_TEST)
    }

    private fun releaseTargets(gl: GL3) {
        if(framebuffer!=0)gl.glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        if(textures.any { it!=0 })gl.glDeleteTextures(2,textures,0)
        framebuffer=0;textures=IntArray(2);ready=false
    }

    fun dispose(gl: GL3) {
        releaseTargets(gl)
        gl.glDeleteProgram(program)
    }
}
