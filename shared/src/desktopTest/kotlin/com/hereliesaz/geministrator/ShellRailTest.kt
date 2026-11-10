package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellRailTest {
    @Test
    fun everyRailPlaceLightsForItsOwnDestination() {
        ShellRailPlace.entries.forEach { place -> assertEquals(place, ShellRailPlace.of(place.destination)) }
    }

    @Test
    fun theKeepersRoomIsNotARailPlace() {
        assertNull(ShellRailPlace.of(ControlRoomDestination.Settings))
        assertNull(ShellRailPlace.of(ControlRoomDestination.Compute))
    }

    @Test
    fun workflowsOpenedFromAHiveKeepHiveLit() {
        assertEquals(ShellRailPlace.Hive, ShellRailPlace.of(ControlRoomDestination.Workflows))
    }

    @Test
    fun everyDestinationButTheKeepersRoomHasARailPlace() {
        ControlRoomDestination.entries
            .filter { it.navigationItem != ControlRoomDestination.Settings }
            .forEach { kotlin.test.assertNotNull(ShellRailPlace.of(it), "$it has no place on the rail") }
    }

    @Test
    fun hiveGoesBackFromAPlaceThenOutToTheDistrictAndBackIn() {
        assertEquals(RailHiveStep.BackToHive, railHiveStep(ControlRoomDestination.Memory, inDistrict = false))
        assertEquals(RailHiveStep.BackToHive, railHiveStep(ControlRoomDestination.Stable, inDistrict = true))
        assertEquals(RailHiveStep.OutToDistrict, railHiveStep(ControlRoomDestination.Overview, inDistrict = false))
        assertEquals(RailHiveStep.IntoHive, railHiveStep(ControlRoomDestination.Overview, inDistrict = true))
    }
    @Test
    fun repositoryBreadcrumbNamesAllThreeRepositoryTypes() {
        assertEquals("GitHub: HereLiesAz/aive", shellRepositoryLabel(RepositoryRef("HereLiesAz", "aive", "main")))
        assertEquals(
            "GitLab: team/platform/aive",
            shellRepositoryLabel(RepositoryRef("team/platform", "aive", "main", source = RepositorySource.GitLab)),
        )
        assertEquals(
            "Local Git: aive",
            shellRepositoryLabel(RepositoryRef("", "aive", source = RepositorySource.Local, localPath = "/work/aive")),
        )
    }

    @Test
    fun breadcrumbUsesTheProjectNameOnlyForObjectiveNamedWorkflows() {
        val project = Project(ProjectId("p"), "Memory Audit", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
        val prompt = "Inspect the memory layer, revise its structure, and carefully optimize each stage of consolidation"
        val run = WorkflowRun(
            WorkflowRunId("r"), projectId = project.id, workflowDefinitionId = WorkflowDefinitionId("hive"),
            objective = prompt, status = WorkflowRunStatus.Running,
            createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
        )
        val legacy = WorkflowDefinition(run.workflowDefinitionId, prompt.take(80), tasks = emptyList())
        assertEquals("Memory Audit", shellHiveLabel(legacy, run, project))
        val named = legacy.copy(name = "Memory Refinement")
        assertEquals("Memory Refinement", shellHiveLabel(named, run, project))
        assertEquals("Memory Audit", shellHiveLabel(named.copy(name = " "), run, project))
    }

}
