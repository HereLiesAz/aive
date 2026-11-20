package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.memory.MemoryWebSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryWebExportTest {
    private class Recorder(var failing: Set<String> = emptySet()) : ArtifactFolderService {
        val written = mutableListOf<Triple<String, String, String>>()
        override suspend fun chooseFolder() = ArtifactFolderRef("/vault", "vault")
        override suspend fun write(folder: String, subfolder: String, fileName: String, content: String): String? {
            if (fileName in failing) return null
            written += Triple(folder, subfolder, fileName)
            return fileName
        }
    }

    private val source = MemoryWebSource(
        MemorySnapshot(
            nodes = listOf(
                MemoryNode(MemoryNodeId("a"), MemoryNodeKind.Summary, "Builds fail on Tuesdays", createdAtEpochMillis = 1),
                MemoryNode(MemoryNodeId("b"), MemoryNodeKind.NounTag, "build", createdAtEpochMillis = 2),
            ),
        ),
    )

    @Test
    fun withNoFolderChosenNothingIsWrittenAndItSaysSo(): Unit = runBlocking {
        ArtifactFolderSettings.setFolder(MemoryWebExport.FOLDER_KEY, null)
        ArtifactFolderHost.service = Recorder()
        assertTrue(MemoryWebExport.export(source, "Memory Web x").noFolder)
    }

    @Test
    fun everyNoteIsWrittenAsItsTitleInOneSubfolderAndFailuresAreCounted(): Unit = runBlocking {
        val recorder = Recorder().also { ArtifactFolderHost.service = it }
        ArtifactFolderSettings.setFolder(MemoryWebExport.FOLDER_KEY, ArtifactFolderRef("/vault", "vault"))
        val ok = MemoryWebExport.export(source, MemoryWebExport.subfolderFor("2026-11-20 18-05"))
        assertEquals(2, ok.written)
        assertEquals(setOf("/vault"), recorder.written.map { it.first }.toSet())
        assertEquals(setOf("Memory Web 2026-11-20 18-05"), recorder.written.map { it.second }.toSet())
        assertEquals(setOf("Builds fail on Tuesdays.md", "build.md"), recorder.written.map { it.third }.toSet())

        recorder.failing = setOf("build.md")
        val partial = MemoryWebExport.export(source, "Memory Web again")
        assertEquals(1, partial.written)
        assertEquals(1, partial.failed)
        ArtifactFolderSettings.setFolder(MemoryWebExport.FOLDER_KEY, null)
    }

    @Test
    fun theStampIsAUtcDateAndMinute() {
        assertEquals("1970-01-01 00-00", MemoryWebExport.stampOf(0L))
        assertEquals("2025-11-20 18-05", MemoryWebExport.stampOf(1_763_661_900_000L))
        // A leap day, to the last minute of it.
        assertEquals("2000-02-29 23-59", MemoryWebExport.stampOf(951_868_799_000L))
        assertEquals("Memory Web 2025-11-20 18-05", MemoryWebExport.subfolderFor("2025-11-20 18-05"))
    }
}
