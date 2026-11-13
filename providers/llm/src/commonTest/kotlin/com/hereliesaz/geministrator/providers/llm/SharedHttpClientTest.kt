package com.hereliesaz.geministrator.providers.llm

import kotlin.test.Test
import kotlin.test.assertSame

class SharedHttpClientTest {
    @Test
    fun hostedApisShareOneClientInsteadOfBuildingAndLeakingOnePerCall() {
        assertSame(sharedLlmHttpClient(), sharedLlmHttpClient())
    }
}
