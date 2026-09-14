package com.hereliesaz.geministrator.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RepositoryRefSerializationTest {
    @Test
    fun legacySerializedRepositoryDefaultsToGitHub() {
        val repository = Json.decodeFromString<RepositoryRef>(
            """{"owner":"HereLiesAz","name":"haive","defaultBranch":"main"}""",
        )

        assertEquals(RepositorySource.GitHub, repository.source)
        assertEquals("HereLiesAz", repository.owner)
        assertEquals("haive", repository.name)
        assertEquals("main", repository.defaultBranch)
        assertNull(repository.remoteUrl)
        assertNull(repository.localPath)
    }
}
