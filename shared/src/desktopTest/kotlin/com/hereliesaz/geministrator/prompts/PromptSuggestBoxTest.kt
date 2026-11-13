package com.hereliesaz.geministrator.prompts

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.hereliesaz.geministrator.DurableUiStateStore
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/** The wrapper's contract: a seed lands once and is not an edit; a masked field or a switched-off store is inert. */
class PromptSuggestBoxTest {
    private fun newStore() = PromptHistoryStore(DurableUiStateStore(MapSettings()))

    private class Probe {
        var value by mutableStateOf("")
        var edits = 0
        var seeded = 0
    }

    private fun run(probe: Probe, seed: String?, secret: Boolean, store: PromptHistoryStore) {
        ImageComposeScene(width = 400, height = 200, density = Density(1f)) {
            PromptSuggestBox(
                kind = PromptKind.Objective,
                value = probe.value,
                onValueChange = { probe.edits++; probe.value = it },
                seed = seed,
                onSeeded = { probe.seeded++; probe.value = it },
                secret = secret,
                store = store,
            ) { field ->
                BasicTextField(value = field.value, onValueChange = field.onValueChange, modifier = field.modifier)
            }
        }.use { scene ->
            repeat(6) { scene.render(it * 16_000_000L) }
        }
    }

    @Test
    fun aSeedFillsAnEmptyFieldOnceAndIsNotAnEdit() {
        val probe = Probe()
        run(probe, seed = "Look through acme/app", secret = false, store = newStore())
        assertEquals("Look through acme/app", probe.value)
        assertEquals(1, probe.seeded)
        assertEquals(0, probe.edits)
    }

    @Test
    fun aSeedNeverReplacesTextAlreadyThere() {
        val probe = Probe().apply { value = "my own goal" }
        run(probe, seed = "Look through acme/app", secret = false, store = newStore())
        assertEquals("my own goal", probe.value)
        assertEquals(0, probe.seeded)
    }

    @Test
    fun aMaskedFieldIsNeverSeeded() {
        val probe = Probe()
        run(probe, seed = "sk-looks-like-a-key", secret = true, store = newStore())
        assertEquals("", probe.value)
        assertEquals(0, probe.seeded)
    }

    @Test
    fun withRememberingOffNothingIsSeeded() {
        val store = newStore().also { it.setEnabled(false) }
        val probe = Probe()
        run(probe, seed = "Look through acme/app", secret = false, store = store)
        assertEquals("", probe.value)
        assertEquals(0, probe.seeded)
    }
}
