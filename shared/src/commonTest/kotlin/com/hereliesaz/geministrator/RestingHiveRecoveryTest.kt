package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals

class RestingHiveRecoveryTest {
    private fun hive(id: String) = WorkflowDefinition(id = WorkflowDefinitionId(id), name = id, tasks = emptyList())

    @Test
    fun aRememberedHiveThatNoLongerExistsIsForgotten() {
        assertEquals("", restingHiveAfterLoad("gone", listOf(hive("other"))))
        assertEquals("", restingHiveAfterLoad("gone", emptyList()))
    }

    @Test
    fun aRememberedHiveThatStillExistsIsKept() {
        assertEquals("h", restingHiveAfterLoad("h", listOf(hive("h"), hive("other"))))
    }

    @Test
    fun aFailedLoadNeverForgetsTheHive() {
        assertEquals("h", restingHiveAfterLoad("h", null))
    }
}
