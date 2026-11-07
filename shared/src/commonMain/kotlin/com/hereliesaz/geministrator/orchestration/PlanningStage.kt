package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.RoleDefinition
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Where the planning of a launch has got to, as the launch records it (docs/Node-Creatures.md,
 * Planning): the plan is an egg the planner gestates, the reviewer hatches from it, and the first step's
 * egg is gestated by the planner and the reviewer together, so it cannot hatch until both are done.
 *
 * It is a record, not a guess: each stage is reported by the code at the moment it happens, and the
 * field (`PlanningScene.kt`) only draws what was reported.
 */
enum class PlanningPhase {
    /** The planner is drafting the plan; the reviewer is still an egg. */
    Planning,

    /** The plan is drafted and the reviewer has hatched from it, reading it. */
    Reviewing,

    /** The reviewer sent the plan back and the planner is reworking it. */
    Reworking,

    /** The review is over and the keeper has the final say; see [PlanningStage.verdict]. */
    AwaitingKeeper,

    /** The keeper started the run: nothing holds the first step's egg any more. */
    Started,
}

/** How the review ended, once it has. */
enum class PlanningVerdict { Approved, NotApproved, Unreachable }

data class PlanningStage(
    val phase: PlanningPhase,
    /** The creature that drafts the plan. */
    val planner: RoleDefinition,
    /** The creature that reviews it. */
    val reviewer: RoleDefinition,
    /** The role that does the plan's first step, and that step's name; null while there is no plan. */
    val firstStepRole: RoleDefinition? = null,
    val firstStepName: String? = null,
    /** The review round now under way (1-based); 0 while the planner drafts the first plan. */
    val round: Int = 0,
    /** Set only while [phase] is [PlanningPhase.AwaitingKeeper]. */
    val verdict: PlanningVerdict? = null,
)

/** Carries [PlanningStage]s from the launch up to the screen, as [LaunchProgress] carries its lines. */
class PlanningStages(private val sink: (PlanningStage) -> Unit) : AbstractCoroutineContextElement(Key) {
    fun report(stage: PlanningStage) = sink(stage)

    companion object Key : CoroutineContext.Key<PlanningStages>
}

/** Reports [stage] to the enclosing [PlanningStages], if any. */
suspend fun reportPlanningStage(stage: PlanningStage) {
    coroutineContext[PlanningStages]?.report(stage)
}

/** The step a plan begins with: the first with nothing before it. */
fun OrchestrationPlan.firstStep(): OrchestrationPlanStep? = steps.firstOrNull { it.dependsOn.isEmpty() } ?: steps.firstOrNull()
