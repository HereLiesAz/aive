package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.queen.Dispute
import com.hereliesaz.geministrator.queen.InvestigationRequest
import com.hereliesaz.geministrator.queen.disputeTestDefinition
import com.hereliesaz.geministrator.research.ResearchInvestigation
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import com.hereliesaz.geministrator.queen.DisputeOutcome
import com.hereliesaz.geministrator.queen.DisputeResolution
import com.hereliesaz.geministrator.queen.Disputant
import com.hereliesaz.geministrator.queen.Escalation
import com.hereliesaz.geministrator.queen.EscalationReason
import com.hereliesaz.geministrator.queen.InvestigationKind
import com.hereliesaz.geministrator.queen.InvestigationLauncher
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.queen.withFindings
import com.hereliesaz.geministrator.queen.disputeNotes
import com.hereliesaz.geministrator.queen.settleWithAgreement

/**
 * The one place a dispute between two creatures is raised for the queen to settle. Called today from the cycle (see [ApplicationRuntime], the
 * Nanny and the Antagonist); any other pair of creatures goes through here too. No ruling, research or test starts until both [parties] agree to her stated approach (see
 * [settleWithAgreement]); what she says about it, or about handing it to the keeper, is kept for [runId] and shown in Messages.
 * An agreed investigation is handed to [launcher] (see [DisputeInvestigator]); with none, or when it cannot start one, the
 * keeper has it. A research that returns findings ([findingsOf]) makes them listed facts for the one ruling that follows;
 * a test runs as its own swarm, which the keeper can open and stop. [mayCall] is where the keeper's spend caps are checked before
 * a model call; the models should already be metered.
 */
