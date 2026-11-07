package com.hereliesaz.geministrator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SomaColourTest {
    private val out = File("build/soma-colours").also { it.mkdirs() }

    @Test
    fun everyRigWithABodyHasAColourAndTheyDiffer() = runBlocking {
        val colours = linkedMapOf<String, androidx.compose.ui.graphics.Color?>()
        CreatureRigMapping.allSlugs.sorted().forEach { slug ->
            val loaded = checkNotNull(CreatureRigCache.get(slug)) { "rig $slug did not load" }
            val hasSoma = loaded.rig.parts.any { it.id == "soma" && it.rect != null }
            val colour = somaColour(loaded)
            if (hasSoma) assertTrue(colour != null, "$slug has a soma but no colour")
            colours[slug] = colour
        }
        File(out, "soma-colours.txt").writeText(
            colours.entries.joinToString("\n") { (slug, c) ->
                "$slug ${c?.let { "#%02X%02X%02X".format((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }}"
            },
        )
        val distinct = colours.values.filterNotNull().map { Triple((it.red * 8).toInt(), (it.green * 8).toInt(), (it.blue * 8).toInt()) }.toSet()
        assertTrue(distinct.size >= 4, "rigs should not all share one body colour: ${distinct.size}")
    }
}
