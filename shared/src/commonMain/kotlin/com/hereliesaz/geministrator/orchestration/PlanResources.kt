package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskResource
import com.hereliesaz.geministrator.domain.TaskResources

/** What [step] works from, as the plan says it: bounded, unknown kinds dropped, and only steps that exist in this plan. */
fun OrchestrationPlan.resourcesFor(step: OrchestrationPlanStep): List<TaskResource> {
    val ids = steps.mapTo(mutableSetOf()) { it.id }
    return TaskResources.fromJson(step.resources) { written -> written.takeIf { it in ids && it != step.id }?.let(::TaskDefinitionId) }
}

/** Words that mark a step's objective as needing facts about how this app works. Used only to tell the reviewer, never to change a plan. */
private val NEEDS_APP_FACTS = Regex(
    """(?i)\b(aive|the queen|queen's|hives?|swarms?|creatures?|keeper's room|messages tab|sources?|nanny|roster|set up (?:a|the) (?:hive|swarm)|how (?:this|the) app works)\b""",
)

/** True when [step]'s name or objective reads like it needs facts about how the app works. */
fun OrchestrationPlanStep.needsAppFacts(): Boolean = NEEDS_APP_FACTS.containsMatchIn(name + " " + objective)

/**
 * The plan's steps that read as needing facts about how the app works but do not list AiveDocs: said to the reviewer as
 * a check to make (a hint, not a gate: the reviewer judges).
 */
fun OrchestrationPlan.stepsMissingAiveDocs(): List<OrchestrationPlanStep> =
    steps.filter { it.needsAppFacts() && !TaskResources.hasAiveDocs(resourcesFor(it)) }

/** The line for a step in the plan the keeper and reviewer read, or null when the step lists no resources. */
fun OrchestrationPlan.worksFromLine(step: OrchestrationPlanStep): String? = TaskResources.worksFromLine(resourcesFor(step))
