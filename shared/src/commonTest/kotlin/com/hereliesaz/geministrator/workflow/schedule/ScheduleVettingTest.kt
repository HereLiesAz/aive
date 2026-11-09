package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.ShortFilmHive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScheduleVettingTest {
    private fun hive(id: String, objective: String = "Report", executor: TaskExecutor? = null) = WorkflowDefinition(
        id = WorkflowDefinitionId(id), name = id,
        tasks = listOf(TaskDefinition(id = TaskDefinitionId("t"), name = "T", objective = objective, roleId = null, executor = executor)),
    )

    @Test
    fun theFingerprintFollowsWhatTheHiveDoesAndNotItsName() {
        val original = hive("h")
        assertEquals(ScheduleVetting.fingerprint(original), ScheduleVetting.fingerprint(original.copy(name = "Renamed", description = "new words")))
        assertNotEquals(ScheduleVetting.fingerprint(original), ScheduleVetting.fingerprint(hive("h", objective = "Report, then publish")))
        assertNotEquals(
            ScheduleVetting.fingerprint(original),
            ScheduleVetting.fingerprint(hive("h", executor = TaskExecutor.TestRunner(command = "make"))),
        )
    }

    @Test
    fun aHiveWithTheFilmExecutorsIsRefusedDirectlyWrappedOrNested() {
        assertNotNull(ScheduleVetting.refusal(ShortFilmHive.definition()))
        val film = hive("f", executor = TaskExecutor.ExternalService("film-assemble"))
        assertNotNull(ScheduleVetting.refusal(film))
        assertNotNull(ScheduleVetting.refusal(hive("w", executor = TaskExecutor.Distributed(TaskExecutor.ExternalService("film-frames")))))
        val parent = hive("p", executor = TaskExecutor.NestedWorkflow(film.id))
        assertNotNull(ScheduleVetting.refusal(parent, mapOf(film.id to film)))
        assertNull(ScheduleVetting.refusal(hive("ok", executor = TaskExecutor.ExternalService("something-else"))))
    }
}
