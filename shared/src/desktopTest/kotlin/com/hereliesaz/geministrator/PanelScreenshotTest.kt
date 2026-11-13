package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
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
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.workflow.SessionActivityKey
import com.hereliesaz.geministrator.workflow.SessionActivityKind
import com.hereliesaz.geministrator.workflow.SessionActivityLine
import com.hereliesaz.geministrator.workflow.WorkflowFlowchart
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetBridge
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetRecord
import com.hereliesaz.geministrator.workflow.refinement.SettingsLadderDatasetStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/** Screenshots of the panels added for the keeper's logs, activity, flowchart and data, for checking by eye. */
class PanelScreenshotTest {
    private val out = File("build/hive-screens").apply { mkdirs() }

    private fun shoot(name: String, width: Int, height: Int, content: @Composable () -> Unit) {
        ImageComposeScene(width = width, height = height, density = Density(2f)) {
            LoadAzphaltFonts()
            Box(Modifier.fillMaxSize().background(Azphalt.currentGround.page).padding(12.dp)) { content() }
        }.use { scene ->
            scene.render(0L)
            scene.render(400_000_000L)
            var image = scene.render(900_000_000L)
            for (ms in 1_000..2_000 step 100) image = scene.render(ms * 1_000_000L)
            val bytes = image.encodeToData()!!.bytes
            ScreenshotChecks.check(name, bytes, width, height)
            File(out, "$name.png").writeBytes(bytes)
        }
    }

    private fun agent(id: String, name: String, role: com.hereliesaz.geministrator.domain.RoleDefinition, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id), name = name, objective = name, roleId = role.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(), executor = TaskExecutor.RoleAgent(role.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("ship"),
        name = "Ship",
        tasks = listOf(
            agent("plan", "Plan the release", BuiltInRoles.Architect),
            agent("build", "Implement", BuiltInRoles.ImplementationEngineer, "plan"),
            TaskDefinition(
                TaskDefinitionId("ci"), "Run CI", "Run CI", null, dependsOn = setOf(TaskDefinitionId("build")),
                executor = TaskExecutor.GitHubAction("ci.yml"),
            ),
            agent("review", "Review", BuiltInRoles.QaEngineer, "ci"),
            agent(
                "fix", "Fix what CI found", BuiltInRoles.ImplementationEngineer, "ci",
            ).copy(condition = TaskCondition.OnFailure(TaskDefinitionId("ci"))),
            TaskDefinition(
                TaskDefinitionId("approve"), "Approve the release", "Approve", null, dependsOn = setOf(TaskDefinitionId("review")),
                executor = TaskExecutor.HumanApproval(),
            ),
            TaskDefinition(
                TaskDefinitionId("deploy"), "Deploy", "Deploy", null, dependsOn = setOf(TaskDefinitionId("approve")),
                executor = TaskExecutor.Deployment("production"),
            ),
        ),
    )

    private val run = WorkflowRun(
        id = WorkflowRunId("run-1"), projectId = ProjectId("aive"), workflowDefinitionId = definition.id,
        objective = "Ship 2.4.0", status = WorkflowRunStatus.Running,
        taskRuns = listOf(
            "plan" to TaskRunStatus.Completed, "build" to TaskRunStatus.Completed, "ci" to TaskRunStatus.Completed,
            "review" to TaskRunStatus.Running, "fix" to TaskRunStatus.Cancelled, "approve" to TaskRunStatus.Blocked,
            "deploy" to TaskRunStatus.Blocked,
        ).associate { (id, status) ->
            val task = definition.tasks.first { it.id.value == id }
            task.id to TaskRun(TaskRunId("r-$id"), task.id, status, assignedRoleId = task.roleId, assignedProviderId = AgentProviderId("claude"))
        },
        createdAtEpochMillis = 1, updatedAtEpochMillis = 2,
    )

