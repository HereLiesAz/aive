package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.orchestration.PlanningPhase
import androidx.compose.runtime.Composable
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

    private fun shoot(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) =
        shootSettled(name, width, height, 900, content)

    /** [settleMillis] past 900 renders further frames, 100 ms apart, for a shot that waits on an animation. */
    private fun shootSettled(name: String, width: Int, height: Int, settleMillis: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        ImageComposeScene(width = width, height = height, density = Density(2f)) {
            LoadAzphaltFonts()
            Box(Modifier.fillMaxSize().background(Azphalt.currentGround.page).padding(12.dp)) { content() }
        }.use { scene ->
            scene.render(0L)
            scene.render(400_000_000L)
            var image = scene.render(900_000_000L)
            for (ms in 1_000..settleMillis step 100) image = scene.render(ms * 1_000_000L)
            val bytes = image.encodeToData()!!.bytes
            ScreenshotChecks.check(name, bytes, width, height)
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

    private fun run(status: WorkflowRunStatus, vararg tasks: Triple<String, TaskRunStatus, String>) = run(definition, status, *tasks)

    private fun run(definition: WorkflowDefinition, status: WorkflowRunStatus, vararg tasks: Triple<String, TaskRunStatus, String>) = WorkflowRun(
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
        // Every axon state (the Figma synapses): complete, active, waiting, and a gated receptor.
        shoot("25-axons", 1800, 1200) {
            val axons = definition.copy(
                tasks = definition.tasks + task("approve", "Sign off", architect, "contract"),
            )
            GeministratorWorkflowTerrarium(
                axons,
                run(
                    axons,
                    WorkflowRunStatus.Running,
                    Triple("contract", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Running, "claude"),
                    Triple("approve", TaskRunStatus.AwaitingApproval, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
        }
        shoot("2-hive-build", 900, 3000) {
            GeministratorWorkflowTerrarium(
                definition,
                restingWorkflowRun(definition),
                roles,
                selectedTaskId = "build",
                onTaskSelected = {},
                onStageDefinition = { it },
                startInBuild = true,
                // A hive at rest also offers Delete hive under the wiring; Remove sits beside Duplicate.
                hiveDeletion = HiveDeletionActions(check = { HiveDeleteVerdict.Allowed }, delete = { HiveDeleteVerdict.Allowed }),
            )
        }
        // While a run of the hive is live, Remove is not offered and the reason is shown.
        shoot("2b-hive-build-live", 900, 2600) {
            GeministratorWorkflowTerrarium(
                definition,
                liveRun.copy(status = com.hereliesaz.geministrator.domain.WorkflowRunStatus.Running),
                roles,
                selectedTaskId = "build",
                onTaskSelected = {},
                onStageDefinition = { it },
                startInBuild = true,
            )
        }
        // A new hive (mockup H6): empty, in Build, named and filled from under the field. A phone.
        shoot("16-new-hive", 780, 2900) {
            val empty = WorkflowDefinition(id = WorkflowDefinitionId("hive-1"), name = "New hive", tasks = emptyList())
            GeministratorWorkflowTerrarium(
                empty,
                restingWorkflowRun(empty),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
                compact = true,
                startInBuild = true,
                heading = { NewHiveCrumb(name = empty.name, onSurface = {}) },
                beneathField = { staged, edit ->
                    // With a keeper linked, the bench also offers "Or describe it and let a keeper draft it".
                    NewHiveBench(staged, roles, onEdit = edit, onRename = {}, drafter = { SwarmDraftResult.Unavailable })
                },
            )
        }
        // The role tray names every role; the preview says what it is and what it connects to.
        shoot("16c-role-preview", 780, 1500) {
            Box(Modifier.fillMaxSize().background(Azphalt.Ink)) {
                RolePreviewContent(
                    BuiltInRoles.QaEngineer,
                    RoleSuggestion(BuiltInRoles.QaEngineer, SuggestionReason.NothingTests, "Implement"),
                    onAdd = {},
                    onClose = {},
                )
            }
        }
        // The camera follows the selection: Overview (nothing selected), then Creature and a path level.
        shoot("17-focus-overview", 900, 1400) {
            GeministratorWorkflowTerrarium(definition, liveRun, roles, selectedTaskId = null, onTaskSelected = {}, onStageDefinition = { it })
        }
        shoot("17-focus-creature", 900, 1400) {
            GeministratorWorkflowTerrarium(definition, liveRun, roles, selectedTaskId = "build", onTaskSelected = {}, onStageDefinition = { it })
        }
        shoot("17-focus-path", 900, 1400) {
            GeministratorWorkflowTerrarium(
                definition, liveRun, roles, selectedTaskId = "build", onTaskSelected = {}, onStageDefinition = { it },
                startFocusLevel = FocusLevel.PathToOrchestrator,
            )
        }
        // An existing hive in Build offers Redraft under the field; it asks before replacing its creatures.
        shoot("16b-redraft", 780, 1688) {
            GeministratorWorkflowTerrarium(
                definition,
                restingWorkflowRun(definition),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
                compact = true,
                startInBuild = true,
                beneathField = { staged, edit -> SwarmDraftBox(staged, { SwarmDraftResult.Unavailable }, edit, redraft = true) },
            )
        }
        shoot("3-whole-hive", 900, 1400) {
            DistrictScreen(
                onLoadDefinitions = { listOf(definition, definition.copy(id = WorkflowDefinitionId("research"), name = "Deep research")) },
                onLoadRuns = { listOf("The Aive" to listOf(liveRun)) },
                onEnterNeighbourhood = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        shoot("5-launch-bar", 900, 600) {
            HiveLaunchBar("shot", onLaunch = { _, _ -> })
        }
        val storePackages = listOf(
            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "r1", name = "Violacea reviewer", description = "Reviews diffs against the contract", author = "azphalt", version = "1", kind = "role"),
            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "r2", name = "Glacia researcher", description = "Finds sources and cites them", author = "azphalt", version = "1", kind = "role"),
            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "w1", name = "Software delivery", description = "A production circuit from planning through adversarial review and release", author = "azphalt", version = "1", kind = "workflow"),
            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "w2", name = "Bug triage", description = "Sorts new issues and drafts a first fix", author = "azphalt", version = "2", kind = "workflow"),
            com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary(id = "w3", name = "Deep research", description = "Evidence-first research with citations", author = "azphalt", version = "1", kind = "workflow", priceStatus = "paid"),
        )
        shoot("7-live-row", 780, 300) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                AzphaltPill(label = "Your hive", seed = "zoom-out-district", onClick = {})
                AzphaltPill(label = "Story", seed = "hive-timeline", onClick = {})
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
        shoot("9-stable-pick", 900, 2600, dark(fresh) { StableSection() })
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
        // B4: the hatch that run made, until seen; then a lucky one, with a stranger.
        shoot("10a-stable-hatch", 900, 1700, dark(kept) { StableSection() })
        val lucky = kept.stable.lastHatching!!.let { it.copy(stranger = kept.catalog.breeds.first { b -> b.lottery == "very-rare" }.id) }
        val luckyStore = DurableUiStateStore(rootKey = "aive.stable.shot.${System.nanoTime()}")
        luckyStore.putString("stable", kotlinx.serialization.json.Json.encodeToString(Stable.serializer(), kept.stable.copy(lastHatching = lucky)))
        val luckyHost = StableHost(luckyStore).also { runBlocking { it.load() } }
        shoot("10b-stable-hatch-stranger", 900, 1900, dark(luckyHost) { StableSection() })
        kept.dismissHatching()
        shoot("10-stable", 900, 1700, dark(kept) { StableSection() })
        // The desktop Stable (Figma): card grid and filter row, three columns wide and two narrower.
        shoot("10c-stable-wide", 2400, 1700, dark(kept) { StableSection() })
        shoot("10d-stable-wide-narrow", 1900, 1900, dark(kept) { StableSection() })
        // B3: QA already wears one of the bought creatures, so the engineer sees it dimmed, and where.
        kept.assign(qa.id, kept.stable.keys[2])
        shoot("11-creature-picker", 900, 900, dark(kept) {
            CreaturePicker(engineer.id, agentName = { id -> roles.firstOrNull { it.id.value == id }?.name })
        })
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
        // Breeds in the Store (mockup B5), on a phone: the tabs, the breeds, the footnote.
        shoot("14-breeds-store", 780, 1688) {
            androidx.compose.runtime.CompositionLocalProvider(LocalStableHost provides kept, LocalBreedPurchases provides shop) {
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    StoreTabsRow(wide = false) {
                        AzphaltTabs(
                            tabs = listOf("Agents", "Hives", "Breeds", "Installed").map { AzphaltTabItem(it, it) },
                            selectedKey = "Breeds",
                            onSelected = {},
                            seedPrefix = "shot",
                        )
                    }
                    StoreBreeds()
                }
            }
        }
        // The Store on a wide screen (the Figma design): tabs on Ink, a hive leading, the cards.
        shoot("14b-store-wide", 2400, 1500) {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                StoreTabsRow(wide = true) {
                    AzphaltTabs(
                        tabs = listOf("All", "Agents", "Hives", "Breeds", "Installed", "Models", "LLMs").map { AzphaltTabItem(it, it) },
                        selectedKey = "Hives",
                        onSelected = {},
                        seedPrefix = "shot",
                    )
                }
                FeaturedStoreHive(storePackages[2], onView = {})
                StoreCardGrid(storePackages.filter { it.kind == "workflow" }, columns = 3, selectedId = "w2", footerOf = { it.latest }, onInspect = {})
            }
        }
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
                    content = { parts, partModifier ->
                        ProviderSettingsScreen(
                            connectedProviderIds = providers,
                            sections = parts,
                            compact = compact,
                            trailing = { part ->
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
        // The former drifter discovery lives inside Connections, with no separate hive-edge sheet.
        DurableUiState.store.putBoolean("keeper.connections.adding-a2a-agent", true)
        try {
            shoot("18b-keeper-remote-a2a", 2400, 2100) { room(KeeperSection.Connections, false, signedIn, two)() }
        } finally {
            DurableUiState.store.putBoolean("keeper.connections.adding-a2a-agent", false)
        }
        shoot("19-keeper-phone-connections", 824, 1830) { room(KeeperSection.Connections, true, EntireStatus(version = "Entire CLI 0.9.2"), two)() }
        shoot("20-keeper-projects", 2400, 1500) { room(KeeperSection.Projects, false, signedIn, two)() }
        shoot("21-keeper-appearance", 2400, 1500) { room(KeeperSection.Appearance, false, signedIn, two)() }
        shoot("36-keeper-limits", 2400, 1500) { room(KeeperSection.Limits, false, signedIn, two)() }
        shoot("37-keeper-phone-device", 824, 1830) { room(KeeperSection.Device, true, signedIn, two)() }
        shoot("38-keeper-appearance-narrow-desktop", 1700, 1200) { room(KeeperSection.Appearance, false, signedIn, two)() }
    }

    /** The Inbox page: list and the open one on a wide window, records on a phone. */
    @Test
    fun shootsTheInbox() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val project = com.hereliesaz.geministrator.domain.Project(ProjectId("aive"), "The Aive", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val plan = com.hereliesaz.geministrator.domain.ArtifactRef(
            id = com.hereliesaz.geministrator.domain.ArtifactId("plan-v3"),
            kind = com.hereliesaz.geministrator.domain.ArtifactKind.Architecture,
            taskRunId = TaskRunId("r-contract"),
            label = "System architecture v3",
            textContent = "Splits the release service from the build service; three public interfaces change.",
            createdAtEpochMillis = 1,
        )
        val waiting = run(
            WorkflowRunStatus.Running,
            Triple("contract", TaskRunStatus.AwaitingApproval, "claude"),
            Triple("build", TaskRunStatus.Escalated, "claude"),
            Triple("review", TaskRunStatus.AwaitingApproval, "gemini"),
        ).let { r ->
            r.copy(
                taskRuns = r.taskRuns.mapValues { (id, tr) ->
                    when (id.value) {
                        "contract" -> tr.copy(artifacts = listOf(plan), progressMessage = "The proposed boundary changes three public interfaces. Review it before the engineer builds on it.")
                        "build" -> tr.copy(attempt = 2, blockingReason = com.hereliesaz.geministrator.domain.BlockingReason("TESTS_RED", "Two integration tests still fail after the retry."))
                        else -> tr
                    }
                },
            )
        }
        val live = ApplicationRuntimeState.Live(LiveWorkflowPresentation(project, definition, waiting, roles))
        DurableUiState.store.putString("inbox.selected", "")
        shoot("39-inbox-wide", 2400, 1500) {
            LiveInboxScreen(live, {}, {}, { _, _ -> }, Modifier.fillMaxSize())
        }
        shoot("40-inbox-phone", 824, 1830) {
            LiveInboxScreen(live, {}, {}, { _, _ -> }, Modifier.fillMaxSize())
        }
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
        // The Figma desktop shell: the labelled rail above 1080dp, icons only between 820 and 1080.
        shoot("36-shell-wide-stable", 2400, 1500) { shell(ControlRoomDestination.Stable, live, false)() }
        shoot("37-shell-wide-store", 2400, 1500) { shell(ControlRoomDestination.AddOns, live, false)() }
        shoot("38-shell-icon-rail", 1900, 1400) { shell(ControlRoomDestination.Overview, live, false)() }
        shoot("39-shell-icon-rail-memory", 1900, 1400) { shell(ControlRoomDestination.Memory, live, false)() }
        shoot("40-page-title", 2000, 500) {
            AzphaltPageTitle(
                eyebrow = "Keeper attention",
                title = "Messages",
                line = "Every creature that needs you, in one place.",
                action = { AzphaltPill(label = "Mark all read", seed = "shot-mark-all", tone = PillTone.Primary, onClick = {}) },
            )
        }
    }

    /** The Memory swarm: Simple (the memory field and its notes) on desktop, a mid width and a phone; Technical unchanged. */
    @Test
    fun shootsTheMemorySwarm() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val store = com.hereliesaz.geministrator.memory.InMemoryMemoryStore()
        val controller = com.hereliesaz.geministrator.memory.MemoryLayerController(
            banks = com.hereliesaz.geministrator.memory.MemoryBanks.inMemory(mapOf("feature-delivery-run" to store)),
            settingsStore = com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore(com.russhwolf.settings.MapSettings()),
            engineProvider = object : com.hereliesaz.geministrator.memory.MemoryEngineProvider {},
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        )
        runBlocking {
            val queue = com.hereliesaz.geministrator.memory.MemoryConsolidationQueue(store)
            listOf(
                "Review every repository change before it lands. The reviewer reads the diff and the tests.",
                "Release notes come from the changelog. The engineer writes them after review passes.",
                "Run the tests before review. A failing test sends the work back to the engineer.",
            ).forEachIndexed { i, text ->
                queue.enqueueSession(
                    com.hereliesaz.geministrator.memory.MemorySessionEnvelope(
                        "session-$i", workflowRunId = "feature-delivery-run", userPrompt = text,
                        parts = listOf(com.hereliesaz.geministrator.memory.MemorySessionPart(com.hereliesaz.geministrator.memory.MemorySourceKind.Message, "agent", text)),
                        closedAtEpochMillis = 1_000L + i,
                    ),
                )
            }
            controller.drain()
            // One more session arrives after the drain, so the field has something waiting.
            queue.enqueueSession(
                com.hereliesaz.geministrator.memory.MemorySessionEnvelope(
                    "session-late", workflowRunId = "feature-delivery-run", userPrompt = "Ship after approval.",
                    parts = emptyList(), closedAtEpochMillis = 2_000L,
                ),
            )
            controller.selectBank("feature-delivery-run")
        }
        fun memory(view: MemoryView, compact: Boolean) = @androidx.compose.runtime.Composable {
            DurableUiState.store.putString(MemoryView.STORAGE_KEY, view.name)
            MemoryLayerScreen(controller, connectedProviderIds = emptySet(), compact = compact)
        }
        shoot("40-memory-simple", 2400, 1500) { memory(MemoryView.Simple, false)() }
        shoot("41-memory-simple-mid", 1400, 1700) { memory(MemoryView.Simple, false)() }
        shoot("42-memory-simple-phone", 824, 1830) { memory(MemoryView.Simple, true)() }
        shoot("43-memory-technical", 2400, 1500) { memory(MemoryView.Technical, false)() }
        DurableUiState.store.putString(MemoryView.STORAGE_KEY, MemoryView.Simple.name)
    }

    /** The wide hive page (Figma desktop): title row, framed hive with its feed bar, and the docked creature panel. */
    @Test
    fun shootsTheWorkspace() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val project = com.hereliesaz.geministrator.domain.Project(ProjectId("aive"), "The Aive", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val live = ApplicationRuntimeState.Live(LiveWorkflowPresentation(project, definition, liveRun, roles))
        val fresh = StableHost(DurableUiStateStore(rootKey = "aive.stable.shot.${System.nanoTime()}")).also { runBlocking { it.load() } }
        fun page(selected: String?, tab: CreatureTab = CreatureTab.Needs, state: ApplicationRuntimeState = live) = @androidx.compose.runtime.Composable {
            DurableUiState.store.putBoolean("overview.zoom.district", false)
            DurableUiState.store.putBoolean("overview.timeline", false)
            DurableUiState.store.putString("overview.resting-hive", "")
            DurableUiState.store.putString("creature.panel.tab", tab.name)
            DurableUiState.store.putString("creature.panel.task", selected.orEmpty())
            androidx.compose.runtime.CompositionLocalProvider(LocalStableHost provides fresh) {
                ControlRoom(
                    destination = ControlRoomDestination.Overview,
                    onDestinationSelected = {},
                    selectedTaskId = selected,
                    onTaskSelected = {},
                    onLaunchWorkflow = { _, _, _, _ -> },
                    onApproveTask = {},
                    onRejectPlan = {},
                    onResolveEscalation = { _, _ -> },
                    onRecoverFromCorruption = {},
                    onLoadWorkflowDefinitions = { listOf(definition) },
                    onLoadRunHistory = { listOf(project to listOf(liveRun)) },
                    compact = false,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(),
                    runtimeState = state,
                )
            }
        }
        shoot("36-workspace-live", 2880, 1800) { page(null)() }
        // The same hive in the Swarm view: every creature drawn at once, the unborn included.
        DurableUiState.store.putBoolean("hive.view.swarm", true)
        try {
            shoot("36b-workspace-swarm", 2880, 1800) { page(null)() }
        } finally {
            DurableUiState.store.putBoolean("hive.view.swarm", false)
        }
        shoot("37-workspace-creature-needs", 2880, 1800) { page("build", CreatureTab.Needs)() }
        shoot("38-workspace-creature-mind", 2880, 1800) { page("build", CreatureTab.Mind)() }
        shoot("39-workspace-creature-gated", 1900, 1500) { page("review", CreatureTab.Needs)() }
        val finished = liveRun.copy(status = WorkflowRunStatus.Completed, taskRuns = liveRun.taskRuns.mapValues { (_, r) -> r.copy(status = TaskRunStatus.Completed) })
        shoot("40-workspace-finished", 2880, 1800) { page(null, state = ApplicationRuntimeState.Live(LiveWorkflowPresentation(project, definition, finished, roles)))() }
    }

    /** A creature's panel (docs/Keepers-Room.md, stage 3): its Needs, its Work, its Mind. */
    @Test
    fun shootsTheCreaturePanel() {
        val project = com.hereliesaz.geministrator.domain.Project(ProjectId("aive"), "The Aive", createdAtEpochMillis = 1, updatedAtEpochMillis = 2)
        val presentation = LiveWorkflowPresentation(project, definition, liveRun, roles)
        fun panel(task: String, tab: CreatureTab, compact: Boolean) = @androidx.compose.runtime.Composable {
            AzphaltThemedSurface(Azphalt.Ink) {
                TechnicalInspector(
                    selectedTaskId = task,
                    liveWorkflow = presentation,
                    tab = tab,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        shoot("32-creature-needs", 824, 1830) { panel("review", CreatureTab.Needs, true)() }
        shoot("33-creature-work", 824, 1830) { panel("build", CreatureTab.Work, true)() }
        shoot("34-creature-mind", 824, 1830) { panel("build", CreatureTab.Mind, true)() }
        // A creature's job (mockup H4), at the mockup's phone size: its objective, criteria, approval, retries, where it runs.
        val job = definition.copy(
            tasks = definition.tasks.map { task ->
                if (task.id.value != "build") task else task.copy(
                    objective = "Implement the smallest coherent change satisfying the contract.",
                    acceptanceCriteria = listOf(
                        com.hereliesaz.geministrator.domain.AcceptanceCriterion("Every changed component maps to a requirement"),
                        com.hereliesaz.geministrator.domain.AcceptanceCriterion("Existing behaviour outside scope is preserved"),
                    ),
                    approvalPolicy = com.hereliesaz.geministrator.domain.ApprovalPolicy.HumanApproval,
                    escalationPolicy = com.hereliesaz.geministrator.domain.EscalationPolicy.RequireHumanDecision,
                )
            },
        )
        shoot("35-creature-job", 780, 1688) {
            Box(Modifier.fillMaxSize().background(Azphalt.Ink)) {
                // A library host in memory, so the job is editable as it is in the app.
                val memory = com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence()
                runBlocking { memory.definitions.put(job) }
                val host = com.hereliesaz.geministrator.WorkflowLibraryHost(
                    persistence = memory,
                    storeService = null,
                    authoredStore = object : com.hereliesaz.geministrator.azphalt.AuthoredWorkflowStore {
                        private val ids = mutableSetOf(job.id)
                        override suspend fun all() = ids.toSet()
                        override suspend fun add(id: WorkflowDefinitionId) { ids += id }
                        override suspend fun remove(id: WorkflowDefinitionId) { ids -= id }
                    },
                    launchWorkflow = { _, _, _, _ -> },
                    onPackagesChanged = {},
                )
                androidx.compose.runtime.CompositionLocalProvider(LocalWorkflowLibraryHost provides host) {
                    AzphaltThemedSurface(Azphalt.Ink) {
                        TechnicalInspector(
                            selectedTaskId = "build",
                            liveWorkflow = LiveWorkflowPresentation(project, job, liveRun, roles),
                            tab = CreatureTab.Work,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    /** The timeline as a waterfall: parallel attempts, a plan gate, a retry and a creature still going. */
    @Test
    fun shootsTheWaterfall() {
        val runId = WorkflowRunId("run-waterfall")
        fun id(task: String) = TaskDefinitionId(task)
        val events = listOf(
            com.hereliesaz.geministrator.events.TaskStarted(runId, id("contract"), 1, 0),
            com.hereliesaz.geministrator.events.ApprovalRequired(runId, id("contract"), com.hereliesaz.geministrator.domain.ApprovalGateId("g"), "Approve the plan", 20_000),
            com.hereliesaz.geministrator.events.ApprovalDecisionReceived(runId, id("contract"), com.hereliesaz.geministrator.domain.ApprovalGateId("g"), true, null, 50_000),
            com.hereliesaz.geministrator.events.TaskCompleted(runId, id("contract"), 90_000),
            com.hereliesaz.geministrator.events.TaskStarted(runId, id("build"), 1, 60_000),
            com.hereliesaz.geministrator.events.TaskFailed(runId, id("build"), "tests red", 120_000),
            com.hereliesaz.geministrator.events.RetryScheduled(runId, id("build"), 2, "tests red", 120_000),
            com.hereliesaz.geministrator.events.TaskStarted(runId, id("build"), 2, 135_000),
            com.hereliesaz.geministrator.events.ProviderUsageRecorded(runId, id("build"), AgentProviderId("claude"), com.hereliesaz.geministrator.domain.ProviderRunId("p"), inputTokens = 12_400, outputTokens = 2_100, costUsd = 0.21, occurredAtEpochMillis = 150_000),
            com.hereliesaz.geministrator.events.TaskStarted(runId, id("review"), 1, 95_000),
            com.hereliesaz.geministrator.events.TaskCompleted(runId, id("review"), 140_000),
        )
        val waterfall = runWaterfall(events, nowEpochMillis = 200_000)
        val names = definition.tasks.associate { it.id to it.name }
        shoot("35-waterfall", 900, 1100) {
            RunWaterfallChart(waterfall, taskName = { names[it] ?: it.value }, selectedTaskId = "build", onTaskSelected = {})
        }
    }

    /**
     * Birth on the field: only the next generation is drawn, as eggs gestated by whoever feeds them.
     * Left: a plan in flight (Implement working, Review an egg with one gestator, Ship not yet shown).
     * Middle: parallel work joining (Ship's egg has two gestators, one of them an egg itself).
     * Right: a failed gestator withers the egg it was feeding.
     */
    @Test
    fun shootsTheEggs() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val chain = definition
        val joined = WorkflowDefinition(
            id = WorkflowDefinitionId("joined"),
            name = "Joined",
            tasks = listOf(
                task("plan", "Plan", architect),
                task("build", "Implement", engineer, "plan"),
                task("test", "Test", qa, "plan"),
                task("review", "Review", qa, "build"),
                task("ship", "Ship", engineer, "review", "test"),
            ),
        )
        shootSettled("38-eggs-chain", 900, 1400, 2_500) {
            GeministratorWorkflowTerrarium(
                chain,
                run(
                    chain,
                    WorkflowRunStatus.Running,
                    Triple("contract", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Running, "claude"),
                    Triple("review", TaskRunStatus.Blocked, "gemini"),
                    Triple("ship", TaskRunStatus.Blocked, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
        }
        shootSettled("39-eggs-joined", 900, 1400, 2_500) {
            GeministratorWorkflowTerrarium(
                joined,
                run(
                    joined,
                    WorkflowRunStatus.Running,
                    Triple("plan", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Running, "claude"),
                    Triple("test", TaskRunStatus.Planning, "gemini"),
                    Triple("review", TaskRunStatus.Blocked, "gemini"),
                    Triple("ship", TaskRunStatus.Blocked, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
        }
        shootSettled("40-eggs-withered", 900, 1400, 2_500) {
            GeministratorWorkflowTerrarium(
                chain,
                run(
                    chain,
                    WorkflowRunStatus.Running,
                    Triple("contract", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Failed, "claude"),
                    Triple("review", TaskRunStatus.Blocked, "gemini"),
                    Triple("ship", TaskRunStatus.Blocked, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
        }
    }

    /**
     * One creature doing several tasks of its role. The engineer does Implement, then Ship: left, it is at
     * Implement and Ship is a place it will work; right, it has walked on to Ship and Implement is the place
     * it worked. The two QA tasks run side by side, so each keeps a creature of its own.
     */
    @Test
    fun shootsTheSharedCreature() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        val joined = WorkflowDefinition(
            id = WorkflowDefinitionId("shared"),
            name = "Shared",
            tasks = listOf(
                task("plan", "Plan", architect),
                task("build", "Implement", engineer, "plan"),
                task("test", "Test", qa, "plan"),
                task("review", "Review", qa, "build"),
                task("ship", "Ship", engineer, "review", "test"),
            ),
        )
        shootSettled("46-shared-creature-at-first-task", 900, 1400, 2_500) {
            GeministratorWorkflowTerrarium(
                joined,
                run(
                    joined,
                    WorkflowRunStatus.Running,
                    Triple("plan", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Running, "claude"),
                    Triple("test", TaskRunStatus.Running, "gemini"),
                    Triple("review", TaskRunStatus.Blocked, "gemini"),
                    Triple("ship", TaskRunStatus.Blocked, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
        }
        shootSettled("47-shared-creature-at-second-task", 900, 1400, 2_500) {
            GeministratorWorkflowTerrarium(
                joined,
                run(
                    joined,
                    WorkflowRunStatus.Running,
                    Triple("plan", TaskRunStatus.Completed, "claude"),
                    Triple("build", TaskRunStatus.Completed, "claude"),
                    Triple("test", TaskRunStatus.Completed, "gemini"),
                    Triple("review", TaskRunStatus.Completed, "gemini"),
                    Triple("ship", TaskRunStatus.Running, "claude"),
                ),
                roles,
                selectedTaskId = null,
                onTaskSelected = {},
                onStageDefinition = { it },
            )
        }
    }

    /**
     * The launch while its plan is made: the planner's egg growing, the reviewer hatching from it, the first
     * step's egg gestated by both, the keeper's decision (approved, and never approved), and the hatch.
     */
    @Test
    fun shootsThePlanning() {
        runBlocking { CreatureRigMapping.allSlugs.forEach { CreatureRigCache.get(it) } }
        fun stage(phase: PlanningPhase, round: Int, withPlan: Boolean = true, verdict: com.hereliesaz.geministrator.orchestration.PlanningVerdict? = null) =
            com.hereliesaz.geministrator.orchestration.PlanningStage(
                phase = phase,
                planner = BuiltInRoles.Orchestrator,
                reviewer = BuiltInRoles.AdversarialReviewer,
                firstStepRole = engineer.takeIf { withPlan },
                firstStepName = "Implement".takeIf { withPlan },
                round = round,
                verdict = verdict,
            )
        fun step(id: String, name: String, after: String? = null, approval: Boolean = false) = com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep(
            id, name, "Do $name carefully, then hand it on.", "implementation-engineer", listOfNotNull(after), approval,
        )
        val plan = com.hereliesaz.geministrator.orchestration.OrchestrationPlan(
            listOf(step("a", "Define scope"), step("b", "Implement", "a"), step("c", "Ship", "b", approval = true)),
        )
        val reject = { why: String -> com.hereliesaz.geministrator.orchestration.PlanReviewRound(plan, com.hereliesaz.geministrator.orchestration.PlanVerdict.Reject(why)) }
        val approve = com.hereliesaz.geministrator.orchestration.PlanReviewRound(plan, com.hereliesaz.geministrator.orchestration.PlanVerdict.Approve("The tests now come before the code."))
        val names = mapOf("implementation-engineer" to "Implementation Engineer")
        val overlay: @Composable (com.hereliesaz.geministrator.orchestration.PlanningStage, com.hereliesaz.geministrator.orchestration.PlanDecision?) -> Unit = { st, decision ->
            PlanningOverlay(stage = st, decision = decision, onStart = {}, onDecline = {}, stepRows = emptyList(), maxRows = 5)
        }
        shootSettled("48-planning-drafting", 900, 1300, 2_500) { overlay(stage(PlanningPhase.Planning, 0, withPlan = false), null) }
        shootSettled("49-planning-reviewing", 900, 1300, 2_500) { overlay(stage(PlanningPhase.Reviewing, 1), null) }
        shootSettled("50-planning-keeper-approved", 900, 1700, 2_500) {
            overlay(
                stage(PlanningPhase.AwaitingKeeper, 2, verdict = com.hereliesaz.geministrator.orchestration.PlanningVerdict.Approved),
                com.hereliesaz.geministrator.orchestration.PlanDecision(
                    "Ship it", "Adversarial Reviewer",
                    com.hereliesaz.geministrator.orchestration.PlanReviewOutcome(plan, listOf(reject("There is no test step before the code."), approve)),
                    names,
                ),
            )
        }
        shootSettled("51-planning-keeper-not-approved", 900, 1700, 2_500) {
            overlay(
                stage(PlanningPhase.AwaitingKeeper, 3, verdict = com.hereliesaz.geministrator.orchestration.PlanningVerdict.NotApproved),
                com.hereliesaz.geministrator.orchestration.PlanDecision(
                    "Ship it", "Adversarial Reviewer",
                    com.hereliesaz.geministrator.orchestration.PlanReviewOutcome(
                        plan,
                        listOf(reject("No rollback."), reject("Still no rollback."), reject("Rollback is still missing.")),
                    ),
                    names,
                ),
            )
        }
        shootSettled("52-planning-started", 900, 1300, 2_500) { overlay(stage(PlanningPhase.Started, 2), null) }
    }

    /** An egg hatching: shown full, then released. The creature arrives over the transition, not all at once. */
    @Test
    fun shootsTheHatch() {
        val unborn = androidx.compose.runtime.mutableStateOf(true)
        val egg = CreatureBirth.Egg(1f, listOf(EggGestator("a", 1f), EggGestator("b", 1f)))
        val frames = mutableMapOf<String, ByteArray>()
        ImageComposeScene(width = 600, height = 600, density = Density(2f)) {
            LoadAzphaltFonts()
            Box(Modifier.fillMaxSize().background(Azphalt.Ink)) {
                Box(Modifier.size(140.dp)) {
                    BirthTransition(
                        unborn = unborn.value,
                        egg = { CreatureEgg(egg, "hatch", listOf(androidx.compose.ui.graphics.Color(0xFF2060D0) to 1f), Modifier.fillMaxSize()) },
                        born = { Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2060D0))) },
                    )
                }
            }
        }.use { scene ->
            scene.render(0L)
            frames["egg"] = scene.render(1_000_000_000L).encodeToData()!!.bytes
            unborn.value = false
            scene.render(1_100_000_000L)
            frames["mid"] = scene.render(1_350_000_000L).encodeToData()!!.bytes
            frames["born"] = scene.render(3_000_000_000L).encodeToData()!!.bytes
        }
        frames.forEach { (name, bytes) -> File(out, "41-hatch-$name.png").writeBytes(bytes) }
        assertTrue(!frames.getValue("egg").contentEquals(frames.getValue("mid")), "released: the egg is gone from the first frame")
        assertTrue(!frames.getValue("mid").contentEquals(frames.getValue("born")), "the creature is still arriving mid-transition")
    }

    /** The Timeline's row (Overview, Waterfall, Events, Memory, Log) at a phone's width, Waterfall, Memory then Log open. */
    @Test
    fun shootsTheTimelineRow() {
        val runId = WorkflowRunId("run-timeline-row")
        val events = listOf(
            com.hereliesaz.geministrator.events.TaskStarted(runId, TaskDefinitionId("contract"), 1, 0),
            com.hereliesaz.geministrator.events.TaskCompleted(runId, TaskDefinitionId("contract"), 90_000),
        )
        try {
            val appLines = kotlinx.coroutines.flow.MutableStateFlow(
                listOf(
                    com.hereliesaz.geministrator.log.LogLine(1_794_055_860_000L, "AiveLaunch", "Launch blocked: no repository chosen"),
                    com.hereliesaz.geministrator.log.LogLine(1_794_055_863_412L, "AzphaltStore", "Store step 'catalog' failed: timeout"),
                ),
            )
            val sources = RunLogSources(
                agents = kotlinx.coroutines.flow.MutableStateFlow(emptyMap()),
                agentScope = { null },
                app = appLines,
                memory = kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
            )
            for ((view, name) in listOf("waterfall" to "36-timeline-row", "memory" to "37-timeline-memory", "log" to "38-timeline-log")) {
                DurableUiState.store.putString("overview.timeline.view", view)
                DurableUiState.store.putString("overview.timeline.log.source", if (view == "log") "App" else "Run")
                shootSettled(name, 780, 900, 2_500) {
                    RunTimeline(
                        runKey = "run-timeline-row",
                        onLoadTimeline = { events },
                        onShowHive = {},
                        memoryContent = { MemoryLayerScreen(controller = null, connectedProviderIds = emptySet(), compact = true) },
                        logSources = sources,
                    )
                }
            }
        } finally {
            DurableUiState.store.putString("overview.timeline.view", "waterfall")
            DurableUiState.store.putString("overview.timeline.log.source", "Run")
        }
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
    fun shootsEditWithAFlippedCard() {
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
        // Edit, the "build" creature turned over to its card (the sheet itself is the shell's).
        val host = CardFlipHost().apply { state = CardFlip(flippedId = "build") }
        shoot("24-edit-flipped-card", 900, 1800) {
            androidx.compose.runtime.CompositionLocalProvider(LocalCardFlip provides host) {
                GeministratorWorkflowTerrarium(flow, liveRun.copy(workflowDefinitionId = flow.id), roles, selectedTaskId = "build", onTaskSelected = {}, onStageDefinition = { it }, startInBuild = true)
            }
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
