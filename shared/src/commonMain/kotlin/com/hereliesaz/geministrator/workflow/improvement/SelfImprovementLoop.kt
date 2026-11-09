package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.workflow.HallMonitorWorkflowFragmentFactory

/**
 * The swarm's self-improvement loop (docs/architecture/SELF_IMPROVEMENT.md): a read-only hive that
 * has the Hall Monitor observe the swarm from its own run history, examines **the loop itself** in
 * parallel, and ends in proposals that only the keeper can adopt. It has no approval step, no
 * repository operation and no code-changing step, so it can run on a schedule and can never hold
 * anything up, itself included.
 *
 * Its own parts (this hive, the Hall Monitor's role, its schedule, its evidence) are improvable through
 * exactly the same path as everything else; [LoopIntegrity] and `SelfImprovementClauseTest` keep that true.
 */
object SelfImprovementLoop {
    const val HIVE_ID = "suite-self-improvement"
    const val SCHEDULE_ID = "schedule-suite-self-improvement"

    /** The step whose text is the cycle's proposals. */
    const val FINAL_STEP = "refine"

    /** The proposal-line grammar, repeated in the steps that write it. */
    const val GRAMMAR = "IMPROVEMENT | <kind> | <target> | <summary> | <the exact new text, or -> | <expected effect, and how it will be measured> | <how to undo it>"

    /** How a field that needs a pipe of its own is written, so it cannot move the fields after it. */
    const val ESCAPING = "Fields are separated by space, pipe, space, and a line has exactly seven. If a field's own text needs a pipe, write it as \\| ."

    /** What a proposal may be about. */
    const val KINDS = "role-instruction (target: a role id), hive-step (target: <hive id>/<task id>), schedule, loop-definition, evidence-rule, other"

    /** The kinds that make a proposal about the loop itself (a proposal also is, by its target; see [isOwn]). */
    val metaKinds: List<String> = listOf(ImprovementKind.LoopDefinition.label, ImprovementKind.EvidenceRule.label)

    /** The ids of the loop's own parts: a proposal aimed at one is about the loop. */
    val ownTargets: Set<String> = setOf(HIVE_ID, SCHEDULE_ID, HallMonitorRole.id.value)

    fun isOwn(target: String): Boolean =
        target.substringBefore('/') in ownTargets || target in ownTargets

    private val architect = BuiltInRoles.Architect
    private val adversary = BuiltInRoles.AdversarialReviewer
    private val product = BuiltInRoles.ProductManager

    fun definition(): WorkflowDefinition {
        val fragment = HallMonitorWorkflowFragmentFactory.create("loop")
        val evidence = "The swarm's evidence is in this run's objective: counts from the run history on this device, what was adopted since the last cycle, and the loop as it is now."
        val observe = fragment.tasks.first { it.id.value == "loop-report" }.let { it.copy(id = TaskDefinitionId("observe"), name = "Observe the swarm", objective = it.objective + "\n\n" + evidence) }
        val challengeReport = fragment.tasks.first { it.id.value == "loop-antagonist-review" }
            .copy(
                id = TaskDefinitionId("challenge-report"), name = "Challenge the report", requiredRoleAuthority = RoleAuthority.ReviewCode,
                dependsOn = setOf(TaskDefinitionId("observe")),
            )
        val examine = step(
            "examine-loop", "Examine the loop itself", architect, RoleAuthority.ProposePlan,
            "Examine this self-improvement loop, as given under 'The loop, as it is now' in the objective: its steps, the evidence it is " +
                "fed, the Hall Monitor's instructions, its schedule. Say what it misses, what it measures badly, what is wasted, and what " +
                "you would change, with evidence from the run history. You work independently of the Hall Monitor's report. Nothing " +
                "about the loop is off limits, and a finding that the loop is fine needs evidence too.",
        )
        val challengeLoop = step(
            "challenge-loop", "Challenge the examination", adversary, RoleAuthority.RejectPlan,
            "Attack the examination of the loop. Is it self-serving, too timid, or blind to something? Above all, flag any suggestion that " +
                "would make the loop harder to change, exempt part of it from review, lock it, or take a decision from the keeper.",
            after = listOf("examine-loop"),
        )
        val propose = step(
            "propose", "Propose improvements", product, RoleAuthority.ProposePlan,
            "From the Hall Monitor's report (as challenged) and the examination of the loop (as challenged), write proposals, one per line, " +
                "exactly in this form:\n$GRAMMAR\n$ESCAPING\nkind is one of: $KINDS. A proposal changes nothing; the keeper decides each. Every " +
                "expected effect must say how it would be measured from run history. You MUST include at least one proposal about the loop " +
                "itself (kind loop-definition or evidence-rule, or a target that is the loop's own hive, role or schedule), or, only if the " +
                "evidence shows the loop needs no change, one line 'META-NO-CHANGE | <the evidence>'. No other text.",
            after = listOf("challenge-report", "challenge-loop"),
        )
        val refine = step(
            FINAL_STEP, "Refine the proposals", adversary, RoleAuthority.RejectPlan,
            "Attack each proposal: is the evidence real, is the new text exact, is the undo real, would it make the swarm or the loop " +
                "worse in a way the evidence does not show? Remove what does not survive, correct the lines that do, and keep the same " +
                "line form:\n$GRAMMAR\n$ESCAPING\nKeep the loop's own proposal (or its META-NO-CHANGE) unless it is unsupported; do not remove one only " +
                "because it is about the loop. Output only the lines.",
            after = listOf("propose"),
        )
        return WorkflowDefinition(
            id = WorkflowDefinitionId(HIVE_ID),
            name = "Self-improvement cycle",
            description = "The swarm examines itself from its own run history, and examines this loop too, and ends in proposals only you can adopt. Reads and reports; changes nothing.",
            tasks = listOf(observe, challengeReport, examine, challengeLoop, propose, refine),
            testDesignPolicy = TestDesignPolicy.None,
        )
    }

