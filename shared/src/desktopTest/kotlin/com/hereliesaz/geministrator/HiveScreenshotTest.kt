package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import androidx.compose.foundation.layout.size
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
        // Every clasp state at once: done (green), failed (red), blocked (red, slice stopping at it).
        shoot("8-clasps", 900, 1400) {
            // Review runs on any outcome of Implement (yellow pulse); Ship only on Review's failure (red).
            val knotted = definition.copy(
                tasks = definition.tasks.map {
                    when (it.id.value) {
                        "review" -> it.copy(condition = com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome(TaskDefinitionId("build")))
                        "ship" -> it.copy(condition = com.hereliesaz.geministrator.domain.TaskCondition.OnFailure(TaskDefinitionId("review")))
                        else -> it
                    }
                },
            )
            GeministratorWorkflowTerrarium(
                knotted,
                run(
                    WorkflowRunStatus.Running,
                    Triple("contract", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Completed, "claude"),
                    Triple("review", TaskRunStatus.Running, "gemini"),
                    Triple("ship", TaskRunStatus.Blocked, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
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
        // The stable (mockups B1-B4), on the whole hive's dark tissue.
        val fresh = StableHost(DurableUiStateStore(rootKey = "aive.stable.shot.${System.nanoTime()}")).also { runBlocking { it.load() } }
        fun dark(host: StableHost, content: @androidx.compose.runtime.Composable () -> Unit) = @androidx.compose.runtime.Composable {
            androidx.compose.runtime.CompositionLocalProvider(LocalStableHost provides host) {
                Box(Modifier.background(androidx.compose.ui.graphics.Color(0xFF1E1A17)).padding(16.dp)) {
                    AzphaltThemedSurface(Azphalt.Ink) { content() }
                }
            }
        }
        shoot("9-stable-pick", 900, 3000, dark(fresh) { StableSection() })
        val shop = object : BreedPurchases {
            override suspend fun prices(breedIds: List<String>) = breedIds.associateWith { "$1.99" }
            override suspend fun buy(breedId: String) = emptyList<BreedPurchase>()
            override suspend fun unclaimed() = emptyList<BreedPurchase>()
            override suspend fun consume(purchase: BreedPurchase) = true
        }
        val kept = StableHost(DurableUiStateStore(rootKey = "aive.stable.shot.${System.nanoTime()}")).also { host ->
            runBlocking { host.load() }
            host.chooseStarter(host.catalog.starters.first().id)
            val bought = host.catalog.breeds.filter { it.sold && !it.starter }.take(2).map { BreedPurchase("shot-${it.id}", listOf(it.id)) }
            runBlocking { host.claim(shop, bought) }
            host.assign(engineer.id, host.stable.keys[1])
            host.runCleared(liveRun.copy(id = WorkflowRunId("hatch-shot"), status = WorkflowRunStatus.Completed, taskRuns = liveRun.taskRuns.mapValues { (_, r) -> r.copy(status = TaskRunStatus.Completed) }))
        }
        shoot("10-stable", 900, 2200, dark(kept) { StableSection() })
        shoot("11-creature-picker", 900, 700, dark(kept) { CreaturePicker(engineer.id) })
        shoot("12-hive-with-stable", 900, 1400) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalStableHost provides kept,
                LocalCreatureRig provides { label -> kept.rigFor(roles.firstOrNull { it.name == label }?.id) },
            ) {
                GeministratorWorkflowTerrarium(definition, liveRun, roles, selectedTaskId = null, onTaskSelected = {}, onStageDefinition = { it })
            }
        }
        // A bred child: one parent's body wearing the other's tendrils and a third's tail.
        shoot("13-bred", 900, 520) {
            androidx.compose.foundation.layout.Row(
                Modifier.background(androidx.compose.ui.graphics.Color(0xFF1E1A17)).padding(16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                StableCreature("feedback-listener", 90)
                StableCreature("threat-modeler", 90)
                StableCreature("support-agent", 90)
                StableCreature("feedback-listener+threat-modeler+support-agent", 160)
            }
        }
        // Breeds in the Store (mockup B5): name, rarity, price; nothing else.
        shoot("14-breeds-store", 900, 1500, dark(kept) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBreedPurchases provides shop) {
                HiveEdge(onLoadPackages = { emptyList() }, onBringIn = {}, shelfAtStart = EdgeShelf.Breeds)
            }
        })
        // Close in on the vessels (zoom 250%), where the red cells show.
        shoot("15-vessels-close", 900, 1400) {
            val scene = TissueScene(
                capillaries = listOf(TissueCapillary("claude", com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition(0f, 0f), com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition(1f, 1f))),
                providerOf = mapOf("a" to "claude", "b" to "claude"),
                egg = null,
            )
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val centres = mapOf("a" to Offset(size.width * 0.38f, size.height * 0.42f), "b" to Offset(size.width * 0.66f, size.height * 0.7f))
                drawTissueGround(scene, zoom = 2.5f, creatureRadius = 100f, phase = 0.3f, isFlowing = { true }) { centres[it] }
                centres.values.forEach { drawCircle(androidx.compose.ui.graphics.Color(0x33FFFFFF), 100f, it) }
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

    /** The Keeper's room: every part, wide and on a phone, with Entire signed in and out. */
    @Test
    fun shootsTheKeepersRoom() {
        fun entire(status: EntireStatus) = object : EntireIntegration {
            override suspend fun status() = status
            override suspend fun logIn(): String? = null
            override fun setRecordRuns(record: Boolean) = Unit
            override suspend fun clone(reference: String): String? = null
        }
        val signedIn = EntireStatus(version = "Entire CLI 0.9.2", account = "HereLiesAz", recordRuns = true)
        fun room(section: KeeperSection, compact: Boolean, status: EntireStatus, providers: Set<String>) = @androidx.compose.runtime.Composable {
            DurableUiState.store.putString("keeper.section", section.name)
            DurableUiState.store.putBoolean("keeper.open", true)
            androidx.compose.runtime.CompositionLocalProvider(LocalEntire provides entire(status)) {
                KeeperRoom(
                    sections = KeeperSection.entries,
                    opening = section,
                    compact = compact,
                    statusOf = { if (it == KeeperSection.Connections) keeperCount(providers.size + 1) else null },
                    content = { part, partModifier ->
                        ProviderSettingsScreen(
                            connectedProviderIds = providers,
                            section = part,
                            compact = compact,
                            trailing = {
                                if (part == KeeperSection.Connections) {
                                    RepositoryConnections(setOf(RepositoryServiceCatalog.GITHUB_ID), {}, {}, compact)
                                }
                            },
                            modifier = partModifier,
                        )
                    },
                )
            }
        }
        val two = setOf(ProviderCatalog.ANTHROPIC_ID, ProviderCatalog.OPENROUTER_ID)
        shoot("17-keeper-connections", 2400, 1500) { room(KeeperSection.Connections, false, signedIn, two)() }
        shoot("18-keeper-connections-first-run", 2400, 1500) { room(KeeperSection.Connections, false, EntireStatus(), emptySet())() }
        shoot("19-keeper-phone-connections", 824, 1830) { room(KeeperSection.Connections, true, EntireStatus(version = "Entire CLI 0.9.2"), two)() }
        shoot("20-keeper-projects", 2400, 1500) { room(KeeperSection.Projects, false, signedIn, two)() }
        shoot("21-keeper-appearance", 2400, 1500) { room(KeeperSection.Appearance, false, signedIn, two)() }
    }

    /** The shell (docs/Keepers-Room.md, stage 2): the breadcrumb header over the hive, its places and a first launch. */
    @Test
    fun shootsTheShell() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val project = com.hereliesaz.geministrator.domain.Project(ProjectId("aive"), "The Aive", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val live = ApplicationRuntimeState.Live(LiveWorkflowPresentation(project, definition, liveRun, roles))
        val fresh = StableHost(DurableUiStateStore(rootKey = "aive.stable.shot.${System.nanoTime()}")).also { runBlocking { it.load() } }
        fun shell(
            destination: ControlRoomDestination,
            state: ApplicationRuntimeState,
            compact: Boolean,
            selected: String? = null,
            district: Boolean = false,
        ) = @androidx.compose.runtime.Composable {
            DurableUiState.store.putBoolean("overview.zoom.district", district)
            DurableUiState.store.putString("overview.resting-hive", "")
            androidx.compose.runtime.CompositionLocalProvider(LocalStableHost provides fresh) {
                ControlRoom(
                    destination = destination,
                    onDestinationSelected = {},
                    selectedTaskId = selected,
                    onTaskSelected = {},
                    onLaunchWorkflow = { _, _, _, _ -> },
                    onApproveTask = {},
                    onRejectPlan = {},
                    onResolveEscalation = { _, _ -> },
                    onRecoverFromCorruption = {},
                    onLoadWorkflowDefinitions = { listOf(definition) },
                    compact = compact,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                    runtimeState = state,
                )
            }
        }
        shoot("25-shell-hive", 2400, 1500) { shell(ControlRoomDestination.Overview, live, false)() }
        shoot("26-shell-phone-hive", 824, 1830) { shell(ControlRoomDestination.Overview, live, true)() }
        shoot("27-shell-creature", 2400, 1500) { shell(ControlRoomDestination.Overview, live, false, selected = "build")() }
        shoot("28-shell-first-launch", 824, 1830) { shell(ControlRoomDestination.Overview, ApplicationRuntimeState.NoProject(), true)() }
        shoot("29-shell-swarm", 2400, 1500) { shell(ControlRoomDestination.Company, live, false)() }
        shoot("30-shell-district", 2400, 1500) { shell(ControlRoomDestination.Overview, live, false, district = true)() }
        shoot("31-shell-keeper", 2400, 1500) { shell(ControlRoomDestination.Settings, live, false)() }
    }

    /** The neuron, the body of programmatic work, in its states. */
    @Test
    fun shootsTheNeuron() {
        runBlocking { CreatureRigCache.get(CreatureRigMapping.PROGRAMMATIC_RIG) }
        shoot("22-neuron", 1800, 520) {
            AzphaltThemedSurface(Azphalt.Ink) {
                androidx.compose.foundation.layout.Row(Modifier.background(Azphalt.Ink)) {
                    listOf(H2g2WorkflowState.Pending, H2g2WorkflowState.Active, H2g2WorkflowState.Blocked, H2g2WorkflowState.Failed).forEach { state ->
                        NodeMascotSurface(roleLabel = "Python", hueSeed = "python", state = state, motionPhase = { 0.3f }, modifier = Modifier.size(220.dp))
                    }
                }
            }
        }
    }

    @Test
    fun shootsTheFlowchart() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val flow = definition.copy(
            tasks = listOf(
                task("contract", "Contract", architect),
                task("build", "Implement", engineer, "contract"),
                TaskDefinition(TaskDefinitionId("ci"), "Run CI", "Build on GitHub", null, dependsOn = setOf(TaskDefinitionId("build")), executor = TaskExecutor.GitHubAction("ci.yml")),
                TaskDefinition(TaskDefinitionId("notify"), "Tell the channel", "Post the result", null, dependsOn = setOf(TaskDefinitionId("ci")), executor = TaskExecutor.ExternalService("Slack", "post")),
                task("review", "Review", qa, "notify"),
            ),
        )
        shoot("24-flowchart", 900, 1400) {
            GeministratorWorkflowTerrarium(flow, liveRun.copy(workflowDefinitionId = flow.id), roles, selectedTaskId = null, onTaskSelected = {}, onStageDefinition = { it })
        }
    }

    @Test
    fun shootsTheResourceManager() {
        val rig = "resource-manager-sheet-3-variant"
        runBlocking { CreatureRigCache.get(rig) }
        shoot("23-resource-manager", 1800, 520) {
            AzphaltThemedSurface(Azphalt.Ink) {
                androidx.compose.runtime.CompositionLocalProvider(LocalCreatureRig provides { rig }) {
                    androidx.compose.foundation.layout.Row(Modifier.background(Azphalt.Ink)) {
                        listOf(0.05f, 0.3f, 0.55f, 0.8f).forEach { phase ->
                            NodeMascotSurface(roleLabel = rig, hueSeed = rig, state = H2g2WorkflowState.Active, motionPhase = { phase }, modifier = Modifier.size(220.dp))
                        }
                    }
                }
            }
        }
    }
}
