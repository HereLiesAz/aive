package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.decodeHallMonitorReportPayload
import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorReport
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.RevisionAim
import com.hereliesaz.geministrator.template.RevisionDirector
import com.hereliesaz.geministrator.template.RevisionIntent
import com.hereliesaz.geministrator.template.RevisionMode
import com.hereliesaz.geministrator.template.TemplateCatalog
import com.hereliesaz.geministrator.template.TemplateKind
import com.hereliesaz.geministrator.template.TemplateRevisionRules
import com.hereliesaz.geministrator.template.TemplateRevisionDraft
import com.hereliesaz.geministrator.template.TemplateTarget
import com.hereliesaz.geministrator.workflow.OUTPUT_CONTRACT_VIOLATION
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import kotlinx.serialization.json.Json

/*
 * Template findings (docs/Step-Templates.md). The Nanny names a template as a finding's subject, `template:<kind>:<id>@<version>`
 * (kind is step or role), and offers a Rewrite solution (a quick change) or a Restructure solution (a new generation)
 * whose target is the proposed wording. Her words are model text and untrusted; the numbers, the aim and the
 * thresholds below come from the run's own record, counted here, never read from her text.
 */

/** Plain counts of one template's steps (or one role's) in one run. */
data class TemplateTally(
    val steps: Int,
    val completed: Int,
    val failed: Int,
    val rejectedOutputs: Int,
    val retried: Int,
    val tokens: Long,
)

/** The tally for [target] in [run]: steps made from that template (any version), or steps a role was assigned. */
fun tallyFor(target: TemplateTarget, definition: WorkflowDefinition, run: WorkflowRun): TemplateTally {
    var steps = 0; var completed = 0; var failed = 0; var rejected = 0; var retried = 0; var tokens = 0L
    for (task in definition.tasks) {
        val taskRun = run.taskRuns[task.id] ?: continue
        val matches = when (target.kind) {
            TemplateKind.Step -> task.contract?.template?.id == target.id
            TemplateKind.Role -> (taskRun.assignedRoleId ?: task.roleId)?.value == target.id
        }
        if (!matches) continue
        steps++
        if (taskRun.status == TaskRunStatus.Completed) completed++
        if (taskRun.status == TaskRunStatus.Failed || taskRun.status == TaskRunStatus.Escalated) failed++
        if (taskRun.blockingReason?.code == OUTPUT_CONTRACT_VIOLATION) rejected++
        if (taskRun.attempt > 1) retried++
        tokens += taskRun.usage?.totalTokens ?: 0L
    }
    return TemplateTally(steps, completed, failed, rejected, retried, tokens)
}

/** The aim the evidence points at, decided by the counts: rejected outputs first, then retries, then cost. */
fun aimFor(tally: TemplateTally): RevisionAim = when {
    tally.rejectedOutputs > 0 -> RevisionAim.FewerRejectedOutputs
    tally.retried > 0 -> RevisionAim.FewerRetries
    else -> RevisionAim.LowerCost
}

/** The template a finding is about, or null when its subject names none. */
fun templateTargetOf(finding: NannyFinding): TemplateTarget? =
    if (finding.subject.startsWith("template:")) TemplateTarget.parse(finding.subject.removePrefix("template:").trim()) else null

private const val WORDING_CHARS = 4_000

/**
 * The ledger row (source Nanny, kind task-template) for a template finding of a finished run, or null when the finding
 * is not about a template or offers no Rewrite or Restructure solution. The draft is carried as the row's text and
 * checked again, on the data, when the keeper adopts it.
 */
