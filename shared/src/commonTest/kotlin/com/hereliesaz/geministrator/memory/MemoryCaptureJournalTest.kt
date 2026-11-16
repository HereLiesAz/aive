package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryCaptureJournalTest {
    /** A Settings that refuses a value over 8,192 characters, as desktop's java.util.prefs does. */
    private class LimitedSettings(private val inner: MapSettings = MapSettings()) : Settings by inner {
        override fun putString(key: String, value: String) {
            require(value.length <= 8_192) { "value too long: ${value.length}" }
            inner.putString(key, value)
        }
    }

    private fun envelope(id: String, text: String) = MemorySessionEnvelope(
        sourceSessionId = id,
        workflowRunId = "wf",
        userPrompt = "prompt",
        parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent message", text)),
        closedAtEpochMillis = 1L,
        sequence = 0,
    )

    @Test
    fun aJournalLargerThanThePreferencesLimitIsWrittenAndReadBack() = runBlocking<Unit> {
        val journal = SettingsMemoryCaptureJournal(LimitedSettings())
        journal.record("a", envelope("a", "x".repeat(30_000)))
        assertEquals(30_000, journal.pending().getValue("a").parts.single().text.length)
    }

    @Test
    fun aPlainValueAnOlderBuildWroteIsStillRead() = runBlocking<Unit> {
        val settings = MapSettings()
        val raw = MapSettings()
        val writer = SettingsMemoryCaptureJournal(raw, "aive.memory.capture-journal.v1")
        writer.record("b", envelope("b", "kept"))
        val plain = raw.getString("aive.memory.capture-journal.v1", "")
        settings.putString("aive.memory.capture-journal.v1", plain)
        assertTrue("b" in SettingsMemoryCaptureJournal(settings).pending())
    }

    @Test
    fun aCorruptJournalIsKeptAsideAndDoesNotWipeTheNextWrite() = runBlocking<Unit> {
        val settings = MapSettings()
        settings.putString(SettingsMemoryCaptureJournal.DEFAULT_KEY, "{not json")
        val journal = SettingsMemoryCaptureJournal(settings)
        journal.record("a", envelope("a", "fresh"))
        assertEquals(setOf("a"), journal.pending().keys)
        assertEquals(
            "{not json",
            settings.getStringOrNull(SettingsMemoryCaptureJournal.corruptKey(SettingsMemoryCaptureJournal.DEFAULT_KEY)),
        )
    }

    @Test
    fun anOverLimitWriteRemovesThatSessionsStaleEnvelopeButKeepsOthers() = runBlocking<Unit> {
        val journal = SettingsMemoryCaptureJournal(MapSettings(), maxChars = 2_000)
        journal.record("a", envelope("a", "small"))
        journal.record("b", envelope("b", "other"))
        journal.record("a", envelope("a", "y".repeat(5_000)))
        val pending = journal.pending()
        assertEquals(setOf("b"), pending.keys)
        assertNotNull(pending["b"])
    }
}
