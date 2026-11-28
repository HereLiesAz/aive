@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)
package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.js
import kotlin.io.encoding.Base64

private fun spatialAvailable(): Boolean = js("() => !!globalThis.AiveSpatialGpu && globalThis.AiveSpatialGpu.available()")
private fun attach(canvas: HTMLCanvasElement, vertex: String, fragment: String, opticsVertex: String, opticsFragment: String): Unit = js("(canvas,vertex,fragment,opticsVertex,opticsFragment) => globalThis.AiveSpatialGpu.attach(canvas,vertex,fragment,opticsVertex,opticsFragment)")
private fun detach(canvas: HTMLCanvasElement): Unit = js("(canvas) => globalThis.AiveSpatialGpu.detach(canvas)")
private fun upload(canvas: HTMLCanvasElement, vertices: String): Unit = js("(canvas,vertices) => globalThis.AiveSpatialGpu.frame(canvas,vertices)")
private fun view(canvas: HTMLCanvasElement, camera: String, focus: Float, aperture: Float): Unit = js("(canvas,camera,focus,aperture) => globalThis.AiveSpatialGpu.camera(canvas,camera,focus,aperture)")
private fun poll(canvas: HTMLCanvasElement): String? = js("(canvas) => globalThis.AiveSpatialGpu.poll(canvas)")
private val codec = Json { encodeDefaults = true }

internal actual val spatialHiveSupported: Boolean get() = spatialAvailable()

/** Mounts a single direct WebGL canvas. All application and camera state stays in Compose. */
@Composable
internal actual fun SpatialHiveSurface(frame: SpatialRenderFrame, camera: SpatialRenderCamera,
    onInput: (SpatialRenderInput) -> Unit, modifier: Modifier, lights: List<SpatialLight>) {
    // The browser surface does not draw the signal lights yet.
    var canvas by remember { mutableStateOf<HTMLCanvasElement?>(null) }
    val input by rememberUpdatedState(onInput)
    val mesh by rememberSpatialMesh(frame)
    val currentCamera by rememberUpdatedState(camera)
    DisposableEffect(canvas) {
        val mounted = canvas
        onDispose { mounted?.let(::detach) }
    }
    LaunchedEffect(canvas, mesh) {
        val mounted = canvas ?: return@LaunchedEffect
        val snapshot = mesh ?: return@LaunchedEffect
        val bytes = ByteArray(snapshot.vertices.size * 4)
        snapshot.vertices.forEachIndexed { i, value ->
            val bits = value.toBits()
            for (j in 0..3) bytes[i * 4 + j] = (bits ushr (j * 8)).toByte()
        }
        upload(mounted, Base64.Default.encode(bytes))
    }
    LaunchedEffect(canvas, camera) {
        val optics = camera.optics()
        canvas?.let { view(it, codec.encodeToString(camera), optics.focusDepth, optics.aperture) }
    }
    LaunchedEffect(canvas) {
        val mounted = canvas ?: return@LaunchedEffect
        while (true) {
            repeat(128) {
                val event = poll(mounted) ?: return@repeat
                val request = runCatching { codec.decodeFromString<SpatialRenderInput>(event) }.getOrNull() ?: return@repeat
                if (request.type == "tap") {
                    val picked = mesh?.frame?.pick(currentCamera, request.x, request.y, request.z)
                    input(picked?.let { SpatialRenderInput(if (it.form == "Spore") "memory" else "agent", it.id) } ?: SpatialRenderInput("empty"))
                } else input(request)
            }
            delay(16)
        }
    }
    WebElementView(factory = {
        (document.createElement("canvas") as HTMLCanvasElement).apply {
            setAttribute("aria-label", "The Aive spatial hive")
            setAttribute("tabindex", "0")
            style.backgroundColor = "#031414"
            style.touchAction = "none"
            attach(this, SPATIAL_VERTEX_SHADER, SPATIAL_FRAGMENT_SHADER, SPATIAL_OPTICS_VERTEX_SHADER, SPATIAL_OPTICS_FRAGMENT_SHADER)
            canvas = this
        }
    }, modifier = modifier)
}
