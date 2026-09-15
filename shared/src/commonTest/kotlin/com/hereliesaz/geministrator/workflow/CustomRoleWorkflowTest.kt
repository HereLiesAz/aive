package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CustomRoleWorkflowTest {
    @Test
    fun starterWorkflowUsesCustomRolesByAuthority() {
        val implementer = role("my-implementer", RoleAuthority.Implement)
        val verifier = role("my-verifier", RoleAuthority.Verify)
        val reviewer = role("my-reviewer", RoleAuthority.ReviewCode)
        val releaser = role("my-releaser", RoleAuthority.ApproveRelease)

        val definition = StarterWorkflowFactory.create(
            id = WorkflowDefinitionId("custom-starter"),
            objective = "Build the feature",
            roles = listOf(implementer, verifier, reviewer, releaser),
        )

        assertEquals(implementer.id, definition.tasks.first { it.id.value == "implementation" }.roleId)
        assertEquals(verifier.id, definition.tasks.first { it.id.value == "verification" }.roleId)
        assertEquals(reviewer.id, definition.tasks.first { it.id.value == "review" }.roleId)
        assertEquals(releaser.id, definition.tasks.first { it.id.value == "release-approval" }.roleId)
    }

    @Test
    fun testDesignExpansionUsesCustomImplementationAndTestAuthorRoles() {
        val implementer = role("builder", RoleAuthority.Implement)
        val testAuthor = role("breaker", RoleAuthority.AuthorTests)
        val implementation = TaskDefinition(
            id = TaskDefinitionId("build"),
            name = "Build",
            objective = "Build it",
            roleId = implementer.id,
            executor = TaskExecutor.RoleAgent(implementer.id),
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("custom-expansion"),
            name = "Custom expansion",
            description = "",
            tasks = listOf(implementation),
            testDesignPolicy = TestDesignPolicy.BeforeAndAfterImplementation,
        )

        val expanded = WorkflowDefinitionExpander.expand(definition, listOf(implementer, testAuthor))

        val pre = assertNotNull(expanded.tasks.firstOrNull { it.id.value == "build--pre-code-tests" })
        val post = assertNotNull(expanded.tasks.firstOrNull { it.id.value == "build--post-code-tests" })
        assertEquals(testAuthor.id, pre.roleId)
        assertEquals(testAuthor.id, post.roleId)
        assertEquals(setOf(pre.id), expanded.tasks.first { it.id == implementation.id }.dependsOn)
    }

    private fun role(id: String, authority: RoleAuthority) = RoleDefinition(
        id = RoleDefinitionId(id),
        name = id,
        description = id,
        instructions = id,
        authorities = setOf(authority),
    )
}
