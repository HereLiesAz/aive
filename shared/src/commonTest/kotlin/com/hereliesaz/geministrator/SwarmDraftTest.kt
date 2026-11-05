package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Drafting a swarm from a prompt: the proposal is checked, never trusted, and never launches anything. */
class SwarmDraftTest {
    private val architect = BuiltInRoles.Architect
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(architect, engineer, qa)

    private fun step(id: String, role: String = engineer.id.value, vararg deps: String, approval: Boolean = false, name: String = "Step $id", objective: String = "Do $id") =
        OrchestrationPlanStep(id, name, objective, role, deps.toList(), approval)

    private fun draft(vararg steps: OrchestrationPlanStep, prompt: String = "Ship the release") =
        swarmDraftFromPlan(prompt, OrchestrationPlan(steps.toList()), roles)

    private fun problem(result: SwarmDraftResult) = assertIs<SwarmDraftResult.Problem>(result).problem

    @Test
    fun aGoodPlanBecomesWiredCreatures() {
        val ready = assertIs<SwarmDraftResult.Ready>(
            draft(
                step("Contract", architect.id.value),
                step("Build", engineer.id.value, "Contract"),
                step("Review", qa.id.value, "Build", approval = true),
            ),
        )
        assertEquals(listOf("contract", "build", "review"), ready.tasks.map { it.id.value })
        assertEquals(setOf(TaskDefinitionId("contract")), ready.tasks[1].dependsOn)
        assertEquals(ApprovalPolicy.HumanApproval, ready.tasks[2].approvalPolicy)
        assertEquals(ApprovalPolicy.None, ready.tasks[0].approvalPolicy)
        assertEquals("Ship the release", ready.suggestedName)
    }

    @Test
    fun aDraftIsAgentWorkOnly() {
        val ready = assertIs<SwarmDraftResult.Ready>(draft(step("a"), step("b", architect.id.value, "a")))
        // Never a repository change, a script or an action: those are the keeper's to bring in.
        assertTrue(ready.tasks.all { it.executor is TaskExecutor.RoleAgent })
    }

    @Test
    fun idsAreMadeSafeAndUnique() {
        val ready = assertIs<SwarmDraftResult.Ready>(draft(step("Step One!"), step("step one?", engineer.id.value, "Step One!")))
        assertEquals(listOf("step-one", "step-one-2"), ready.tasks.map { it.id.value })
        assertEquals(setOf(TaskDefinitionId("step-one")), ready.tasks[1].dependsOn)
        assertEquals("creature", assertIs<SwarmDraftResult.Ready>(draft(step("???"))).tasks.single().id.value)
    }

    @Test
    fun blankNamesAndObjectivesFallBackToTheRole() {
        val task = assertIs<SwarmDraftResult.Ready>(draft(step("a", architect.id.value, name = " ", objective = " "))).tasks.single()
        assertEquals(architect.name, task.name)
        assertTrue(task.objective.isNotBlank())
    }

    @Test
    fun malformedPlansAreRefusedInPlainTerms() {
        assertEquals(SwarmDraftProblem.Empty, problem(draft()))
        assertEquals(SwarmDraftProblem.UnknownRole, problem(draft(step("a", "no-such-role"))))
        assertEquals(SwarmDraftProblem.Unreadable, problem(draft(step("a", engineer.id.value, "ghost"))))
        assertEquals(SwarmDraftProblem.Unreadable, problem(draft(step("a"), step("a"))))
        assertEquals(SwarmDraftProblem.Unreadable, problem(draft(step(" "))))
        assertEquals(SwarmDraftProblem.Cycle, problem(draft(step("a", engineer.id.value, "a"))))
        assertEquals(SwarmDraftProblem.Cycle, problem(draft(step("a", engineer.id.value, "b"), step("b", engineer.id.value, "a"))))
        assertEquals(SwarmDraftProblem.NoPrompt, problem(draft(step("a"), prompt = "   ")))
        assertEquals(SwarmDraftProblem.NoRoles, problem(swarmDraftFromPlan("x", OrchestrationPlan(listOf(step("a"))), emptyList())))
    }

    @Test
    fun theCreatureCountIsCapped() {
        val atCap = (1..SWARM_DRAFT_MAX_CREATURES).map { step("s$it") }.toTypedArray()
        assertIs<SwarmDraftResult.Ready>(draft(*atCap))
        val over = (1..SWARM_DRAFT_MAX_CREATURES + 1).map { step("s$it") }.toTypedArray()
        assertEquals(SwarmDraftProblem.TooMany, problem(draft(*over)))
    }

