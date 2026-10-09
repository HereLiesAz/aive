package com.hereliesaz.aive

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidProjectFileServiceAtomicSaveTest {
    @Test
    fun failedReplacementLeavesPreviousProjectFileIntact() {
        val directory = Files.createTempDirectory("aive-project-save").toFile()
        val destination = directory.resolve("project.ive").apply { writeText("previous") }

        assertFailsWith<IllegalStateException> {
            writeProjectFileReplacement(
                destination = destination,
                content = "replacement",
                moveReplacement = { _, _ -> throw IllegalStateException("simulated move failure") },
            )
        }

        assertEquals("previous", destination.readText())
        assertTrue(
            directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
            "Temporary replacement must be cleaned after a failed move",
        )
    }

    @Test
    fun successfulReplacementOverwritesWithoutPreDeletingDestination() {
        val directory = Files.createTempDirectory("aive-project-save").toFile()
        val destination = directory.resolve("project.ive").apply { writeText("previous") }

        writeProjectFileReplacement(destination, "replacement")

        assertEquals("replacement", destination.readText())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
