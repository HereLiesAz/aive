package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextGenerationOrchestrationAgentRuntimeTest {
    private val packet = OrchestrationPacket(
        objective = "Add a settings screen",
        availableAgents = listOf(
            OrchestrationRole(id = "engineer", name = "Engineer", authorities = listOf("Implement")),
            OrchestrationRole(id = "reviewer", name = "Reviewer", authorities = listOf("Review")),
        ),
        instruction = "Plan the work.",
    )

    private fun runtimeReplying(text: String, prompts: MutableList<String> = mutableListOf()) =
        TextGenerationOrchestrationAgentRuntime(
            object : TextGenerationApi {
                override suspend fun generate(prompt: String): TextGenerationResult {
                    prompts += prompt
                    return TextGenerationResult(text = text)
                }
            },
        )

    @Test
    fun parsesFencedPlanAndSendsPacket() = runBlocking {
        val prompts = mutableListOf<String>()
        val plan = runtimeReplying(
            """
            ```json
            {"steps":[
              {"id":"build","name":"Build","objective":"Build the screen","roleId":"engineer"},
              {"id":"review","name":"Review","objective":"Review it","roleId":"reviewer","dependsOn":["build"]}
            ]}
            ```
            """.trimIndent(),
            prompts,
        ).plan(packet)

        assertEquals(listOf("build", "review"), plan.steps.map { it.id })
        assertTrue(prompts.single().contains("Add a settings screen"))
        assertTrue(prompts.single().contains("\"engineer\""))
    }

    @Test
    fun rejectsPlanUsingUnavailableRole() {
        val runtime = runtimeReplying(
            """{"steps":[{"id":"a","name":"A","objective":"Do A","roleId":"designer"}]}""",
        )
        assertFailsWith<IllegalArgumentException> { runBlocking { runtime.plan(packet) } }
    }

    @Test
    fun rejectsReplyWithoutJson() {
        val runtime = runtimeReplying("I cannot help with that.")
        assertFailsWith<IllegalArgumentException> { runBlocking { runtime.plan(packet) } }
    }
}
