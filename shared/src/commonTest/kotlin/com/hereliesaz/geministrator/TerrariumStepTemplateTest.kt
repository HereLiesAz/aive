package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.template.BuiltInTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TerrariumStepTemplateTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val source = TaskDefinition(TaskDefinitionId("src"), "src", "o", engineer.id, executor = TaskExecutor.RoleAgent(engineer.id))
    private val hive = WorkflowDefinition(WorkflowDefinitionId("w"), "w", tasks = listOf(source))

    private fun add(definition: WorkflowDefinition, template: com.hereliesaz.geministrator.template.StepTemplate) =
        assertIs<TerrariumDependencyEditResult.Applied>(
            addTerrariumCreature(definition, TerrariumNewcomer.FromTemplate(template, template.base(), template.id)),
        ).definition

    @Test
    fun everyBuiltInStepCanBeAddedUnwiredAndValid() {
        var definition = hive
        for (template in BuiltInTemplates.all) definition = add(definition, template)
        assertEquals(1 + BuiltInTemplates.all.size, definition.tasks.size)
        assertTrue(definition.tasks.drop(1).all { it.contract != null && it.dependsOn.isEmpty() })
    }

    @Test
    fun theChangeStepArrivesWithItsPlanGateAndASingleAttempt() {
        val added = add(hive, BuiltInTemplates.ChangeConnectedSource).tasks.last()
        assertEquals(ApprovalPolicy.HumanApproval, added.approvalPolicy)
        assertEquals(1, added.retryPolicy.maxAttempts)
    }

    @Test
    fun theApprovalOfAChangingStepCannotBeTurnedOffNorItsAttemptsRaised() {
        val definition = add(hive, BuiltInTemplates.ChangeConnectedSource)
        val id = definition.tasks.last().id
        assertIs<TerrariumDependencyEditResult.Rejected>(setTerrariumJob(definition, id, humanApproval = false))
        assertIs<TerrariumDependencyEditResult.Rejected>(setTerrariumJob(definition, id, maxAttempts = 3))
        assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumJob(definition, id, objective = "Make the change carefully."))
    }

    @Test
    fun aLiteralInputIsSetAndShownInTheObjective() {
        val definition = add(hive, BuiltInTemplates.WriteDocument)
        val id = definition.tasks.last().id
        val next = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumContractInput(definition, id, "brief", "A note on bees")).definition
        val task = next.tasks.last()
        assertEquals("A note on bees", task.contract!!.inputs["brief"])
        assertTrue(task.objective.contains("brief: A note on bees"))
        val cleared = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumContractInput(next, id, "brief", " ")).definition
        assertEquals(BuiltInTemplates.WriteDocument.body.wording, cleared.tasks.last().objective)
    }

    @Test
    fun anInputTakenFromAnotherStepMakesItWaitForThatStep() {
        val definition = add(hive, BuiltInTemplates.CheckWork)
        val id = definition.tasks.last().id
        val next = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumContractInput(definition, id, "work", "@src.document")).definition
        assertEquals(setOf(TaskDefinitionId("src")), next.tasks.last().dependsOn)
        val dropped = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumContractInput(next, id, "work", "")).definition
        assertTrue(dropped.tasks.last().dependsOn.isEmpty())
    }

    @Test
    fun anUnknownPortOrAToollessStepIsRejected() {
        val definition = add(hive, BuiltInTemplates.WriteDocument)
        val id = definition.tasks.last().id
        assertIs<TerrariumDependencyEditResult.Rejected>(setTerrariumContractInput(definition, id, "nope", "x"))
        assertIs<TerrariumDependencyEditResult.Rejected>(setTerrariumContractTool(definition, id, "read_file"))
    }

    @Test
    fun theToolOfAConnectedStepIsOneNamedTool() {
        val definition = add(hive, BuiltInTemplates.ReadConnectedSource)
        val id = definition.tasks.last().id
        val next = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumContractTool(definition, id, " read_file ")).definition
        assertEquals(listOf("read_file"), next.tasks.last().contract!!.allowedTools)
        val cleared = assertIs<TerrariumDependencyEditResult.Applied>(setTerrariumContractTool(next, id, "")).definition
        assertTrue(cleared.tasks.last().contract!!.allowedTools.isEmpty())
    }
}
