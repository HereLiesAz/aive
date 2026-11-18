package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactBrowseTest {
    private fun entry(
        id: String,
        taskId: String,
        creature: String,
        final: Boolean = false,
        kind: ArtifactKind = ArtifactKind.Research,
        label: String = "label $id",
        uri: String? = null,
        text: String? = "text $id",
        mediaType: String? = null,
    ) = ArtifactEntry(
        taskId, creature, final,
        ArtifactRef(ArtifactId(id), kind, TaskRunId("run-$taskId"), label, uri, text, mediaType, createdAtEpochMillis = 0L),
    )

    private val entries = listOf(
        entry("a1", "t-research", "Scout", kind = ArtifactKind.Research),
        entry("a2", "t-build", "Builder", kind = ArtifactKind.CodeChange, uri = "https://github.com/x/y/pull/7", text = null),
        entry("a3", "t-research", "Scout", kind = ArtifactKind.Media, uri = "file:///tmp/pic.png", mediaType = "image/png"),
        entry("a4", "t-ship", "Shipper", final = true, kind = ArtifactKind.Release, uri = "https://example.com/r"),
    )

    @Test
    fun blankFilterKeepsEverything() {
        assertEquals(entries, entries.matching("   "))
    }

    @Test
    fun filterMatchesCreatureLabelKindUriAndMediaTypeIgnoringCase() {
        assertEquals(listOf("a1", "a3"), entries.matching("SCOUT").map { it.artifact.id.value })
        assertEquals(listOf("a2"), entries.matching("label a2").map { it.artifact.id.value })
        assertEquals(listOf("a2"), entries.matching("codechange").map { it.artifact.id.value })
        assertEquals(listOf("a2"), entries.matching("pull/7").map { it.artifact.id.value })
        assertEquals(listOf("a3"), entries.matching("image/png").map { it.artifact.id.value })
        assertTrue(entries.matching("nothing like this").isEmpty())
    }

    @Test
    fun groupsHoldTheResultFirstThenEachCreatureInTaskOrder() {
        val groups = entries.groupedByCreature()
        assertEquals(listOf("t-ship", "t-research", "t-build"), groups.map { it.taskId })
        assertEquals(listOf("a1", "a3"), groups.first { it.taskId == "t-research" }.entries.map { it.artifact.id.value })
        assertTrue(groups.first().isFinal)
    }

    @Test
    fun onlyWebAddressesOpen() {
        assertEquals("https://github.com/x/y/pull/7", artifactWebUri(entries[1].artifact))
        assertEquals("https://example.com/r", artifactWebUri(entries[3].artifact))
        assertNull(artifactWebUri(entries[2].artifact))
        assertNull(artifactWebUri(entries[0].artifact))
    }

    @Test
    fun aLongTextIsCutOnlyUntilShowAll() {
        val long = "x".repeat(ARTIFACT_PREVIEW_CHARS + 50)
        val cut = artifactPreview(long, expanded = false)
        assertTrue(cut.hasMore)
        assertTrue(cut.text.endsWith("…"))
        assertEquals(ARTIFACT_PREVIEW_CHARS + 1, cut.text.length)
        val all = artifactPreview(long, expanded = true)
        assertTrue(all.hasMore)
        assertEquals(long, all.text)
        val short = artifactPreview("  short  ", expanded = false)
        assertFalse(short.hasMore)
        assertEquals("short", short.text)
    }
}
