package com.hereliesaz.aive

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SafeFileReplacementTest {
    @Test
    fun successfulReplacementInstallsCompleteNewContent() {
        val directory = Files.createTempDirectory("aive-project-save").toFile()
        try {
            val destination = File(directory, "project.ive").apply { writeText("old") }

            writeTextReplacingSafely(destination, "new project contents")

            assertEquals("new project contents", destination.readText())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fallbackRestoresOldDestinationWhenInstallingReplacementFails() {
        val directory = Files.createTempDirectory("aive-project-rollback").toFile()
        try {
            val destination = File(directory, "project.ive").apply { writeText("last good project") }
            val temporary = File(directory, "replacement.tmp").apply { writeText("new project") }
            var moves = 0

            assertFailsWith<IOException> {
                replaceFileSafely(
                    temporary = temporary,
                    destination = destination,
                    atomicReplace = { source, target ->
                        throw AtomicMoveNotSupportedException(
                            source.absolutePath,
                            target.absolutePath,
                            "test fallback",
                        )
                    },
                    replaceMove = { source, target ->
                        moves += 1
                        if (moves == 2) throw IOException("replacement failed")
                        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    },
                )
            }

            assertEquals("last good project", destination.readText())
            assertEquals(3, moves, "backup, failed install, then restore")
        } finally {
            directory.deleteRecursively()
        }
    }
}