    /** The loop's step text, as the evidence shows it to the loop: what it would examine. */
    fun describe(definition: WorkflowDefinition = definition(), hallMonitor: RoleDefinition = HallMonitorRole.definition): String = buildString {
        appendLine("Hive ${definition.name} (${definition.id.value}):")
        definition.tasks.forEach { appendLine("- ${it.name} [${it.roleId?.value}]: ${it.objective.replace(Regex("\\s+"), " ").take(360)}") }
        appendLine("Hall Monitor instructions (start): ${hallMonitor.instructions.replace(Regex("\\s+"), " ").take(700)}")
    }

    private fun step(id: String, name: String, role: RoleDefinition, authority: RoleAuthority?, objective: String, after: List<String> = emptyList()) =
        TaskDefinition(
            id = TaskDefinitionId(id), name = name, objective = objective, roleId = role.id, requiredRoleAuthority = authority,
            dependsOn = after.map(::TaskDefinitionId).toSet(), approvalPolicy = ApprovalPolicy.None,
            // Never a person's decision: a step that cannot finish fails the run, so the loop cannot hold a scheduled start or a remote launch up.
            escalationPolicy = EscalationPolicy.FailWorkflow, executor = TaskExecutor.RoleAgent(role.id),
        )
}

/**
 * What must stay true of any hive that claims to be the loop (the amendment clause, SELF_IMPROVEMENT.md §4).
 * The keeper may edit the loop however they like; this is what the **system** refuses to ship, to adopt a proposal
 * into, or to start on a schedule as the loop: one that exempts itself from review, can block anything, or can
 * change anything by itself.
 *
 * The checks are on structure the engine and the parser act on (what a step runs as, how it fails, what it waits
 * for, what role it uses, what the proposal parser is told to read), never on a word appearing in a step's text,
 * except for the two line forms [ImprovementParser] recognises: a step is not told to write something the parser
 * cannot read. What a model does with its instructions is not checkable here; the obligation to examine the loop
 * is checked where the result is read ([CycleRecord.examinedLoop]).
 */
object LoopIntegrity {
    /** What a role used by the loop must not be able to do: write to a repository, run commands, open a pull request, author tests. */
    private val writeCapabilities = setOf(AgentCapability.RepositoryWrite, AgentCapability.ShellExecution, AgentCapability.PullRequestCreation, AgentCapability.TestAuthoring)

    /** Authorities that change or release something. */
    private val changingAuthorities = setOf(RoleAuthority.Implement, RoleAuthority.AuthorTests, RoleAuthority.ApproveIntegration, RoleAuthority.ApproveRelease)

    private const val NO_APPROVAL = "The loop must not hold anything up: it has no approval step."
    private const val READS_ONLY = "The loop only reads and reports: no repository, script, deployment, action, test, external-service, nested-workflow or distributed step."

