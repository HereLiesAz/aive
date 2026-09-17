package com.hereliesaz.geministrator

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

private external object HaiveNodeCreatures : JsAny {
    val ready: Boolean

    fun renderPacketBase64(
        roleLabel: String,
        identitySeed: String,
        activityCode: Int,
        timeSeconds: Float,
        cameraYaw: Float,
        cameraPitch: Float,
        cameraZoom: Float,
    ): String
}

@OptIn(ExperimentalEncodingApi::class, ExperimentalWasmJsInterop::class)
internal actual fun platformNodeCreatureRenderEngine(): NodeCreatureRenderEngine? =
    NodeCreatureRenderEngine { request ->
        check(HaiveNodeCreatures.ready) { "Rust node-creature renderer is still initializing." }
        Base64.Default.decode(
            HaiveNodeCreatures.renderPacketBase64(
                request.roleLabel,
                request.identitySeed,
                request.activity.abiCode,
                request.timeSeconds,
                request.camera.yaw,
                request.camera.pitch,
                request.camera.zoom,
            ),
        )
    }
