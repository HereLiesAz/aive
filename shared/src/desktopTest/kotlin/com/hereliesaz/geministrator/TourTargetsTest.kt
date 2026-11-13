package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.tour.TourTargetId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The registry is the enum; this keeps it honest against the real controls and the real strings. */
class TourTargetsTest {
    private val kotlinRoot = File("src/commonMain/kotlin")
    private val stringsDir = File("src/commonMain/composeResources/values")

    @Test
    fun everyRegisteredIdIsTaggedOnAControlInCommonMain() {
        assertTrue(kotlinRoot.isDirectory, "sources not found from ${File(".").absolutePath}")
        val sources = kotlinRoot.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "TourTarget.kt" }
            .joinToString("\n") { it.readText() }
        val untagged = TourTargetId.entries.filter { !sources.contains("tourTarget(TourTargetId.${it.name}") }.map { it.name }
        assertEquals(emptyList<String>(), untagged, "ids with no Modifier.tourTarget on a control")
    }

    @Test
    fun everyLabelKeyIsARealString() {
        val keys = stringsDir.listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { f ->
            Regex("""<string\s+name="([^"]+)"""").findAll(f.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        val missing = TourTargetId.entries.filter { it.labelKey !in keys }.map { "${it.name}:${it.labelKey}" }
        assertEquals(emptyList<String>(), missing)
    }
}