fun templateProposalFor(
    finding: NannyFinding,
    report: HallMonitorReport,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    now: Long,
): ImprovementProposal? {
    val target = templateTargetOf(finding) ?: return null
    val reportFinding = report.findings.firstOrNull { it.id == finding.id } ?: return null
    val solution = reportFinding.solutions.firstOrNull { it.action == HallMonitorAction.Rewrite || it.action == HallMonitorAction.Restructure }
        ?: return null
    val genetic = solution.action == HallMonitorAction.Restructure
    val tally = tallyFor(target, definition, run)
    val aim = aimFor(tally)
    val intent = RevisionIntent(
        aim = aim,
        sentence = queenText(finding.observation, 200),
        hypothesis = "Over ${tally.steps} steps: ${tally.rejectedOutputs} rejected outputs, ${tally.retried} retried, ${tally.failed} failed.",
        measure = "${aim.label}, judged over the next ${if (genetic) 10 else 3} steps of this template.",
        directedBy = RevisionDirector.Nanny,
    )
    val draft = TemplateRevisionDraft(
        target = target.key,
        wording = solution.target.take(WORDING_CHARS),
        mode = if (genetic) RevisionMode.Genetic else RevisionMode.Memetic,
        intent = intent,
        evidenceSteps = tally.steps,
    )
    return ImprovementProposal(
        id = nannyCarriedId(finding),
        cycleRunId = run.id.value,
        kind = ImprovementKind.TaskTemplate,
        target = target.key,
        summary = queenText("Nanny: " + finding.observation, 200),
        newText = Json.encodeToString(TemplateRevisionDraft.serializer(), draft),
        expectedEffect = aim.label,
        undo = "Revert puts back the version it came from.",
        meta = false,
        createdAtEpochMillis = now,
        source = ImprovementSource.Nanny,
        sourceRef = definition.id.value,
        evidence = queenText(
            "${tally.steps} steps, ${tally.completed} completed, ${tally.failed} failed, ${tally.rejectedOutputs} rejected outputs, " +
                "${tally.retried} retried, ${tally.tokens} tokens.",
            600,
        ),
    )
}

private fun newestReport(run: WorkflowRun): HallMonitorReport? =
    latestReport(run)?.textContent?.let { runCatching { decodeHallMonitorReportPayload(it) }.getOrNull() }

/**
 * The Nanny's mid-run finding about a template, as the queen decides it. She puts it before the keeper only when the
 * change would be accepted as it stands: the aim and evidence are counted from this run, the thresholds (3 steps for a
 * quick change, 10 for a new generation) are met, no locked field is touched and no limit is loosened. Anything else is
 * not offered now and is carried to the next swarm as before ([nannyCarryForward]). Pure; no model. The check here is
 * against the built-in versions; adopting it checks again against the lineage the keeper has.
 */
internal fun nannyTemplateCandidates(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition> = emptyList(),
): List<QueenCandidate> {
    val report = newestReport(run) ?: return emptyList()
    val catalog = TemplateCatalog(if (roles.isEmpty()) BuiltInTemplates.bases() else BuiltInTemplates.bases(roles))
    return nannyFindings(definition, run).mapNotNull { finding ->
        val target = templateTargetOf(finding) ?: return@mapNotNull null
        val proposal = templateProposalFor(finding, report, definition, run, 0L) ?: return@mapNotNull null
        val draft = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(TemplateRevisionDraft.serializer(), proposal.newText)
        } catch (_: Exception) {
            return@mapNotNull null
        }
        val base = catalog.at(target.kind, target.id, target.version) ?: return@mapNotNull null
        if (base.version != target.version) return@mapNotNull null
        val check = TemplateRevisionRules.check(base, draft) as? TemplateRevisionRules.Check.Accepted ?: return@mapNotNull null
        if (check.loosens.isNotEmpty()) return@mapNotNull null
        QueenCandidate(
            QueenAction.TemplateChange, finding.id, QueenVoice.SuggestNannyTemplate,
            listOf(queenSlot(target.id), draft.evidenceSteps.toString()),
            outside = false,
        )
    }
}

/**
 * The ledger row (source Nanny) for the template finding [findingId] of the newest report, made now instead of at the end
 * of the swarm; null when the finding or the report is gone. Its id is the one [nannyCarryForward] uses, so it is never twice.
 */
internal fun nannyTemplateProposal(definition: WorkflowDefinition, run: WorkflowRun, findingId: String, now: Long): ImprovementProposal? {
    val report = newestReport(run) ?: return null
    val finding = nannyFindings(definition, run).firstOrNull { it.id == findingId } ?: return null
    return templateProposalFor(finding, report, definition, run, now)
}
