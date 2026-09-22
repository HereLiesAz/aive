package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NodeCharacterGenerationWorkflowFactoryTest {
    @Test
    fun buildsOneGovernedSixStagePipelinePerTargetRole() {
        val target = RoleDefinition(
            id = RoleDefinitionId("archive-cartographer"),
            name = "Archive Cartographer",
            description = "Maps relationships across an archive.",
            instructions = "Preserve provenance while mapping the archive.",
        )
        val source = WorkflowDefinition(
            id = WorkflowDefinitionId("archive-workflow"),
            name = "Archive Workflow",
            tasks = listOf(
                TaskDefinition(
                    id = TaskDefinitionId("map"),
                    name = "Map archive",
                    objective = "Build the archive relationship map.",
                    roleId = target.id,
                    executor = TaskExecutor.RoleAgent(target.id),
                ),
            ),
        )

        val generated = NodeCharacterGenerationWorkflowFactory.create(
            source = source,
            roleCatalog = listOf(target),
        )

        assertEquals(6, generated.tasks.size)
        assertEquals(
            listOf(
                "archive-cartographer-character-intake",
                "archive-cartographer-character-design",
                "archive-cartographer-rig-contract",
                "archive-cartographer-generate-assets",
                "archive-cartographer-inspect-assets",
                "archive-cartographer-register-assets",
            ),
            generated.tasks.map { it.id.value },
        )

        val generate = generated.tasks.single { it.id.value.endsWith("-generate-assets") }
        val generateExecutor = assertIs<TaskExecutor.ExternalService>(generate.executor)
        assertEquals(NODE_CHARACTER_IMAGE_PIPELINE_SERVICE, generateExecutor.service)
        assertEquals(
            "generate|archive-workflow|archive-cartographer",
            generateExecutor.operation,
        )

        val register = generated.tasks.single { it.id.value.endsWith("-register-assets") }
        val registerExecutor = assertIs<TaskExecutor.ExternalService>(register.executor)
        assertEquals(
            "register|archive-workflow|archive-cartographer",
            registerExecutor.operation,
        )
        assertTrue(register.dependsOn.any { it.value.endsWith("-generate-assets") })
        assertTrue(register.dependsOn.any { it.value.endsWith("-inspect-assets") })
    }

    @Test
    fun pipelineRolesNeverRecursivelyRequestTheirOwnCharacters() {
        val source = WorkflowDefinition(
            id = WorkflowDefinitionId("pipeline"),
            name = "Pipeline",
            tasks = NodeCharacterGenerationWorkflowFactory.roles.mapIndexed { index, role ->
                TaskDefinition(
                    id = TaskDefinitionId("task-$index"),
                    name = role.name,
                    objective = role.description,
                    roleId = role.id,
                    executor = TaskExecutor.RoleAgent(role.id),
                )
            },
        )

        assertTrue(NodeCharacterGenerationWorkflowFactory.referencedRoleIds(source).isEmpty())
        assertFalse(
            NodeCharacterGenerationWorkflowFactory.roleIds.any {
                it in NodeCharacterGenerationWorkflowFactory.referencedRoleIds(source)
            },
        )
    }
}
