package com.hereliesaz.geministrator

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopArtifactFolderServiceTest {
    private val service = DesktopArtifactFolderService()
    private fun folder() = Files.createTempDirectory("aive-artifacts").toFile()

    @Test
    fun writesIntoTheRunsSubfolderAndNeverOverwrites(): Unit = runBlocking {
        val root = folder()
        assertEquals("Implement - plan.md", service.write(root.path, "run-1 Hive", "Implement - plan.md", "one"))
        assertEquals("Implement - plan (2).md", service.write(root.path, "run-1 Hive", "Implement - plan.md", "two"))
        assertEquals("one", root.resolve("run-1 Hive/Implement - plan.md").readText())
        assertEquals("two", root.resolve("run-1 Hive/Implement - plan (2).md").readText())
    }

    @Test
    fun aNameThatReachesOutsideTheFolderIsRefused(): Unit = runBlocking {
        val root = folder()
        assertNull(service.write(root.path, "../escape", "x.md", "no"))
        assertNull(service.write(root.path, "run", "../../x.md", "no"))
        assertTrue(root.parentFile.resolve("escape").exists().not())
    }

    @Test
    fun aMissingFolderIsAFailureNotAnExceptionOrANewTree(): Unit = runBlocking {
        val gone = folder().also { it.deleteRecursively() }
        assertNull(service.write(gone.path, "run", "x.md", "no"))
        assertTrue(!gone.exists())
    }
}