internal suspend fun ApplicationRuntime.raiseDispute(
    runId: String,
    dispute: Dispute,
    parties: List<Disputant>,
    queenModel: QueenModel?,
    voices: Map<String, QueenModel>,
    launcher: InvestigationLauncher? = null,
    /** True when this dispute already had its one investigation: she may not commission another. */
    investigated: Boolean = false,
    /** The findings a research left under the id [launcher] returned; they become listed facts for one more ruling. */
    findingsOf: (String) -> List<String> = { emptyList() },
    mayCall: () -> Boolean = { true },
    /** Reads a started test swarm's results back as listed facts (see [readBackTestReport]); null when they cannot be read. */
    testFacts: (suspend (String) -> List<String>?)? = null,
): DisputeResolution {
    var resolution = settleWithAgreement(dispute, parties, queenModel, voices, allowInvestigation = !investigated, mayCall = mayCall)
    var investigationRunId: String? = null
    val agreed = resolution as? DisputeResolution.Agreed
    val request = (agreed?.outcome as? DisputeOutcome.Investigate)?.request
    if (agreed != null && request != null) {
        investigationRunId = try {
            launcher?.launch(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (investigationRunId == null) {
            resolution = DisputeResolution.Escalated(Escalation(EscalationReason.CannotInvestigate, agreed.approach, emptyList(), request.missingFact))
        }
    }
    rememberDisputeNotes(runId, disputeNotes(runId, dispute.question, parties, resolution, investigationRunId))
    val found = if (request?.kind == InvestigationKind.Research && investigationRunId != null) findingsOf(investigationRunId) else emptyList()
    if (found.isNotEmpty()) {
        // One investigation per dispute: the second pass may not commission another, and both disputants agree again.
        resolution = settleWithAgreement(dispute.withFindings(found), parties, queenModel, voices, allowInvestigation = false, mayCall = mayCall)
        rememberDisputeNotes(runId, disputeNotes(runId, dispute.question, parties, resolution, null))
    }
    val testRun = investigationRunId
    if (request?.kind == InvestigationKind.Test && testRun != null && testFacts != null && resolution is DisputeResolution.Agreed) {
        val read = testFacts(testRun).orEmpty()
        if (read.isNotEmpty()) {
            // One more agreed ruling on what the test found; she may not commission another investigation.
            resolution = settleWithAgreement(dispute.withFindings(read), parties, queenModel, voices, allowInvestigation = false, mayCall = mayCall)
            rememberDisputeNotes(runId, disputeNotes(runId, dispute.question, parties, resolution, null))
        }
    }
    return resolution
}

/** How long a finished test report is waited for, and how often it is looked for. The keeper's own waiting is not counted against any creature. */
private const val TEST_READ_BACK_POLLS = 120
private const val TEST_READ_BACK_POLL_MILLIS = 5_000L

/**
 * The results of the test swarm [testRunId] as listed facts, once its Reviewer report is complete ([testReportFacts]); empty when the
 * run ended without one, null when it is still not complete after about ten minutes (the keeper still has the run to open). The
 * test run is cycled when the keeper opens it; this only reads it.
 */
internal suspend fun ApplicationRuntime.readBackTestReport(testRunId: String): List<String>? {
    repeat(TEST_READ_BACK_POLLS) {
        val run = persistence.runs.get(WorkflowRunId(testRunId)) ?: return null
        com.hereliesaz.geministrator.queen.testReportFacts(run)?.let { return it }
        kotlinx.coroutines.delay(TEST_READ_BACK_POLL_MILLIS)
    }
    return null
}

/**
 * Her own model, the voice of each creature that may be in a dispute (by role id), and the research she may commission. Until
 * the app gives the runtime one ([ApplicationRuntime.disputeModels]) she is away: a dispute then goes to the keeper in Messages.
 * [mayCall] is where the keeper's spend caps are checked before a model call.
 */
class DisputeModels(
    val queen: QueenModel,
    val voices: Map<String, QueenModel> = emptyMap(),
    val research: ResearchInvestigation? = null,
    val mayCall: () -> Boolean = { true },
)

/**
 * The Test investigation: a small swarm of generic Planner and Reviewer creatures that tries each safe disputed option on a
 * small generated task and reports how each did ([disputeTestDefinition]). It is a normal persisted run, so the keeper's spend
 * caps and each creature's time allowance apply, and the keeper can open it and stop it. Launched from the project of [runId],
 * Manual integration, evidence only. Returns the new run's id, or null when it cannot start (no such run or project, a missing
 * Planner or Reviewer, fewer than two safe options, or a launch that fails).
 */
@OptIn(ExperimentalTime::class)
internal suspend fun ApplicationRuntime.launchDisputeTestSwarm(runId: String, dispute: Dispute, request: InvestigationRequest): String? {
    val source = persistence.runs.get(WorkflowRunId(runId)) ?: return null
    val project = persistence.projects.get(source.projectId) ?: return null
    val roles = activeRoles(resolveRoleCollection(persistence.roles.all())).filter { it.enabled }
    val planner = roles.firstOrNull { it.id == BuiltInRoles.Planner.id } ?: return null
    val reviewer = roles.firstOrNull { it.id == BuiltInRoles.Reviewer.id } ?: return null
    val now = Clock.System.now().toEpochMilliseconds()
    val definitionId = WorkflowDefinitionId("dispute-test-$now")
    val definition = disputeTestDefinition(definitionId, dispute, request, planner, reviewer) ?: return null
    val testRunId = WorkflowRunId("dispute-test-run-$now")
    val testRoles = listOf(planner, reviewer)
    WorkflowLaunchService(
        preparer = WorkflowDefinitionPreparer(providerRegistry, testRoles),
        persistence = persistence,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
        roles = testRoles,
    ).launch(
        project = project,
        definition = definition,
        workflowRunId = testRunId,
        objective = "Try the disputed options and report how each did: ${dispute.question.replace('\n', ' ').trim().take(200)}",
        nowEpochMillis = now,
        taskRunIdFactory = { id -> TaskRunId("${testRunId.value}-${id.value}") },
        parentWorkflowRunIds = listOf(source.id),
    )
    loadLatest()
    return testRunId.value
}
