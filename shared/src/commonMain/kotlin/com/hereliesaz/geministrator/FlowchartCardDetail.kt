package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.agentui.awaitsKeeperAnswer
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.workflow.GATE_VERDICT_BLOCKED
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_GLOBAL_PAUSE_CODE
import com.hereliesaz.geministrator.workflow.REMOTE_COMPUTE_UNAVAILABLE_CODE
import com.hereliesaz.geministrator.workflow.ROLE_BUDGET_SPENT
import com.hereliesaz.geministrator.workflow.SPEND_CAP_STOP
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import com.hereliesaz.geministrator.workflow.awaitsNestedHumanApproval

/** What a flowchart card's detail panel says, in one plain sentence. The composable maps each kind to a string. */
internal enum class FlowchartSentenceKind {
    /** "Working: <what>"; [FlowchartCardDetail.subject] is the progress message, else the role, else the step. */
    Working,
    WaitingForApproval,
    Question,
    Escalated,
    SpendStopped,
    /** [FlowchartCardDetail.subject] is the role that spent it. */
    BudgetSpent,
    ComputeUnavailable,
    IntegrationUnavailable,
    HallMonitorPaused,
    /** [FlowchartCardDetail.subject] is the provider, [FlowchartCardDetail.detail] what it said. */
    ProviderFailed,
    NoProvider,
    GateTurnedBack,
    /** [FlowchartCardDetail.subject] is the name of the step it waits on. */
    WaitingOnStep,
    KeeperPaused,
    /** [FlowchartCardDetail.detail] is the reported reason, when there is one. */
    Failed,
    /** A hold with a code this panel has no better word for; [FlowchartCardDetail.detail] is its message. */
    Blocked,
    NotStarted,
    Done,
    Skipped,
}

/** The one place a card's button takes the keeper; the composable names each in a verb. */
internal sealed interface FlowchartCardAction {
    /** The creature's Needs (where the decision is made); null names the Inbox itself (the Hall Monitor's pause). */
    data class OpenInbox(val taskId: String?) : FlowchartCardAction
    data object OpenLimits : FlowchartCardAction
    data object OpenCompute : FlowchartCardAction
    /** Connections, with no provider to name. */
    data object OpenConnections : FlowchartCardAction
    /** The provider's credentials. */
    data class FixProvider(val providerId: String) : FlowchartCardAction
    data class OpenCreature(val taskId: String, val tab: CreatureTab) : FlowchartCardAction
    /** Selects another card in this flowchart: the step this one waits on. */
    data class ShowStep(val taskId: String, val name: String) : FlowchartCardAction
}

internal data class FlowchartCardDetail(
    val sentence: FlowchartSentenceKind,
    val action: FlowchartCardAction,
    val subject: String? = null,
    val detail: String? = null,
)

private val ACTIVE = setOf(
    TaskRunStatus.Planning,
    TaskRunStatus.Running,
    TaskRunStatus.Verifying,
    TaskRunStatus.Retrying,
)

private val HTTP_CLIENT_ERROR = Regex("""(?i)(http|status)[^0-9]{0,4}4\d\d""")

/** The raw text a card keeps, shown only behind Technical details. */
private const val DETAIL_LIMIT = 300

/**
 * Reads a flowchart card's state into one sentence and the one button that leads to where it is
 * unblocked. Every link is one the shell already has (openInbox, onOpenLimits, onOpenCompute,
 * openConnections, onReconfigureProvider, openCreature); this only decides which. Null when the run has no
 * such step.
 *
 * [computeAvailable] is false where the Compute place is not offered; the card then leads to the creature.
 */
internal fun flowchartCardDetail(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    computeAvailable: Boolean = true,
): FlowchartCardDetail? {
    val taskRun = run.taskRuns[taskId] ?: return null
    val id = taskId.value
    val task = definition.tasks.firstOrNull { it.id == taskId }
    val roleId = taskRun.assignedRoleId ?: task?.roleId
    val roleName = roleId?.let { rid -> roles.firstOrNull { it.id == rid }?.name ?: rid.value }
    val creature = FlowchartCardAction.OpenCreature(id, CreatureTab.Work)
    val code = taskRun.blockingReason?.code
    val message = taskRun.blockingReason?.message?.trim()?.takeIf(String::isNotEmpty)

    return when {
        taskRun.status == TaskRunStatus.Completed -> FlowchartCardDetail(FlowchartSentenceKind.Done, creature)
        taskRun.status == TaskRunStatus.Cancelled -> FlowchartCardDetail(FlowchartSentenceKind.Skipped, creature)
        taskRun.status == TaskRunStatus.AwaitingApproval ->
            FlowchartCardDetail(FlowchartSentenceKind.WaitingForApproval, FlowchartCardAction.OpenInbox(id))
        taskRun.status == TaskRunStatus.Escalated ->
            FlowchartCardDetail(FlowchartSentenceKind.Escalated, FlowchartCardAction.OpenInbox(id))
        taskRun.status == TaskRunStatus.Failed -> failed(taskRun, creature)
        taskRun.awaitsKeeperAnswer() ->
            FlowchartCardDetail(FlowchartSentenceKind.Question, FlowchartCardAction.OpenInbox(id))
        taskRun.awaitsNestedHumanApproval() ->
            FlowchartCardDetail(FlowchartSentenceKind.WaitingForApproval, FlowchartCardAction.OpenInbox(id))
        taskRun.status in ACTIVE -> FlowchartCardDetail(
            FlowchartSentenceKind.Working,
            creature,
            subject = taskRun.progressMessage?.trim()?.takeIf(String::isNotEmpty)?.take(DETAIL_LIMIT)
                ?: roleName
                ?: task?.name
                ?: id,
        )
        taskRun.status == TaskRunStatus.Blocked -> blocked(
            code, message, taskRun, definition, run, roleName, computeAvailable, creature, id,
        )
        // Created or Ready.
        run.keeperPause != null -> FlowchartCardDetail(FlowchartSentenceKind.KeeperPaused, creature)
        else -> FlowchartCardDetail(FlowchartSentenceKind.NotStarted, creature)
    }
}

