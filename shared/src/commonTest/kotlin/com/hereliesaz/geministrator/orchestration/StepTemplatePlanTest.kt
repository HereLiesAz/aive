package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StepTemplatePlanTest {
    private val agents = listOf(OrchestrationRole("planner", "Planner", emptyList()))
    private val packet = OrchestrationPacket(
        objective = "o",
        availableAgents = agents,
        availableTemplates = BuiltInTemplates.forPlanner(),
        instruction = "i",
    )

    private fun plan(vararg steps: OrchestrationPlanStep) = OrchestrationPlan(steps.toList())

    private fun templated(id: String, template: String, inputs: Map<String, String>, deps: List<String> = emptyList()) =
        OrchestrationPlanStep(id, id, "o", "", deps, templateId = template, templateInputs = inputs)

    @Test
    fun theCatalogOffersNothingThatChangesAnything() {
        val ids = BuiltInTemplates.forPlanner().map { it.id }
        assertTrue("change-connected-source" !in ids)
        assertEquals(6, ids.size)
    }

    @Test
    fun aTemplatedStepNeedsNoRoleButEveryRequiredInput() {
        plan(templated("a", "write-document", mapOf("brief" to "bees"))).validateAgainst(packet)
        assertFailsWith<IllegalArgumentException> { plan(templated("a", "write-document", emptyMap())).validateAgainst(packet) }
        assertFailsWith<IllegalArgumentException> { plan(templated("a", "write-document", mapOf("brief" to " "))).validateAgainst(packet) }
    }

    @Test
    fun anUnknownOrWithheldTemplateOrPortIsRefused() {
        assertFailsWith<IllegalArgumentException> { plan(templated("a", "nope", mapOf("brief" to "x"))).validateAgainst(packet) }
        assertFailsWith<IllegalArgumentException> {
            plan(templated("a", "change-connected-source", mapOf("change" to "x", "target" to "y", "content" to "z"))).validateAgainst(packet)
        }
        // Withheld even when a packet claims to offer it.
        val lying = packet.copy(availableTemplates = packet.availableTemplates + OrchestrationTemplate("change-connected-source", "x", listOf("change")))
        assertFailsWith<IllegalArgumentException> {
            plan(templated("a", "change-connected-source", mapOf("change" to "x"))).validateAgainst(lying)
        }
        assertFailsWith<IllegalArgumentException> {
            plan(templated("a", "write-document", mapOf("brief" to "x", "bogus" to "y"))).validateAgainst(packet)
        }
    }

    @Test
    fun aBoundInputMustNameAnEarlierStepInThePlan() {
        val ok = plan(
            templated("a", "write-document", mapOf("brief" to "x")),
            templated("b", "check-work", mapOf("work" to "@a.document", "requirements" to "has headings"), deps = listOf("a")),
        )
        ok.validateAgainst(packet)
        assertFailsWith<IllegalArgumentException> {
            plan(templated("b", "check-work", mapOf("work" to "@zzz.document", "requirements" to "r"))).validateAgainst(packet)
        }
    }

    @Test
    fun aPlannedTemplateStepBecomesAContractTaskPinnedToTheCatalogVersion() {
        val p = plan(
            templated("a", "write-document", mapOf("brief" to "x")),
            templated("b", "check-work", mapOf("work" to "@a.document", "requirements" to "r"), deps = listOf("a")),
        )
        val definition = OrchestratedWorkflowFactory.create(WorkflowDefinitionId("w"), "o", p, packet, BuiltInRoles.all)
        assertTrue(WorkflowGraphValidator.validate(definition).isEmpty())
        val check = definition.tasks.first { it.id.value == "b" }
        assertEquals("check-work@1", check.contract!!.template.key)
        assertEquals(BuiltInRoles.Reviewer.id, check.roleId)
        assertEquals(setOf("a"), check.dependsOn.map { it.value }.toSet())
    }

    @Test
    fun anOrdinaryStepStillNeedsItsRole() {
        assertFailsWith<IllegalArgumentException> {
            plan(OrchestrationPlanStep("a", "a", "o", "nobody")).validateAgainst(packet)
        }
        plan(OrchestrationPlanStep("a", "a", "o", "planner")).validateAgainst(packet)
    }
    @Test
    fun aPlannedStepStartsFromTheGenerationTheKeeperSelectedAndTheInstructionAsksForAStartingTemplate() {
        val base = BuiltInTemplates.WriteDocument.base()
        val revision = com.hereliesaz.geministrator.template.TemplateRevision(
            kind = base.kind, templateId = base.id, version = 2, parentVersion = 1, generation = 1,
            mutation = setOf(com.hereliesaz.geministrator.template.TunableField.Wording),
            body = base.body.copy(wording = "Write the document with two levels of headings."), locked = base.locked,
            before = base.body.wording, after = "Write the document with two levels of headings.", fitness = "f", proposalId = "p",
            adoptedAtEpochMillis = 5,
        )
        val catalog = com.hereliesaz.geministrator.template.TemplateCatalog(BuiltInTemplates.bases(), listOf(revision))
        val p = plan(templated("a", "write-document", mapOf("brief" to "x")))
        val chosen = OrchestratedWorkflowFactory.create(WorkflowDefinitionId("w"), "o", p, packet, BuiltInRoles.all, catalog).tasks.single()
        assertEquals("write-document@2", chosen.contract!!.template.key)
        assertTrue(chosen.objective.startsWith("Write the document with two levels of headings."))
        val builtIn = OrchestratedWorkflowFactory.create(WorkflowDefinitionId("w"), "o", p, packet, BuiltInRoles.all).tasks.single()
        assertEquals("write-document@1", builtIn.contract!!.template.key)
        assertTrue(BuiltInTemplates.PLANNER_INSTRUCTION.contains("reviewer can review"))
    }
}
