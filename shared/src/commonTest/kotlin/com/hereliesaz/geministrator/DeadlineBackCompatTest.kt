package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TimeLimit
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeadlineBackCompatTest {
    private val json = SettingsWorkflowPersistence.defaultJson

    @Test fun definitionWithoutTimeLimitDecodesAuto() {
        val task = TaskDefinition(TaskDefinitionId("t"), "T", "o", null)
        val encoded = Json.parseToJsonElement(json.encodeToString(task)).let { it as kotlinx.serialization.json.JsonObject }
        val stripped = kotlinx.serialization.json.JsonObject(encoded - "timeLimit")
        assertEquals(TimeLimit.Auto, json.decodeFromString<TaskDefinition>(stripped.toString()).timeLimit)
    }

    @Test fun runWithoutClockDecodes() {
        val run = TaskRun(TaskRunId("r"), TaskDefinitionId("t"), TaskRunStatus.Running, assignedRoleId = null)
        val encoded = Json.parseToJsonElement(json.encodeToString(run)) as kotlinx.serialization.json.JsonObject
        val old = kotlinx.serialization.json.JsonObject(encoded - "clock" - "timedOutAttempts")
        val decoded = json.decodeFromString<TaskRun>(old.toString())
        assertNull(decoded.clock)
        assertEquals(0, decoded.timedOutAttempts)
    }

    @Test fun keeperLimitRoundTrips() {
        for (limit in listOf(TimeLimit.Auto, TimeLimit.Off, TimeLimit.Minutes(20))) {
            val task = TaskDefinition(TaskDefinitionId("t"), "T", "o", null, timeLimit = limit)
            assertEquals(limit, json.decodeFromString<TaskDefinition>(json.encodeToString(task)).timeLimit)
        }
    }

    @Test fun minutesAreCoercedToTheKeepersRange() {
        assertEquals(TimeLimit.Minutes(1), TimeLimit.minutes(0))
        assertEquals(TimeLimit.Minutes(480), TimeLimit.minutes(9999))
    }
}
