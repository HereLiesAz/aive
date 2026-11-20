package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.queen.QueenNote
import com.hereliesaz.geministrator.queen.QueenRelay

/** What the hive just did that a tour step can wait on. Each maps to one [ExpectKind]; nothing here is model text. */
enum class RunSignal(val kind: ExpectKind) {
    /** A creature left its egg: its task was released to work. */
    Hatched(ExpectKind.RunHatched),

    /** A creature finished and a creature that waited on it began: work changed hands. */
    HandedOff(ExpectKind.RunHandedOff),

    /** A creature made an artifact. */
    Artifact(ExpectKind.RunArtifact),

    /** The swarm reached the end of the run. */
    Done(ExpectKind.RunDone),

    /** The queen added a line to Messages for this run. */
    QueenSpoke(ExpectKind.QueenSpoke),

    /** A creature began waiting on the keeper: a question asked, an approval wanted, or a step that stopped and escalated. */
    NeedRaised(ExpectKind.RunNeedRaised),

    /** The queen opened a dispute between creatures (a new dispute note in Messages). */
    DisputeOpened(ExpectKind.RunDisputeOpened),
}

private val working = setOf(
    TaskRunStatus.Planning, TaskRunStatus.AwaitingApproval, TaskRunStatus.Running, TaskRunStatus.Verifying, TaskRunStatus.Retrying,
)

/** True while the creature waits on the keeper. */
private fun TaskRun?.needy(): Boolean = this != null &&
    (keeperQuestion != null || status == TaskRunStatus.AwaitingApproval || status == TaskRunStatus.Escalated)

private fun TaskRun?.started(): Boolean = this != null && (status in working || status == TaskRunStatus.Completed)

/**
 * What changed between two publishes of the same run (pure; no model call, no clock). [before] null means the first sight of the run:
 * everything already true then counts, so a tour that begins mid-run still sees the creatures that are already out.
 * [queenBefore] and [queenAfter] are how many Messages lines the queen has for the run; [disputesBefore] and [disputesAfter] are how
 * many of those are dispute notes (see [disputeLines]).
 */
fun runSignals(
    definition: WorkflowDefinition,
    before: WorkflowRun?,
    after: WorkflowRun,
    queenBefore: Int = 0,
    queenAfter: Int = 0,
    disputesBefore: Int = 0,
    disputesAfter: Int = 0,
): Set<RunSignal> = buildSet {
    val was = before?.taskRuns.orEmpty()
    for (task in definition.tasks) {
        val now = after.taskRuns[task.id]
        val then = was[task.id]
        if (now != null && now.status != TaskRunStatus.Created && now.status != TaskRunStatus.Blocked && (then == null || then.status in setOf(TaskRunStatus.Created, TaskRunStatus.Blocked))) {
            add(RunSignal.Hatched)
        }
        if (now != null && now.artifacts.size > (then?.artifacts?.size ?: 0)) add(RunSignal.Artifact)
        // A hand-off: this creature began now, and something it waited on is finished.
        if (now.needy() && !then.needy()) add(RunSignal.NeedRaised)
        if (now.started() && !then.started() && task.dependsOn.any { after.taskRuns[it]?.status == TaskRunStatus.Completed }) {
            add(RunSignal.HandedOff)
        }
    }
    if (after.status == WorkflowRunStatus.Completed && before?.status != WorkflowRunStatus.Completed) add(RunSignal.Done)
    if (queenAfter > queenBefore) add(RunSignal.QueenSpoke)
    if (disputesAfter > disputesBefore) add(RunSignal.DisputeOpened)
}

/** How many of the queen's Messages lines for the run are dispute notes: the count [runSignals] reads for [RunSignal.DisputeOpened]. */
fun disputeLines(notes: List<QueenNote>): Int = notes.count { it.voice.relay == QueenRelay.Dispute }
