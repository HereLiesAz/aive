package com.hereliesaz.geministrator.domain

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aTaskSavedBeforeContractsExistedStillLoadsWithoutOne() {
        val task = json.decodeFromString<TaskDefinition>(
            """{"id":{"value":"t"},"name":"n","objective":"o","roleId":null}""",
        )
        assertNull(task.contract)
    }

    @Test
    fun aContractRoundTrips() {
        val contract = TaskContract(
            template = TemplateRef("write-document", 2),
            inputs = mapOf("brief" to "A note"),
            allowedTools = listOf("read_file"),
            outputs = listOf(OutputSpec("document", ArtifactKind.Research, "text/markdown", "markdown/v1", "markdown-document")),
            effects = setOf(StepEffect.ReadsConnectedSource),
            tokenAllowance = 1000,
        )
        val task = TaskDefinition(TaskDefinitionId("t"), "n", "o", null, contract = contract)
        val again = json.decodeFromString<TaskDefinition>(json.encodeToString(TaskDefinition.serializer(), task))
        assertEquals(contract, again.contract)
    }

    @Test
    fun templateRefParsesIdAndVersion() {
        assertEquals(TemplateRef("make-page", 3), TemplateRef.parse("make-page@3"))
        assertEquals("make-page@3", TemplateRef("make-page", 3).key)
        assertNull(TemplateRef.parse("make-page"))
        assertNull(TemplateRef.parse("make-page@x"))
        assertNull(TemplateRef.parse("@2"))
        assertNull(TemplateRef.parse("make-page@0"))
    }

    @Test
    fun onlyEffectsThatChangeThingsAreEffectful() {
        assertFalse(setOf(StepEffect.None, StepEffect.ReadsConnectedSource).anyEffectful())
        assertTrue(setOf(StepEffect.WritesRepositoryBranch).anyEffectful())
        assertTrue(setOf(StepEffect.MutatesConnectedSource).mutatesConnectedSource())
        assertTrue(setOf(StepEffect.DestructiveConnectedSource).mutatesConnectedSource())
        assertFalse(setOf(StepEffect.WritesRepositoryBranch).mutatesConnectedSource())
    }
}
