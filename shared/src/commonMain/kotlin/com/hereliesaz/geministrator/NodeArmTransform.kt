package com.hereliesaz.geministrator

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin

internal data class NodeArmTransform(
    val leftPx: Float,
    val topPx: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotationDegrees: Float,
    val alpha: Float,
)

internal fun calculateNodeArmTransform(
    asset: NodeArmAsset,
    socket: Offset,
    toward: Offset,
    baseWidthPx: Float,
    baseHeightPx: Float,
    zoom: Float,
    pulse: Float,
    active: Boolean,
    blocked: Boolean,
): NodeArmTransform? {
    val direction = toward - socket
    val distance = direction.getDistance()
    if (distance <= 0.5f) return null

    val sourceSpanPx = baseWidthPx * (asset.terminalPivotX - asset.socketPivotX)
    val uniformScale = zoom.coerceAtLeast(0.01f)
    val longitudinalStretch = (
        distance / (sourceSpanPx * uniformScale).coerceAtLeast(1f)
        ).coerceIn(0.22f, 6f)
    val angle = (
        atan2(direction.y.toDouble(), direction.x.toDouble()) * 180.0 / PI
        ).toFloat()
    val wave = sin((pulse * (2f * PI.toFloat())).toDouble()).toFloat()
    val wobble = when {
        blocked -> wave * 2.4f
        active -> wave * 1.25f
        else -> 0f
    }
    val breathe = if (active) 1f + wave * 0.035f else 1f

    return NodeArmTransform(
        leftPx = socket.x - baseWidthPx * asset.socketPivotX,
        topPx = socket.y - baseHeightPx * asset.socketPivotY,
        scaleX = uniformScale * longitudinalStretch,
        scaleY = uniformScale * breathe,
        rotationDegrees = angle + wobble,
        alpha = if (blocked) 0.72f else 0.96f,
    )
}