    /** The ways [definition] is not the loop. [roles] (the installed roles) lets the roles its steps use be checked too; empty skips that. */
    fun violations(definition: WorkflowDefinition, roles: Collection<RoleDefinition> = emptyList()): List<String> {
        val problems = linkedSetOf<String>()
        val tasks = definition.tasks
        val byId = tasks.associateBy { it.id.value }
        val examine = byId["examine-loop"]
        val challenge = byId["challenge-loop"]
        val propose = byId["propose"]
        val final = byId[SelfImprovementLoop.FINAL_STEP]

        if (examine == null || examine.objective.isBlank()) {
            problems += "The loop must examine itself (a step 'examine-loop' with its own objective)."
        } else {
            val author = examine.roleId
            if (author == null || author == HallMonitorRole.id || ancestors(tasks, examine.id).any { byId[it.value]?.roleId == HallMonitorRole.id }) {
                problems += "The loop must examine itself independently of the Hall Monitor: 'examine-loop' is not the Hall Monitor's step and does not wait on its report."
            }
        }
        if (challenge == null || challenge.dependsOn.none { it.value == "examine-loop" }) {
            problems += "The loop's examination of itself must be challenged ('challenge-loop' after 'examine-loop')."
        } else if (examine != null && challenge.roleId != null && challenge.roleId == examine.roleId) {
            problems += "The loop's examination of itself must be challenged by a different role than the one that made it."
        }
        if (propose == null || !carries(propose, ImprovementParser.NO_CHANGE_PREFIX) || !carries(propose, ImprovementParser.LINE_PREFIX) || !SelfImprovementLoop.metaKinds.any { it in propose.objective }) {
            problems += "'propose' must require a proposal about the loop, or a META-NO-CHANGE with evidence, in the line forms the app reads."
        }
        if (propose != null && examine != null && !reaches(tasks, propose.id, examine.id)) problems += "The proposals must follow from the examination of the loop."
        if (propose != null && challenge != null && !reaches(tasks, propose.id, challenge.id)) problems += "The proposals must follow from the challenge of the examination."
        if (final == null) {
            problems += "The loop must end in a step that writes the proposals ('${SelfImprovementLoop.FINAL_STEP}')."
        } else {
            if (!carries(final, ImprovementParser.LINE_PREFIX)) problems += "The last step must write the proposal lines in the form the app reads."
            if (tasks.any { it.id != final.id && !reaches(tasks, final.id, it.id) } || tasks.any { t -> t.dependsOn.contains(final.id) }) {
                problems += "The last step must follow from every other step and nothing may follow it, so nothing the loop does is left out of what it reports."
            }
        }

        for (task in tasks) {
            if (task.condition != TaskCondition.Always) problems += "No step of the loop can be skipped: '${task.id.value}' only runs on a condition."
            if (task.holdOutput) problems += "No step of the loop can withhold its output: '${task.id.value}' holds it."
            if (task.escalationPolicy != EscalationPolicy.FailWorkflow) {
                problems += "The loop must never wait on a person or hand a step to another role: '${task.id.value}' must fail the run (FailWorkflow), not escalate."
            }
            if (task.approvalPolicy != ApprovalPolicy.None) problems += NO_APPROVAL
            when (task.executor) {
                null, is TaskExecutor.RoleAgent -> Unit
                is TaskExecutor.HumanApproval -> problems += NO_APPROVAL
                else -> problems += READS_ONLY
            }
            if (task.requiredRoleAuthority in changingAuthorities) problems += "The loop changes nothing: no implementing, test-authoring, integration or release step."
            (task.providerConstraints as? ProviderConstraints.RequireCapabilities)?.let { needs ->
                if (needs.capabilities.any { it in writeCapabilities }) problems += "The loop changes nothing: '${task.id.value}' asks for a provider that can write, run commands or open a pull request."
            }
            val roleId = (task.executor as? TaskExecutor.RoleAgent)?.roleId ?: task.roleId
            roles.firstOrNull { it.id == roleId }?.let { role ->
                if (role.capabilitiesRequired.any { it in writeCapabilities } || role.authorities.any { it in changingAuthorities } || role.executionSource != RoleExecutionSource.Agent) {
                    problems += "The loop changes nothing: the role '${role.id.value}' used by '${task.id.value}' can write, run commands or release, or is not a plain agent."
                }
            }
        }
        if (definition.testDesignPolicy != TestDesignPolicy.None) problems += "The loop changes nothing: it adds no test-design steps."
        return problems.toList()
    }

    /**
     * Why a schedule must not start [definition] as the loop now, or null when it may. An unattended run keeps the
     * promises the loop makes (it reads and reports, and never waits on anyone), so a hive that no longer keeps them
     * is not started by a schedule until the keeper fixes it; the keeper can still run it by hand.
     */
    fun startRefusal(definition: WorkflowDefinition, roles: Collection<RoleDefinition>): String? =
        violations(definition, roles).takeIf { it.isNotEmpty() }
            ?.let { "The self-improvement cycle on this device no longer meets the rules it runs unattended under, so it was not started: " + it.joinToString(" ") }

    /** Whether the step's own text tells the writer the line form that starts with [prefix] (the form [ImprovementParser] reads). */
    private fun carries(task: TaskDefinition, prefix: String): Boolean = task.objective.contains("$prefix |")

    private fun ancestors(tasks: List<TaskDefinition>, id: TaskDefinitionId): Set<TaskDefinitionId> {
        val byId = tasks.associateBy { it.id }
        val seen = linkedSetOf<TaskDefinitionId>()
        fun walk(current: TaskDefinitionId) {
            byId[current]?.dependsOn.orEmpty().forEach { if (seen.add(it)) walk(it) }
        }
        walk(id)
        return seen
    }

    private fun reaches(tasks: List<TaskDefinition>, from: TaskDefinitionId, to: TaskDefinitionId): Boolean {
        val byId = tasks.associateBy { it.id }
        val seen = mutableSetOf<TaskDefinitionId>()
        fun walk(id: TaskDefinitionId): Boolean {
            if (id == to) return true
            if (!seen.add(id)) return false
            return byId[id]?.dependsOn.orEmpty().any(::walk)
        }
        return walk(from)
    }
}
