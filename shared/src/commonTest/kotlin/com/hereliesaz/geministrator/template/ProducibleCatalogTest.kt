package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.CHECKPOINT_COMMIT_METADATA_KEY
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TEMPLATE_METADATA_KEY
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.workflow.StepOutputValidators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The catalog of 66 producibles: every one has a template with a deterministic contract (docs/Step-Templates.md). */
class ProducibleCatalogTest {
    private fun artifact(text: String?, meta: Map<String, String> = emptyMap(), kind: ArtifactKind = ArtifactKind.Research) =
        ArtifactRef(ArtifactId("a"), kind, TaskRunId("r"), "x", textContent = text, metadata = meta, createdAtEpochMillis = 0)

    @Test
    fun allSixtySixAreHereAndUnique() {
        val ids = Producibles.catalog.map { it.id }
        assertEquals(66, ids.size)
        assertEquals(66, ids.toSet().size)
        assertEquals(13, Producibles.groups.count { it.id != "output-kinds" })
        assertEquals(ids.toSet(), Producibles.groups.filter { it.id != "output-kinds" }.flatMap { it.members }.toSet())
        assertEquals(66 + 5, Producibles.all.size)
        assertEquals(Producibles.all.size, Producibles.all.map { it.templateId }.toSet().size)
    }

    @Test
    fun everyProducibleHasATemplateWithATypedCheckedContract() {
        for (p in Producibles.all) {
            val template = assertNotNull(BuiltInTemplates.byId[p.templateId], p.id)
            assertTrue(template.inputs.any { it.required }, p.id)
            assertTrue(template.outputs.isNotEmpty(), p.id)
            for (o in template.outputs) {
                assertTrue(StepOutputValidators.isKnown(o.validatorId), "${p.id}:${o.validatorId}")
                assertEquals(o.validatorId, template.body.validators[o.name], p.id)
            }
            assertTrue(p.reviewable == (p.notReviewableReason == null), p.id)
            if (p.reviewable) assertTrue(p.criteria.size >= 2, p.id)
            assertTrue(template.body.wording.isNotBlank(), p.id)
        }
    }

    @Test
    fun effectfulOnesAskFirstAndNothingElseDoes() {
        val effectful = Producibles.all.filter { BuiltInTemplates.byId.getValue(it.templateId).effectful }.map { it.id }.toSet()
        assertEquals(
            setOf("code-change-on-branch", "tests-on-branch", "change-connected-source", "update-connected-document", "append-rows-to-connected-sheet", "remove-from-connected-tool"),
            effectful,
        )
        for (id in effectful) {
            val t = BuiltInTemplates.byId.getValue(Producibles.byId(id)!!.templateId)
            assertTrue(t.planGate, id)
            assertEquals(1, t.body.maxAttempts, id)
            assertTrue(t !in BuiltInTemplates.queenMayMake, id)
        }
        assertTrue(BuiltInTemplates.queenMayMake.none { it.effectful })
        assertTrue(BuiltInTemplates.byId.getValue("brief") in BuiltInTemplates.queenMayMake)
    }

    @Test
    fun theTrainingRulingIsTheTrainingReport() {
        assertEquals("Nanny's training report", Producibles.byId("training-report")?.name)
    }

    @Test
    fun theKindsOfOutputHavePortTypes() {
        fun type(id: String) = PortTypes.of(BuiltInTemplates.byId.getValue(id).outputs.first())
        assertEquals(PortType.Mermaid, type("diagram-flowchart"))
        assertEquals(PortType.Json, type("swarm-plan"))
        assertEquals(PortType.Diff, type("patch-draft"))
        assertEquals(PortType.Checklist, type("checklist"))
        assertEquals(PortType.Plan, type("plan"))
        assertEquals(PortType.Script, type("script"))
        assertEquals(PortType.Report, type("report"))
        assertEquals(PortType.MediaBrief, type("image-brief"))
        assertEquals(PortType.Table, type("glossary"))
        assertTrue(PortTypes.accepts(PortType.Markdown, PortType.Report))
        assertTrue(PortTypes.accepts(PortType.Any, PortType.Json))
        assertFalse(PortTypes.accepts(PortType.Table, PortType.Markdown))
        assertFalse(PortTypes.accepts(PortType.Json, PortType.Mermaid))
    }

    @Test
    fun checkWorkUsesTheTemplatesCriteriaOrWrapsOrRefuses() {
        val templated = artifact("# Goal\nx", mapOf(TEMPLATE_METADATA_KEY to "brief@1"))
        val review = CheckWorkSelection.select(templated, "keep it short") as CheckWorkSelection.Review
        assertFalse(review.generic)
        assertEquals(Producibles.byId("brief")!!.criteria, review.criteria)
        assertTrue(review.requirements.startsWith("1. ") && "keep it short" in review.requirements)
        assertEquals(StepOutputValidators.VERDICT_CRITERIA, review.validatorId)

        val generic = CheckWorkSelection.select(artifact("some text")) as CheckWorkSelection.Review
        assertTrue(generic.generic)
        assertEquals(CheckWorkSelection.GENERIC_CRITERIA, generic.criteria)
        assertTrue(CheckWorkSelection.select(artifact("some text"), allowGeneric = false) is CheckWorkSelection.Refuse)
        assertTrue(CheckWorkSelection.select(artifact(" ")) is CheckWorkSelection.Refuse)
        assertTrue(CheckWorkSelection.select(artifact("BRANCH: aive/x", mapOf(CHECKPOINT_COMMIT_METADATA_KEY to "abc"), ArtifactKind.CodeChange)) is CheckWorkSelection.Refuse)
        assertTrue(CheckWorkSelection.select(artifact("TARGET: a\nCHANGED: b", mapOf(TEMPLATE_METADATA_KEY to "change-connected-source@1"))) is CheckWorkSelection.Refuse)
        assertTrue(CheckWorkSelection.select(artifact("VERDICT: approve", mapOf(TEMPLATE_METADATA_KEY to "check-work@1"))) is CheckWorkSelection.Refuse)
    }

    @Test
    fun anEffectfulPresetKeepsItsEffect() {
        assertEquals(setOf(StepEffect.WritesRepositoryBranch), BuiltInTemplates.byId.getValue("tests-on-branch").effects)
        assertEquals(setOf(StepEffect.MutatesConnectedSource), BuiltInTemplates.byId.getValue("update-connected-document").effects)
    }
}
