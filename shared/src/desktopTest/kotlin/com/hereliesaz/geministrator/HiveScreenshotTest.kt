package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Screenshots of the hive screens as the app draws them, for checking by eye before shipping.
 * PNGs land in `shared/build/hive-screens/`.
 */
class HiveScreenshotTest {
    private val out = File("build/hive-screens").apply { mkdirs() }

    private fun shoot(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene(width = width, height = height, density = Density(2f)) {
            Box(Modifier.fillMaxSize().background(Azphalt.currentGround.page).padding(12.dp)) { content() }
        }.use { scene ->
            scene.render(0L)
            scene.render(400_000_000L)
            val bytes = scene.render(900_000_000L).encodeToData()!!.bytes
            assertTrue(bytes.size > 10_000, "$name looks blank")
            File(out, "$name.png").writeBytes(bytes)
        }
    }

    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val architect = BuiltInRoles.Architect
    private val roles: List<RoleDefinition> = listOf(engineer, qa, architect)

    private fun task(id: String, name: String, role: RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = name,
        objective = "$name for the release",
        roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(role.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("feature-delivery"),
        name = "Feature delivery",
        tasks = listOf(
            task("contract", "Contract", architect),
            task("build", "Implement", engineer, "contract"),
            task("review", "Review", qa, "build"),
            task("ship", "Ship", engineer, "review"),
        ),
    )

    private fun run(status: WorkflowRunStatus, vararg tasks: Triple<String, TaskRunStatus, String>) = WorkflowRun(
        id = WorkflowRunId("run-${status.name}"),
        projectId = ProjectId("aive"),
        workflowDefinitionId = definition.id,
        objective = "Ship 2.4.0",
        status = status,
        taskRuns = tasks.associate { (id, s, provider) ->
            val t = definition.tasks.first { it.id.value == id }
            t.id to TaskRun(TaskRunId("r-$id"), t.id, s, assignedRoleId = t.roleId, assignedProviderId = AgentProviderId(provider))
        },
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private val liveRun = run(
        WorkflowRunStatus.Running,
        Triple("contract", TaskRunStatus.Completed, "claude"),
        Triple("build", TaskRunStatus.Running, "claude"),
        Triple("review", TaskRunStatus.AwaitingApproval, "gemini"),
    )

    @Test
    fun shootsTheHiveScreens() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        shoot("1-hive-live", 900, 1400) {
            GeministratorWorkflowTerrarium(definition, liveRun, roles, selectedTaskId = null, onTaskSelected = {}, onStageDefinition = { it })
        }
        shoot("2-hive-build", 900, 2600) {
            GeministratorWorkflowTerrarium(definition, liveRun, roles, selectedTaskId = "build", onTaskSelected = {}, onStageDefinition = { it }, startInBuild = true)
        }
        shoot("3-whole-hive", 900, 1400) {
            DistrictScreen(
                onLoadDefinitions = { listOf(definition, definition.copy(id = WorkflowDefinitionId("research"), name = "Deep research")) },
                onLoadRuns = { listOf("The Aive" to listOf(liveRun)) },
                onEnterNeighbourhood = {},
                onImportAgent = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        shoot("5-launch-bar", 900, 600) {
            HiveLaunchBar("shot", onLaunch = { _, _ -> })
        }
        shoot("6-hive-edge", 900, 900) {
            // In the app the edge sits inside the whole hive's dark tissue.
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF1E1A17)).padding(16.dp)) { AzphaltThemedSurface(Azphalt.Ink) {
                HiveEdge(
                    onLoadPackages = {
                        listOf(
                            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "r1", name = "Violacea reviewer", description = "Reviews diffs against the contract", author = "azphalt", version = "1", kind = "role"),
                            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "r2", name = "Glacia researcher", description = "Finds sources and cites them", author = "azphalt", version = "1", kind = "role"),
                            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "w1", name = "Bug triage", author = "azphalt", version = "1", kind = "workflow"),
                        )
                    },
                    onBringIn = {},
                )
            } }
        }
        shoot("7-live-row", 780, 300) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                AzphaltPill(label = "Your hive", seed = "zoom-out-district", onClick = {})
                AzphaltPill(label = "Timeline", seed = "hive-timeline", onClick = {})
                AzphaltPill(label = "More editing", seed = "hive-more-editing", onClick = {})
            }
        }
        shoot("4-needs", 700, 1000) {
            AzphaltThemedSurface(Azphalt.Ink) {
                Box(Modifier.background(Azphalt.Ink).padding(16.dp)) {
                    creatureNeeds(TaskDefinitionId("build"), definition, liveRun, roles)?.let { CreatureNeedsPanel(it) }
                }
            }
        }
    }
}
