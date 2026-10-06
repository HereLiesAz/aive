package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Headless render of the rigged terrarium and the full rig grid. PNGs land in
 * `shared/build/creature-rig-screens/` for eyeballing; the assertions only check that rigs loaded
 * and the frames are not blank.
 */
class CreatureRigRenderTest {
    private val out = File("build/creature-rig-screens").apply { mkdirs() }

    private fun preload() = runBlocking {
        CreatureRigMapping.allSlugs.forEach { slug ->
            assertTrue(CreatureRigCache.get(slug) != null, "rig $slug failed to load")
        }
    }

    private fun shoot(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene(width = width, height = height, density = Density(1.5f)) {
            Box(Modifier.fillMaxSize().background(Azphalt.currentGround.page).padding(12.dp)) { content() }
        }.use { scene ->
            scene.render(0L)
            val image = scene.render(700_000_000L)
            val bytes = image.encodeToData()!!.bytes
            assertTrue(bytes.size > 10_000, "$name looks blank")
            File(out, "$name.png").writeBytes(bytes)
        }
    }

    @Test
    fun rendersRiggedTerrariumAndGallery() {
        preload()
        CreatureRigSettings.enabled = true
        shoot("wired-terrarium", 1500, 1000) { CreatureRigTerrariumDemo(Modifier.fillMaxSize()) }
        shoot("wired-gallery", 1500, 2300) { CreatureRigGrid(columns = 7, cell = 128.dp) }
        CreatureRigSettings.enabled = false
        try {
            shoot("wired-terrarium-legacy", 1500, 1000) { CreatureRigTerrariumDemo(Modifier.fillMaxSize()) }
        } finally {
            CreatureRigSettings.enabled = true
        }
    }
}

