package com.hereliesaz.geministrator.puppet

import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.geministrator.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

/** A rig plus its decoded atlas, loaded from compose resources. */
class LoadedPuppetRig(val rig: PuppetRig, val atlas: ImageBitmap)

/**
 * Loads `files/rigs/<role>.rig.json` and the atlas it names (`atlas.image`, resolved as
 * `files/rigs/<atlas.image>`) from compose resources. See `docs/architecture/PUPPET_RIG_FORMAT.md`.
 */
object PuppetRigResources {
    const val DIRECTORY = "files/rigs"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(role: String): LoadedPuppetRig {
        val rig = PuppetRig.parse(Res.readBytes("$DIRECTORY/$role.rig.json").decodeToString())
        val atlas = Res.readBytes("$DIRECTORY/${rig.atlas.image}").decodeToImageBitmap()
        return LoadedPuppetRig(rig, atlas)
    }
}
