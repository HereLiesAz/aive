package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

/**
 * The Hall Monitor's evidence (docs/architecture/SELF_IMPROVEMENT.md §2): counts computed from the run
 * history on this device, for a window and the window before it, so a change can be seen; what the loop
 * adopted since its last cycle and what happened to the numbers after; and the loop as it is now. Pure and
 * deterministic: the same history gives the same text. It is plain text for the run's objective; names and
 * reasons from the history are bounded, and nothing in it is an instruction.
 */
object SwarmEvidence {
    const val DEFAULT_WINDOW_MILLIS = 7L * 24 * 3_600_000
    private const val MAX_ROWS = 12
    private const val MAX_CHARS = 9_000
    private const val MAX_LOOP_CHARS = 3_000

    private data class Tally(
        var steps: Int = 0,
        var completed: Int = 0,
        var failed: Int = 0,
        var escalated: Int = 0,
        var retried: Int = 0,
        var tokens: Long = 0,
        var cost: Double = 0.0,
        var costKnown: Boolean = false,
    ) {
        fun add(task: TaskRun) {
            steps += 1
            when (task.status) {
                TaskRunStatus.Completed -> completed += 1
                TaskRunStatus.Failed -> failed += 1
                TaskRunStatus.Escalated -> escalated += 1
                else -> Unit
            }
            if (task.attempt > 1) retried += 1
            task.usage?.let { u ->
                tokens += u.totalTokens
                u.costUsd?.let { cost += it; costKnown = true }
            }
        }

        val failRate: Double get() = if (steps == 0) 0.0 else (failed + escalated).toDouble() / steps
    }

    /** Runs whose last change fell in `[from, to)`. */
    private fun inWindow(runs: List<WorkflowRun>, from: Long, to: Long) = runs.filter { it.updatedAtEpochMillis in from until to }

    private fun byRole(runs: List<WorkflowRun>): Map<String, Tally> {
        val out = linkedMapOf<String, Tally>()
        runs.forEach { run ->
            run.taskRuns.values.forEach { t ->
                val role = t.assignedRoleId?.value ?: "(no role)"
                out.getOrPut(role) { Tally() }.add(t)
            }
        }
        return out
    }

    private fun byProvider(runs: List<WorkflowRun>): Map<String, Tally> {
        val out = linkedMapOf<String, Tally>()
        runs.forEach { run -> run.taskRuns.values.forEach { t -> out.getOrPut(t.assignedProviderId?.value ?: "(none)") { Tally() }.add(t) } }
        return out
    }

    private fun usd(t: Tally) = if (t.costKnown) "$" + (kotlin.math.round(t.cost * 100) / 100.0) else "no cost reported"

    private fun pct(x: Double) = "${kotlin.math.round(x * 100).toInt()}%"

    private fun row(name: String, now: Tally, before: Tally?, label: (String) -> String): String =
        "- ${label(name)}: ${now.steps} steps, ${now.completed} completed, ${now.failed} failed, ${now.escalated} escalated, ${now.retried} retried, " +
            "${now.tokens} tokens, ${usd(now)}" + (before?.takeIf { it.steps > 0 }?.let {
                "; before: ${it.steps} steps, fail rate ${pct(it.failRate)} (now ${pct(now.failRate)})"
            } ?: "")