    @Test
    fun shootsTheFlowchartCards() {
        // The back of each creature in Edit: the flowchart's card, one selected.
        val flow = WorkflowFlowchart.of(definition, run)
        shoot("50-flowchart-cards", 400, 900) {
            androidx.compose.foundation.layout.Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                flow.nodes.forEach { node -> FlowCardFace(node, selected = node.id.value == "review", modifier = Modifier.width(176.dp).height(76.dp)) }
            }
        }
    }

    @Test
    fun shootsTheActivityLogByRole() {
        val runId = WorkflowRunId("run-1")
        val events = listOf(
            TaskStarted(runId, TaskDefinitionId("plan"), 1, 1_794_055_860_000L),
            TaskCompleted(runId, TaskDefinitionId("plan"), 1_794_055_900_000L),
            TaskStarted(runId, TaskDefinitionId("build"), 1, 1_794_055_905_000L),
            TaskStarted(runId, TaskDefinitionId("review"), 1, 1_794_055_990_000L),
        )
        fun key(task: String) = SessionActivityKey(TaskRunId("r-$task"), ProviderRunId("p-$task"))
        val feeds = mapOf(
            key("plan") to listOf(SessionActivityLine(1, SessionActivityKind.Thinking, "Weighing the contract options", 1_794_055_870_000L)),
            key("build") to listOf(
                SessionActivityLine(1, SessionActivityKind.Progress, "Edits WorkflowCompositionService.kt", 1_794_055_920_000L),
                SessionActivityLine(2, SessionActivityKind.Progress, "Runs the desktop tests", 1_794_055_950_000L),
            ),
            key("review") to listOf(SessionActivityLine(1, SessionActivityKind.Thinking, "Reading the diff", 1_794_056_000_000L)),
        )
        val names = definition.tasks.associate { it.id to it.name }
        val roles = run.taskRuns.values.mapNotNull { r -> r.assignedRoleId?.let { r.taskDefinitionId to it.value } }.toMap()
        val sources = RunLogSources(
            agents = MutableStateFlow(feeds),
            agentScope = { names[run.taskRuns.values.first { r -> r.id == it.taskRunId }.taskDefinitionId] },
            app = MutableStateFlow(emptyList()),
            memory = null,
            roleOfTask = { roles[it] },
            roleOfSession = { key -> roles[run.taskRuns.values.first { r -> r.id == key.taskRunId }.taskDefinitionId] },
        )
        // The keeper's pick lives in the machine's real preferences, shared with every other test in this JVM and
        // every earlier run: put back what was there, not a guess at it.
        val before = DurableUiState.store.getString("overview.timeline.log.source")
        DurableUiState.store.putString("overview.timeline.log.source", "Activity")
        try {
            shoot("51-activity-by-role", 780, 900) {
                RunLogView(events = events, taskName = { names[it] ?: it.value }, sources = sources, modifier = Modifier.fillMaxSize())
            }
        } finally {
            if (before == null) DurableUiState.store.remove("overview.timeline.log.source")
            else DurableUiState.store.putString("overview.timeline.log.source", before)
        }
    }

    @Test
    fun shootsTheRefinementDataPanel() = runBlocking {
        val settings = MapSettings()
        val capture = LadderDatasetBridge.attach(settings)
        try {
            capture.enabled = true
            val store = SettingsLadderDatasetStore(settings)
            fun level(i: Int, level: Int, gate: String) = LadderDatasetRecord(
                id = "l$i", kind = LadderDatasetRecord.KIND_LEVEL, ladder = "run${i % 3}/plan", taskId = "t$level", level = level,
                total = 3, objective = "o", artifact = "{}", structured = true, valid = true, gateVerdict = gate,
                capturedAtEpochMillis = 1_794_055_860_000L + i * 60_000,
            )
            store.append(
                (1..14).map { level(it, 1 + it % 3, if (it % 5 == 0) "reject" else "approve") } +
                    LadderDatasetRecord(
                        id = "o1", kind = LadderDatasetRecord.KIND_OUTCOME, ladder = "run1/plan", taskId = "t", finalStatus = "Completed",
                        verifierVerdict = "approve", capturedAtEpochMillis = 1_794_056_900_000L,
                    ),
            )
            shoot("52-refinement-data", 780, 1100) {
                androidx.compose.foundation.layout.Column(
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) { MemoryRefinementDataSection() }
            }
        } finally {
            LadderDatasetBridge.reset()
        }
    }

    @Test
    fun shootsTheRoleActivity() {
        val later = run.copy(
            id = WorkflowRunId("run-2"), updatedAtEpochMillis = 1_794_056_000_000L,
            taskRuns = run.taskRuns.mapValues { (_, taskRun) ->
                taskRun.copy(id = TaskRunId("b-${taskRun.id.value}"), status = TaskRunStatus.Completed,
                    usage = com.hereliesaz.geministrator.domain.TaskUsage(12_400, 3_100, 0.18))
            },
        )
        val earlier = run.copy(
            id = WorkflowRunId("run-1"), updatedAtEpochMillis = 1_794_055_000_000L,
            taskRuns = run.taskRuns.mapValues { (_, taskRun) ->
                taskRun.copy(status = if (taskRun.taskDefinitionId.value == "build") TaskRunStatus.Failed else taskRun.status, attempt = 2)
            },
        )
        val activity = com.hereliesaz.geministrator.workflow.RoleActivities.of(
            BuiltInRoles.ImplementationEngineer.id, listOf(definition), listOf(later, earlier),
        )
        shoot("53-role-activity", 780, 700) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) { RoleActivityView(activity) }
        }
    }

    @Test
    fun schedulesPanel() {
        val now = 20_765L * 86_400_000L + 9 * 3_600_000L
        val store = com.hereliesaz.geministrator.workflow.schedule.InMemoryWorkflowScheduleStore()
        runBlocking {
            com.hereliesaz.geministrator.workflow.CodingWorkflowSuite.all().filter { it.schedule != null }.take(3).forEachIndexed { i, entry ->
                store.put(
                    com.hereliesaz.geministrator.workflow.schedule.WorkflowSchedule(
                        id = "schedule-${entry.definition.id.value}", workflowId = entry.definition.id.value,
                        name = entry.definition.name, objective = entry.schedule!!.objective, cadence = entry.schedule!!.cadence,
                        enabled = i == 0, createdAtEpochMillis = now,
                        lastStartedAtEpochMillis = if (i == 0) now - 86_400_000L else null,
                        lastNote = if (i == 0) "Started." else null,
                    ),
                )
            }
        }
        shoot("54-schedules", 780, 1500) {
            androidx.compose.runtime.CompositionLocalProvider(LocalWorkflowScheduleStore provides store) { SchedulesPart(compact = true) }
        }
    }

    @Test
    fun improvementPanel() {
        val store = com.hereliesaz.geministrator.workflow.improvement.InMemoryImprovementStore()
        val now = 20_765L * 86_400_000L
        runBlocking {
            val text = """
                IMPROVEMENT | role-instruction | qa-engineer | Quote the failing command | Always quote the exact command that failed. | Fewer retries for qa-engineer; measure retried steps over the next seven days | Revert removes the added line
                IMPROVEMENT | loop-definition | suite-self-improvement/examine-loop | Start the loop's self-examination from retried steps | Begin from the steps with attempt above one, then the rest. | The loop's findings cite retry counts; measure the share that do | Revert restores the old objective
                IMPROVEMENT | schedule | schedule-suite-self-improvement | Run the cycle twice a week | - | More cycles inside the same weeks; measure cycles per month | Set it back to weekly
            """.trimIndent()
            val (cycle, proposals) = com.hereliesaz.geministrator.workflow.improvement.readCycle("run-cycle-1", text, now)
            store.ingest(cycle, proposals)
            store.merge { st ->
                val role = com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposal(
                    id = "rip-1", roleId = BuiltInRoles.QaEngineer.id, baseInstructions = "Verify carefully.",
                    proposedInstructions = "Verify carefully.\n\nLearned from past work:\n- When a check fails, quote the failing command.",
                    changes = listOf(
                        com.hereliesaz.geministrator.memory.procedural.RoleInstructionChange(
                            "- When a check fails, quote the failing command.",
                            com.hereliesaz.geministrator.memory.procedural.RoleInstructionEvidenceKind.RepeatedFailure, emptyList(),
                        ),
                    ),
                    createdAtEpochMillis = now,
                )
                val withRole = com.hereliesaz.geministrator.workflow.improvement.ProposalMirrors.roleHistory(
                    st, com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalState(proposals = listOf(role)), now,
                )
                val habit = com.hereliesaz.geministrator.memory.procedural.FormedHabit(
                    executorKind = "GitHubAction", operation = "github-action:ci.yml", uses = 5, completedUses = 4, failedUses = 1,
                    verifiedUses = 0, rejectedUses = 0, slots = emptyList(), firstUsedAtEpochMillis = 1L, lastUsedAtEpochMillis = 2L,
                    strength = com.hereliesaz.geministrator.memory.procedural.HabitStrength.Low,
                )
                com.hereliesaz.geministrator.workflow.improvement.ProposalMirrors.habits(withRole, listOf("bank" to habit), now)
            }
            store.decide(proposals[2].id) { it.copy(status = com.hereliesaz.geministrator.workflow.improvement.ProposalStatus.Adopted, decidedAtEpochMillis = now, note = "by hand") }
        }
        shoot("55-improvement", 780, 2600) {
            androidx.compose.runtime.CompositionLocalProvider(LocalImprovementStore provides store) {
                ImprovementPart(onSaveRoleInstructions = { _, _ -> })
            }
        }
    }

    @Test
    fun localAgentsPanel() {
        val host = object : com.hereliesaz.geministrator.providers.cli.CliAgentHost {
            override suspend fun statuses() = com.hereliesaz.geministrator.providers.cli.CliAgentSpecs.all.mapIndexed { i, spec ->
                com.hereliesaz.geministrator.providers.cli.CliAgentStatus(
                    spec = spec, path = if (i < 3) "/usr/local/bin/" + spec.binaries.first() else null, version = if (i < 3) "1.${i}.0" else null,
                    enabled = i == 0, passKeys = false,
                    lastTest = when (i) {
                        0 -> com.hereliesaz.geministrator.providers.cli.CliTestResult(true, "Answered in 3.2 s: OK", 1L, 3200)
                        1 -> com.hereliesaz.geministrator.providers.cli.CliTestResult(false, "Exit 1: unknown option --approval-mode", 1L, 800)
                        else -> null
                    },
                )
            }
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun setPassKeys(id: String, passKeys: Boolean) = Unit
            override suspend fun test(id: String) = com.hereliesaz.geministrator.providers.cli.CliTestResult(true, "OK", 1L, 1)
        }
        shoot("56-local-agents", 780, 2400) {
            androidx.compose.runtime.CompositionLocalProvider(com.hereliesaz.geministrator.providers.cli.LocalCliAgentHost provides host) { LocalAgentsPart() }
        }
    }
}

