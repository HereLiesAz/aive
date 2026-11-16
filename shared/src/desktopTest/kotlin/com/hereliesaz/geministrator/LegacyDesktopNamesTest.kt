package com.hereliesaz.geministrator

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LegacyDesktopNamesTest {
    @Test
    fun legacyNamesAreDerivedFromTheCurrentNames() {
        assertEquals("com.hereliesaz.haive.a2a-server", LegacyDesktopNames.legacyService("com.hereliesaz.aive.a2a-server"))
        assertEquals("com.hereliesaz.haive", LegacyDesktopNames.legacyService("com.hereliesaz.aive"))
        assertEquals("com/hereliesaz/haive/control-api", LegacyDesktopNames.legacyPreferencesNode("com/hereliesaz/aive/control-api"))
    }

    @Test
    fun preferencesSavedUnderTheLegacyNodeMoveToTheCurrentNode() {
        val root = Preferences.userRoot()
        val suffix = "migration-test-${System.nanoTime()}"
        val currentPath = "com/hereliesaz/aive/$suffix"
        val legacyPath = LegacyDesktopNames.legacyPreferencesNode(currentPath)
        try {
            root.node(legacyPath).apply {
                put("enabled", "true")
                put("lan", "false")
            }
            root.node(currentPath).put("lan", "true")

            LegacyDesktopNames.migratePreferencesNode(currentPath, root)

            val current = root.node(currentPath)
            assertEquals("true", current.get("enabled", null))
            assertEquals("true", current.get("lan", null), "an entry already under the current name wins")
            assertFalse(root.nodeExists(legacyPath))
        } finally {
            runCatching { root.node(currentPath).removeNode() }
            runCatching { if (root.nodeExists(legacyPath)) root.node(legacyPath).removeNode() }
        }
    }

    @Test
    fun migratingWithNoLegacyNodeChangesNothing() {
        val root = Preferences.userRoot()
        val currentPath = "com/hereliesaz/aive/migration-test-empty-${System.nanoTime()}"
        try {
            LegacyDesktopNames.migratePreferencesNode(currentPath, root)
            assertNull(root.node(currentPath).get("enabled", null))
        } finally {
            runCatching { root.node(currentPath).removeNode() }
        }
    }
}
