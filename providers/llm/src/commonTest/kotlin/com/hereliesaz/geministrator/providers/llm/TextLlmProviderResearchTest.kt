package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class TextLlmProviderResearchTest {
    @Test
    fun aTextSourceCanTakeAResearchRoleSoAFirstHiveWithTheFreeSourcesCanRun() = runTest {
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String) = TextGenerationResult("text")
        }
        val supported = TextLlmProvider(AgentProviderId("kilo"), "Kilo", api).capabilities().supported
        assertTrue(AgentCapability.Research in supported)
        assertTrue(AgentCapability.PlanGeneration in supported)
    }
}
