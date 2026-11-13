package com.hereliesaz.geministrator.persistence

import com.russhwolf.settings.MapSettings
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkedStringSettingsConcurrencyTest {
    @Test
    fun concurrentWritersLeaveOneCompleteGenerationAndNoStrayChunks() {
        val delegate = MapSettings()
        val settings = ChunkedStringSettings(delegate, setOf("k"), chunkSize = 4)
        val values = (0 until 8).map { writer -> "writer-$writer-".repeat(6) }
        val threads = values.map { value -> thread { repeat(60) { settings.putString("k", value) } } }
        threads.forEach { it.join() }

        val final = settings.getStringOrNull("k")
        assertTrue(final in values, "the stored value is one writer's whole value, not a mix: $final")
        val expectedChunks = (final!!.length + 3) / 4
        // the manifest plus exactly the live generation's chunks
        assertEquals(1 + expectedChunks, delegate.keys.size)
    }
}
