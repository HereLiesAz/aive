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

private fun spatialAvailable(): Boolean = js("() => !!globalThis.AiveSpatialGpu && globalThis.AiveSpatialGpu.available()")
private fun attach(canvas: HTMLCanvasElement): Unit = js("(canvas) => globalThis.AiveSpatialGpu.attach(canvas)")
private fun detach(canvas: HTMLCanvasElement): Unit = js("(canvas) => globalThis.AiveSpatialGpu.detach(canvas)")
private fun upload(canvas: HTMLCanvasElement, frame: String): Unit = js("(canvas,frame) => globalThis.AiveSpatialGpu.frame(canvas,frame)")
private fun view(canvas: HTMLCanvasElement, camera: String): Unit = js("(canvas,camera) => globalThis.AiveSpatialGpu.camera(canvas,camera)")
private fun poll(canvas: HTMLCanvasElement): String? = js("(canvas) => globalThis.AiveSpatialGpu.poll(canvas)")
private val codec = Json { encodeDefaults = true }

internal actual val spatialHiveSupported: Boolean get() = spatialAvailable()

/** Mounts a single direct WebGL canvas. All application and camera state stays in Compose. */
@Composable
internal actual fun SpatialHiveSurface(frame: SpatialRenderFrame, camera: SpatialRenderCamera,
    onInput: (SpatialRenderInput) -> Unit, modifier: Modifier) {
    var canvas by remember { mutableStateOf<HTMLCanvasElement?>(null) }
    val input by rememberUpdatedState(onInput)
    DisposableEffect(canvas) {
        val mounted = canvas
        onDispose { mounted?.let(::detach) }
    }
    LaunchedEffect(canvas, frame) { canvas?.let { upload(it, codec.encodeToString(frame)) } }
    LaunchedEffect(canvas, camera) { canvas?.let { view(it, codec.encodeToString(camera)) } }
    LaunchedEffect(canvas) {
        val mounted = canvas ?: return@LaunchedEffect
        while (true) {
            repeat(128) {
                val event = poll(mounted) ?: return@repeat
                runCatching { codec.decodeFromString<SpatialRenderInput>(event) }.getOrNull()?.let(input)
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
            attach(this)
            canvas = this
        }
    }, modifier = modifier)
}