    @Test
    fun onlySwitchedOnAgentRolesAreOffered() {
        val script = engineer.copy(executionSource = RoleExecutionSource.Script(com.hereliesaz.geministrator.domain.ScriptLanguage.JavaScript, "return 1;", com.hereliesaz.geministrator.domain.ScriptRunner.LocalSandbox))
        val off = qa.copy(enabled = false)
        val offered = swarmDraftRoles(listOf(architect, script, off)).map { it.id }
        assertEquals(listOf(architect.id), offered)
        val packet = swarmDraftPacket("  Ship it  ", listOf(architect, script, off))
        assertEquals("Ship it", packet.objective)
        assertEquals(listOf(architect.id.value), packet.availableAgents.map { it.id })
        assertTrue("$SWARM_DRAFT_MAX_CREATURES" in packet.instruction)
        // A role the planner was never offered cannot be drafted.
        assertEquals(
            SwarmDraftProblem.UnknownRole,
            problem(swarmDraftFromPlan("x", OrchestrationPlan(listOf(step("a", engineer.id.value))), listOf(architect, script))),
        )
    }

    @Test
    fun theNameIsTheFirstLineCutAtAWord() {
        assertEquals("Triage the inbox", swarmDraftName("Triage the inbox.\nThen answer the urgent ones"))
        val long = swarmDraftName("Review every pull request that touches the payments module this week")
        assertTrue(long.length <= 40 && !long.endsWith(" "))
        assertEquals("", swarmDraftName("  "))
    }

    @Test
    fun providerFailuresKeepTheirMessageForTheFriendlyText() {
        listOf(
            "HTTP 402 Payment Required" to ErrorKind.OutOfCredit,
            "HTTP 429 Too Many Requests" to ErrorKind.RateLimited,
            "Unable to resolve host api.example.com" to ErrorKind.Offline,
            "HTTP 401 Unauthorized" to ErrorKind.KeyRejected,
        ).forEach { (raw, kind) ->
            val failed = assertIs<SwarmDraftResult.ProviderFailed>(swarmDraftFailure(IllegalStateException(raw)), raw)
            assertEquals(kind, classifyError(failed.raw))
        }
        // A provider's failure is not "unreadable" even when it arrives as an IllegalArgumentException.
        assertIs<SwarmDraftResult.ProviderFailed>(swarmDraftFailure(IllegalArgumentException("429 rate limit")))
    }

    @Test
    fun unreadableAnswersNeverCrash() {
        assertEquals(SwarmDraftProblem.Unreadable, problem(swarmDraftFailure(SerializationException("Unexpected JSON token"))))
        assertEquals(SwarmDraftProblem.Unreadable, problem(swarmDraftFailure(IllegalArgumentException("Planner returned an empty plan"))))
        assertIs<SwarmDraftResult.Unavailable>(swarmDraftFailure(IllegalStateException("The orchestration model runtime is unavailable on this platform")))
        assertIs<SwarmDraftResult.ProviderFailed>(swarmDraftFailure(RuntimeException()))
    }

    // ---- Redraft ----

    private fun task(id: String) = TaskDefinition(
        TaskDefinitionId(id), id, "$id objective", engineer.id, executor = TaskExecutor.RoleAgent(engineer.id),
    )

    private val mine = WorkflowDefinition(
        id = WorkflowDefinitionId("hive-1"),
        name = "Release train",
        description = "Mine",
        tasks = listOf(task("x"), task("y")),
    )

    private val proposal = assertIs<SwarmDraftResult.Ready>(draft(step("a"), step("b", architect.id.value, "a")))

    @Test
    fun aRedraftKeepsTheHivesIdentityAndChangesOnlyItsCreatures() {
        val redrafted = redraftedHive(mine, proposal)
        assertEquals(mine.id, redrafted.id)
        assertEquals(mine.name, redrafted.name)
        assertEquals(mine.description, redrafted.description)
        assertEquals(listOf("a", "b"), redrafted.tasks.map { it.id.value })
        assertEquals(2, redraftReplaces(mine))
        assertEquals(0, redraftReplaces(mine.copy(tasks = emptyList())))
    }

    @Test
    fun oneUndoStepRestoresThePreviousLayoutAndRedoBringsTheDraftBack() {
        val tide = TerrariumTide(present = mine).record(redraftedHive(mine, proposal))
        assertTrue(tide.canUndo)
        val back = tide.undo()
        assertEquals(mine, back.present)
        assertFalse(back.canUndo, "a redraft is exactly one step")
        assertEquals(redraftedHive(mine, proposal), back.redo().present)
    }

    @Test
    fun aRedraftAfterEditsKeepsTheEditsOnTheTide() {
        val edited = (addTerrariumCreature(mine, TerrariumNewcomer.FromRole(qa)) as TerrariumDependencyEditResult.Applied).definition
        val tide = TerrariumTide(present = mine).record(edited).record(redraftedHive(edited, proposal))
        assertEquals(edited, tide.undo().present)
        assertEquals(mine, tide.undo().undo().present)
    }

    @Test
    fun aDraftOnANewHiveIsOneStepFromEmpty() {
        val empty = mine.copy(tasks = emptyList())
        val tide = TerrariumTide(present = empty).record(redraftedHive(empty, proposal))
        assertTrue(opensAsNewHive(tide.undo().present))
    }
}
