package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeCharacterAssetStoreTest {
    @Test
    fun recordsRoundTripAndReplaceByRole() = runTest {
        val store = SettingsNodeCharacterAssetStore(
            settings = MapSettings(),
            storageKey = "test.node-character.assets",
        )
        val roleId = RoleDefinitionId("archive-cartographer")
        val first = record(roleId, "source-a", "rig-a", 10L)
        val replacement = record(roleId, "source-b", "rig-b", 20L)

        store.put(first)
        assertEquals(first, store.get(roleId))

        store.put(replacement)
        assertEquals(replacement, store.get(roleId))
        assertEquals(listOf(replacement), store.all())

        store.remove(roleId)
        assertNull(store.get(roleId))
        assertEquals(emptyList(), store.all())
    }

    private fun record(
        roleId: RoleDefinitionId,
        source: String,
        rig: String,
        time: Long,
    ) = NodeCharacterAssetRecord(
        roleId = roleId,
        roleLabel = "Archive Cartographer",
        sourceWorkflowId = WorkflowDefinitionId("archive-workflow"),
        sourceCharacterArtifactId = ArtifactId(source),
        rigSheetArtifactId = ArtifactId(rig),
        generationPromptArtifactId = ArtifactId("prompt"),
        verificationArtifactId = ArtifactId("verification"),
        registeredAtEpochMillis = time,
    )
}
