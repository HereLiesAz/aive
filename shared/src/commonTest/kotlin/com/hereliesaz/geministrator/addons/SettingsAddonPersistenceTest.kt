package com.hereliesaz.geministrator.addons

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsAddonPersistenceTest {
    @Test
    fun installationsRoundTripAndCanBeDisabled() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsAddonPersistence(settings)
        val installation = AddonInstallation(
            id = "com.example.workflow",
            version = "1.2.3",
            enabled = true,
            grantedPermissions = setOf(HostPermission.AppRead),
            settings = mapOf("mode" to "safe"),
            importedWorkflowIds = listOf("review"),
        )

        persistence.saveInstallation(installation)
        assertEquals(listOf(installation), persistence.getInstallations())

        persistence.saveInstallation(installation.copy(enabled = false))
        val reloaded = SettingsAddonPersistence(settings).getInstallations().single()
        assertFalse(reloaded.enabled)
        assertEquals("1.2.3", reloaded.version)
    }

    @Test
    fun removeDeletesActiveInstallation() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsAddonPersistence(settings)
        persistence.saveInstallation(
            AddonInstallation(
                id = "com.example.workflow",
                version = "1.0.0",
                enabled = true,
                grantedPermissions = emptySet(),
                settings = emptyMap(),
                importedWorkflowIds = emptyList(),
            ),
        )

        persistence.removeInstallation("com.example.workflow")

        assertTrue(persistence.getInstallations().isEmpty())
    }

    @Test
    fun corruptedInstallationStateFailsClosedInsteadOfPretendingNothingIsInstalled() = runBlocking {
        val settings = MapSettings()
        settings.putString("addon_state", "not-json")
        val persistence = SettingsAddonPersistence(settings)

        assertFailsWith<AddonPersistenceCorruptionException> {
            persistence.getInstallations()
        }
        Unit
    }

    @Test
    fun corruptedStateDoesNotSilentlySucceedOnRemove() = runBlocking {
        val settings = MapSettings()
        val persistence = SettingsAddonPersistence(settings)
        val installation = AddonInstallation(
            id = "com.example.workflow",
            version = "1.0.0",
            enabled = true,
            grantedPermissions = emptySet(),
            settings = emptyMap(),
            importedWorkflowIds = emptyList(),
        )
        persistence.saveInstallation(installation)
        // The single key holds both installations and history; corrupt the whole blob
        settings.putString("addon_state", "not-json")

        // removeInstallation must throw rather than silently succeed on corrupt state
        assertFailsWith<AddonPersistenceCorruptionException> {
            persistence.removeInstallation(installation.id)
        }
        Unit
    }
}
