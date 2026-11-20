package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.providers.cli.CliAgentSpec
import java.io.File
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which CLIs become providers. The settings store and the status/install/test host moved to `shared` (desktopMain); this stays with the provider. */
class ConfiguredCliProvidersTest {
    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun removePreferenceNodes() {
        nodes.forEach { node -> runCatching { node.removeNode(); node.flush() } }
        nodes.clear()
    }

    private fun settings(): CliAgentSettings {
        val node = Preferences.userRoot().node("com/hereliesaz/geministrator/test-cli-providers-${UUID.randomUUID()}").also { nodes += it }
        return CliAgentSettings { node }
    }

    @Test
    fun onlyACliThatIsOnAndInstalledJoinsTheProviderList() {
        val on = settings().also { it.setEnabled("cli-claude", true) }
        val installed = { spec: CliAgentSpec -> if (spec.id == "cli-claude") File("/usr/bin/claude") else null }
        assertEquals(listOf("cli-claude"), configuredCliProviders(on, installed).map { it.id.value }, "on and installed: registered")
        assertEquals(emptyList(), configuredCliProviders(on) { null }.map { it.id.value }, "on but not installed: not registered")
        assertEquals(emptyList(), configuredCliProviders(settings(), installed).map { it.id.value }, "installed but off: not registered")
    }
}
