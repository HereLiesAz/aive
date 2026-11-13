package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.retrieval.ContextChunkSource
import com.hereliesaz.geministrator.retrieval.TextChunk
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResearchWiringTest {
    @Test
    fun researchIsOnByDefaultAndTheSwitchPersists() {
        val store = MapSettings()
        assertTrue(ResearchSettings(store).enabled.value)
        ResearchSettings(store).setEnabled(false)
        assertFalse(ResearchSettings(store).enabled.value)
    }

    @Test
    fun docPathKeepsOnlyReadmeAndDocsFiles() {
        assertEquals("README.md", ChunkSourceDocNames.docPath("Repository file README.md"))
        assertEquals("docs/Guide.md", ChunkSourceDocNames.docPath("Repository file docs/Guide.md (part 2)"))
        assertNull(ChunkSourceDocNames.docPath("Repository file src/Main.kt"))
        assertNull(ChunkSourceDocNames.docPath("Upstream artifact notes"))
    }

    @Test
    fun namesAreEmptyWithoutALinkedRepositoryAndDistinctWithOne() = runTest {
        val source = ContextChunkSource {
            listOf(
                TextChunk("a", "Repository file README.md", "x"),
                TextChunk("b", "Repository file README.md (part 2)", "y"),
                TextChunk("c", "Repository file src/App.kt", "z"),
            )
        }
        assertTrue(ChunkSourceDocNames(listOf(source)) { null }.names().isEmpty())
        val linked = RepositoryRef(owner = "o", name = "n")
        assertEquals(listOf("README.md"), ChunkSourceDocNames(listOf(source)) { linked }.names())
    }

    @Test
    fun aFailingSourceContributesNothing() = runTest {
        val failing = ContextChunkSource { error("offline") }
        val linked = RepositoryRef(owner = "o", name = "n")
        assertTrue(ChunkSourceDocNames(listOf(failing)) { linked }.names().isEmpty())
    }
}
