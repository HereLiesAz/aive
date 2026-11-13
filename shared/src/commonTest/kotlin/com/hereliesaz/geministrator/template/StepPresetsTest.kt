package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.workflow.StepOutputValidators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Presets are data over the built-in templates; the artifact set is accounted for, step by step. */
class StepPresetsTest {
    private fun artifact(text: String) = ArtifactRef(
        id = ArtifactId("a"), kind = ArtifactKind.Research, taskRunId = TaskRunId("r"), label = "x",
        textContent = text, createdAtEpochMillis = 0,
    )

    private fun check(id: String, text: String) = StepOutputValidators.check(id, artifact(text))

    @Test
    fun everyPresetIsItsOwnTemplateWithKnownChecks() {
        val everything = BuiltInTemplates.all + StepPresets.all
        assertEquals(everything.size, everything.map { it.id }.toSet().size)
        assertEquals(everything.size, BuiltInTemplates.byId.size)
        StepPresets.all.forEach { preset ->
            assertTrue(preset.outputs.isNotEmpty(), preset.id)
            preset.outputs.forEach { assertTrue(StepOutputValidators.isKnown(it.validatorId), "${preset.id}:${it.name}") }
            assertEquals(preset.outputs.associate { it.name to it.validatorId }, preset.body.validators, preset.id)
            assertTrue(preset.inputs.any { it.required }, preset.id)
        }
        assertEquals(everything.size, BuiltInTemplates.bases().size - BuiltInRoles.all.size)
    }

    @Test
    fun effectfulPresetsAskFirstAndTheQueenNeverMakesThem() {
        StepPresets.all.filter { it.effectful }.forEach { assertTrue(it.planGate, it.id) }
        assertEquals(setOf(StepEffect.DestructiveConnectedSource), StepPresets.RemoveInConnectedSource.effects)
        assertEquals(ToolPick.OneDestructive, StepPresets.RemoveInConnectedSource.toolPick)
        assertEquals(setOf(StepEffect.WritesRepositoryBranch), BuiltInTemplates.PrepareFilesForRepository.effects)
        assertTrue(BuiltInTemplates.PrepareFilesForRepository !in BuiltInTemplates.queenMayMake)
        assertTrue(BuiltInTemplates.MakeDiagram in BuiltInTemplates.queenMayMake)
        assertEquals(setOf(StepEffect.None), StepPresets.ReadRepositoryFiles.effects)
    }

    @Test
    fun aStepMadeFromAPresetPinsThePresetAndKeepsItsOutput() {
        val preset = StepPresets.ListConnectedSource
        val task = StepInstantiation.task(
            preset, preset.base(), TaskDefinitionId("t"), "List", mapOf("question" to "all open tickets"), tool = "tickets.list",
        )
        assertEquals("list-connected-source", task.contract?.template?.id)
        assertEquals(listOf("tickets.list"), task.contract?.allowedTools)
        assertEquals("rows", task.contract?.outputs?.single()?.name)
        assertEquals(BuiltInRoles.Researcher.id, task.roleId)
        val files = StepPresets.UpdateRepositoryFiles
        val update = StepInstantiation.task(files, files.base(), TaskDefinitionId("u"), "Update", mapOf("brief" to "x", "target" to "aive/page"))
        assertEquals(BuiltInRoles.ImplementationEngineer.id, update.roleId)
        assertEquals(1, update.retryPolicy.maxAttempts)
    }

    @Test
    fun everyStepOfTheArtifactSetIsAccountedForOnce() {
        val steps = ArtifactStepCoverage.steps
        assertEquals(36, steps.size)
        assertEquals(36, steps.map { it.path }.toSet().size)
        steps.flatMap { it.templates }.forEach { assertNotNull(BuiltInTemplates.byId[it], it) }
        steps.filter { it.status == ArtifactStepStatus.CannotRun }.forEach {
            assertTrue(it.templates.isEmpty() && it.note.isNotBlank(), it.path)
        }
        steps.filter { it.status == ArtifactStepStatus.Built }.forEach { assertTrue(it.templates.isNotEmpty(), it.path) }
        assertEquals(12, ArtifactStepCoverage.cannotRun.size)
    }

    @Test
    fun aMermaidFlowchartMustBeOneTheAppCanRead() {
        assertNull(check(StepOutputValidators.MERMAID_FLOWCHART, "flowchart TD\n  A[Start] --> B[Next]\n  B --> C"))
        assertNull(check(StepOutputValidators.MERMAID_FLOWCHART, "```mermaid\ngraph LR\n  A --> B\n```"))
        assertNotNull(check(StepOutputValidators.MERMAID_FLOWCHART, "A --> B"))
        assertNotNull(check(StepOutputValidators.MERMAID_FLOWCHART, "flowchart TD"))
        assertNotNull(check(StepOutputValidators.MERMAID_FLOWCHART, "A diagram of the plan"))
    }

    @Test
    fun designTokensMustBeOneJsonObject() {
        assertNull(check(StepOutputValidators.JSON_OBJECT, """{"color":{"primary":"#112233"}}"""))
        assertNull(check(StepOutputValidators.JSON_OBJECT, "```json\n{\"space\":{\"1\":\"4px\"}}\n```"))
        assertNotNull(check(StepOutputValidators.JSON_OBJECT, "[1,2]"))
        assertNotNull(check(StepOutputValidators.JSON_OBJECT, "{}"))
        assertNotNull(check(StepOutputValidators.JSON_OBJECT, "{not json"))
    }

    @Test
    fun filesMustBeOnADedicatedBranchAndListed() {
        assertNull(check(StepOutputValidators.FILES_CHANGE, "BRANCH: aive/landing\nFILES: index.html, style.css"))
        assertNotNull(check(StepOutputValidators.FILES_CHANGE, "BRANCH: main\nFILES: index.html"))
        assertNotNull(check(StepOutputValidators.FILES_CHANGE, "BRANCH: origin/master\nFILES: a.txt"))
        assertNotNull(check(StepOutputValidators.FILES_CHANGE, "BRANCH: aive/landing"))
        assertNotNull(check(StepOutputValidators.FILES_CHANGE, "FILES: index.html"))
    }
}
