package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Native GPU implementations remain outstanding. Do not substitute a planar image for the spatial renderer.
internal actual val spatialHiveSupported: Boolean get() = false

@Composable
internal actual fun SpatialHiveSurface(frame: SpatialRenderFrame, camera: SpatialRenderCamera,
    onInput: (SpatialRenderInput) -> Unit, modifier: Modifier) = Unit
