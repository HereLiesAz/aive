package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A direct GPU drawing surface owned by the composition, not an embedded application. */
internal expect val spatialHiveSupported: Boolean

@Composable
internal expect fun SpatialHiveSurface(
    frame: SpatialRenderFrame,
    camera: SpatialRenderCamera,
    onInput: (SpatialRenderInput) -> Unit,
    modifier: Modifier = Modifier,
)
