package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertNotNull

class LocalGitRepositoryOperationClientCompileSmoke {
    @Test
    fun clientConstructs() {
        assertNotNull(LocalGitRepositoryOperationClient())
    }
}