    fun of(
        runs: List<WorkflowRun>,
        roles: Collection<RoleDefinition>,
        state: ImprovementState,
        nowEpochMillis: Long,
        loopDescription: String,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): String {
        val names = roles.associate { it.id.value to it.name }
        val roleName: (String) -> String = { id -> names[id]?.let { "$it ($id)" } ?: id }
        val windowStart = nowEpochMillis - windowMillis
        val now = inWindow(runs, windowStart, nowEpochMillis + 1)
        val before = inWindow(runs, windowStart - windowMillis, windowStart)
        val days = windowMillis / 86_400_000

        val body = buildString {
            appendLine("SWARM EVIDENCE: computed from the run history on this device. Counts only; it says nothing about whether the work was good.")
            appendLine("Window: the last $days days (${now.size} runs), compared with the $days days before (${before.size} runs).")
            appendLine()
            appendLine("Runs by outcome: " + WorkflowRunStatus.entries.joinToString(", ") { s -> "${s.name} ${now.count { it.status == s }}" })
            val byWorkflow = now.groupBy { it.workflowDefinitionId.value }.entries.sortedByDescending { it.value.size }.take(MAX_ROWS)
            byWorkflow.forEach { (id, rs) ->
                appendLine("- hive $id: ${rs.size} runs, ${rs.count { it.status == WorkflowRunStatus.Completed }} completed, ${rs.count { it.status == WorkflowRunStatus.Failed }} failed, ${rs.count { it.status == WorkflowRunStatus.Cancelled }} cancelled")
            }
            appendLine()
            appendLine("By role (steps; failed and escalated are the fail rate):")
            val roleBefore = byRole(before)
            byRole(now).entries.sortedByDescending { it.value.steps }.take(MAX_ROWS).forEach { (r, t) -> appendLine(row(r, t, roleBefore[r], roleName)) }
            appendLine()
            appendLine("By provider:")
            val providerBefore = byProvider(before)
            byProvider(now).entries.sortedByDescending { it.value.steps }.take(MAX_ROWS).forEach { (p, t) -> appendLine(row(p, t, providerBefore[p]) { it }) }
            appendLine()
            appendLine("Adopted since the last cycle, and what happened after (a role's fail rate before and after the adoption):")
            val lastCycle = state.cycles.maxOfOrNull { it.atEpochMillis } ?: 0L
            val adopted = state.proposals.filter { it.status == ProposalStatus.Adopted && (it.decidedAtEpochMillis ?: 0L) > lastCycle - windowMillis }
            if (adopted.isEmpty()) appendLine("- Nothing adopted yet.")
            adopted.take(MAX_ROWS).forEach { p ->
                val at = p.decidedAtEpochMillis ?: 0L
                val effect = if (p.kind == ImprovementKind.RoleInstruction) {
                    val pre = Tally().also { t -> runs.filter { it.updatedAtEpochMillis < at }.forEach { r -> r.taskRuns.values.filter { it.assignedRoleId?.value == p.target }.forEach(t::add) } }
                    val post = Tally().also { t -> runs.filter { it.updatedAtEpochMillis >= at }.forEach { r -> r.taskRuns.values.filter { it.assignedRoleId?.value == p.target }.forEach(t::add) } }
                    "; ${p.target} fail rate ${pct(pre.failRate)} over ${pre.steps} steps before, ${pct(post.failRate)} over ${post.steps} steps after"
                } else {
                    ""
                }
                appendLine("- \"${p.summary.take(120)}\" (${p.kind.label}, ${p.target})$effect. It said: ${p.expectedEffect.take(160)}")
            }
            // The other mechanisms' proposals are in the same ledger: what each has noticed, and what the keeper made of it.
            appendLine("Proposals by the mechanism that noticed them (the loop reads the others' as evidence, so it does not repeat them):")
            ImprovementSource.entries.forEach { source ->
                val mine = state.proposals.filter { it.source == source }
                val label = when (source) {
                    ImprovementSource.Loop -> "the loop"
                    ImprovementSource.RoleHistory -> "run-history heuristics (role instructions)"
                    ImprovementSource.Habit -> "habits"
                }
                val judged = mine.filter { it.status == ProposalStatus.Dismissed }.takeLast(3).joinToString("; ") { "\"${it.summary.take(70)}\"" }
                appendLine(
                    "- $label: ${mine.count { it.status == ProposalStatus.Proposed }} waiting, ${mine.count { it.status == ProposalStatus.Adopted }} adopted, " +
                        "${mine.count { it.status == ProposalStatus.Dismissed }} dismissed, ${mine.count { it.status == ProposalStatus.Reverted }} reverted" +
                        (if (judged.isNotEmpty()) "; recently dismissed: $judged" else ""),
                )
            }
            val pending = state.proposals.count { it.status == ProposalStatus.Proposed }
            val dismissed = state.proposals.filter { it.status == ProposalStatus.Dismissed }.takeLast(5)
            appendLine("Proposals still waiting on the keeper: $pending. Recently dismissed (the keeper judged these not worth it): " +
                if (dismissed.isEmpty()) "none." else dismissed.joinToString("; ") { "\"${it.summary.take(80)}\"" })
        }
        // The loop's own section is never the part that is cut: the loop must always see itself.
        val own = "\nTHE LOOP, AS IT IS NOW (examine this too; nothing in it is off limits):\n" + loopDescription.take(MAX_LOOP_CHARS)
        return body.take((MAX_CHARS - own.length).coerceAtLeast(0)) + own
    }
}
