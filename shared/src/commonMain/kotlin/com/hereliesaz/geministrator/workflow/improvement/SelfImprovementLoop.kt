package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
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

    /** What a proposal may be about. */
    const val KINDS = "role-instruction (target: a role id), hive-step (target: <hive id>/<task id>), schedule, loop-definition, evidence-rule, other"

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
                "exactly in this form:\n$GRAMMAR\nkind is one of: $KINDS. A proposal changes nothing; the keeper decides each. Every " +
                "expected effect must say how it would be measured from run history. You MUST include at least one proposal about the loop " +
                "itself (kind loop-definition or evidence-rule, or a target that is the loop's own hive, role or schedule), or, only if the " +
                "evidence shows the loop needs no change, one line 'META-NO-CHANGE | <the evidence>'. No other text.",
            after = listOf("challenge-report", "challenge-loop"),
        )
        val refine = step(
            FINAL_STEP, "Refine the proposals", adversary, RoleAuthority.RejectPlan,
            "Attack each proposal: is the evidence real, is the new text exact, is the undo real, would it make the swarm or the loop " +
                "worse in a way the evidence does not show? Remove what does not survive, correct the lines that do, and keep the same " +
                "line form:\n$GRAMMAR\nKeep the loop's own proposal (or its META-NO-CHANGE) unless it is unsupported; do not remove one only " +
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
            escalationPolicy = EscalationPolicy.RequireHumanDecision, executor = TaskExecutor.RoleAgent(role.id),
        )
}

/**
 * What must stay true of any hive that claims to be the loop (the amendment clause, SELF_IMPROVEMENT.md §4).
 * The keeper may edit the loop however they like; this is what the **system** refuses to ship or accept as
 * the loop: one that exempts itself from review, can block anything, or can change anything by itself.
 */
object LoopIntegrity {
    fun violations(definition: WorkflowDefinition): List<String> {
        val problems = mutableListOf<String>()
        val tasks = definition.tasks
        fun byId(id: String) = tasks.firstOrNull { it.id.value == id }
        val examine = byId("examine-loop")
        if (examine == null || !examine.objective.contains("loop", ignoreCase = true)) problems += "The loop must examine itself (a step 'examine-loop' about the loop)."
        val challenge = byId("challenge-loop")
        if (challenge == null || challenge.dependsOn.none { it.value == "examine-loop" }) problems += "The loop's examination of itself must be challenged ('challenge-loop' after 'examine-loop')."
        val propose = byId("propose")
        if (propose == null || !propose.objective.contains("META-NO-CHANGE") || !propose.objective.contains("itself")) {
            problems += "'propose' must require a proposal about the loop, or a META-NO-CHANGE with evidence."
        }
        if (propose != null && examine != null && !reaches(tasks, propose.id, examine.id)) problems += "The proposals must follow from the examination of the loop."
        if (tasks.any { it.executor is TaskExecutor.HumanApproval || it.approvalPolicy != ApprovalPolicy.None }) problems += "The loop must not hold anything up: it has no approval step."
        if (tasks.any { it.executor is TaskExecutor.RepositoryOperation || it.executor is TaskExecutor.Script || it.executor is TaskExecutor.Deployment || it.executor is TaskExecutor.GitHubAction }) {
            problems += "The loop only reads and reports: no repository, script, deployment or action step."
        }
        if (tasks.any { it.requiredRoleAuthority == RoleAuthority.Implement || it.requiredRoleAuthority == RoleAuthority.AuthorTests }) problems += "The loop changes nothing: no implementing or test-authoring step."
        if (byId(SelfImprovementLoop.FINAL_STEP) == null) problems += "The loop must end in a step that writes the proposals ('${SelfImprovementLoop.FINAL_STEP}')."
        return problems
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
