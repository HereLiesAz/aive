package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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


/** Keep mesh and selection geometry together; an older mesh must not pick from a newer frame. */
internal data class SpatialMeshSnapshot(val frame: SpatialRenderFrame, val vertices: FloatArray)

/** Compute once per actual scene update, not once per camera animation frame. */
@Composable
internal fun rememberSpatialMesh(frame: SpatialRenderFrame): State<SpatialMeshSnapshot?> =
    produceState<SpatialMeshSnapshot?>(null, frame) {
        value = withContext(Dispatchers.Default) { SpatialMeshSnapshot(frame, frame.triangles()) }
    }