private fun failed(taskRun: TaskRun, creature: FlowchartCardAction): FlowchartCardDetail {
    val said = taskRun.progressMessage?.trim()?.takeIf(String::isNotEmpty)
    val kind = classifyError(said)
    val providerId = taskRun.assignedProviderId?.value
    return when {
        // Nothing connected could take the job: the fix is connecting one.
        kind == ErrorKind.NoAgent ->
            FlowchartCardDetail(FlowchartSentenceKind.NoProvider, FlowchartCardAction.OpenConnections)
        // The provider itself refused: a rejected key, a spent balance, or a request it answered with a 4xx.
        providerId != null && said != null &&
            (kind == ErrorKind.KeyRejected || kind == ErrorKind.OutOfCredit || HTTP_CLIENT_ERROR.containsMatchIn(said)) ->
            FlowchartCardDetail(
                FlowchartSentenceKind.ProviderFailed,
                FlowchartCardAction.FixProvider(providerId),
                subject = providerId,
                detail = said.take(DETAIL_LIMIT),
            )
        else -> FlowchartCardDetail(FlowchartSentenceKind.Failed, creature, detail = said?.take(DETAIL_LIMIT))
    }
}

private fun blocked(
    code: String?,
    message: String?,
    taskRun: TaskRun,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roleName: String?,
    computeAvailable: Boolean,
    creature: FlowchartCardAction,
    id: String,
): FlowchartCardDetail = when (code) {
    SPEND_CAP_STOP -> FlowchartCardDetail(FlowchartSentenceKind.SpendStopped, FlowchartCardAction.OpenLimits)
    ROLE_BUDGET_SPENT -> FlowchartCardDetail(
        FlowchartSentenceKind.BudgetSpent,
        FlowchartCardAction.OpenCreature(id, CreatureTab.Mind),
        subject = roleName,
    )
    REMOTE_COMPUTE_UNAVAILABLE_CODE -> FlowchartCardDetail(
        FlowchartSentenceKind.ComputeUnavailable,
        if (computeAvailable) FlowchartCardAction.OpenCompute else creature,
    )
    WorkflowRunFactory.EXECUTOR_INTEGRATION_UNAVAILABLE ->
        FlowchartCardDetail(FlowchartSentenceKind.IntegrationUnavailable, FlowchartCardAction.OpenConnections)
    HALL_MONITOR_GLOBAL_PAUSE_CODE ->
        FlowchartCardDetail(FlowchartSentenceKind.HallMonitorPaused, FlowchartCardAction.OpenInbox(null))
    GATE_VERDICT_BLOCKED -> FlowchartCardDetail(FlowchartSentenceKind.GateTurnedBack, creature)
    WorkflowRunFactory.WAITING_FOR_DEPENDENCIES -> waitingOnSteps(taskRun, definition, run, creature)
    else -> if (classifyError(message) == ErrorKind.NoAgent) {
        FlowchartCardDetail(FlowchartSentenceKind.NoProvider, FlowchartCardAction.OpenConnections)
    } else {
        FlowchartCardDetail(FlowchartSentenceKind.Blocked, creature, detail = message?.take(DETAIL_LIMIT))
    }
}

/** The step a waiting card points at: one that needs attention first, else the first not yet finished. */
private fun waitingOnSteps(
    taskRun: TaskRun,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    creature: FlowchartCardAction,
): FlowchartCardDetail {
    val task = definition.tasks.firstOrNull { it.id == taskRun.taskDefinitionId }
    val condition = when (val c = task?.condition) {
        is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome -> c.ofTask
        is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure -> c.ofTask
        else -> null
    }
    val upstream = (task?.dependsOn.orEmpty() + listOfNotNull(condition))
        .filter { it != taskRun.taskDefinitionId }
        .distinct()
        .mapNotNull { dep -> run.taskRuns[dep]?.let { dep to it.status } }
        .filter { (_, status) -> status != TaskRunStatus.Completed }
    val stuck = setOf(
        TaskRunStatus.Failed,
        TaskRunStatus.Escalated,
        TaskRunStatus.AwaitingApproval,
        TaskRunStatus.Blocked,
    )
    val chosen = upstream.firstOrNull { it.second in stuck } ?: upstream.firstOrNull()
        ?: return FlowchartCardDetail(FlowchartSentenceKind.NotStarted, creature)
    val name = definition.tasks.firstOrNull { it.id == chosen.first }?.name ?: chosen.first.value
    return FlowchartCardDetail(
        FlowchartSentenceKind.WaitingOnStep,
        FlowchartCardAction.ShowStep(chosen.first.value, name),
        subject = name,
    )
}

/**
 * Whether the creature's bottom sheet may open on a phone: in Live and Build, not in Flowchart, where a card's
 * own detail panel answers the tap. [flowchart] is the Flowchart view being on screen.
 */
internal fun creatureSheetAllowed(flowchart: Boolean): Boolean = !flowchart
