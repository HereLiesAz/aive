package com.hereliesaz.geministrator.research

/**
 * The research kind of the queen's investigation: the same step, consent gate and budget as the research before planning,
 * asked about the fact she lacks. What it returns is the leads it found, each one line marked as untrusted reference with its
 * source and trust and capped (see [toAvailableTools]); it executes nothing it finds. Null when nothing was consulted or found,
 * which is "cannot investigate". The keeper's waiting is never counted: the step times only its own local pass and lookups.
 */
class ResearchInvestigation(
    private val step: ResearchStep,
    private val context: () -> ResearchContext = { ResearchContext() },
    private val budget: ResearchBudget = ResearchBudget(),
) {
    suspend fun findings(missingFact: String, runKey: String): List<String>? {
        val objective = ResearchText.clean(missingFact, 200)
        if (objective.isEmpty()) return null
        // "Learn about" is a word the step's own deterministic rule reads as a request to look something up.
        val outcome = step.run("Learn about $objective", context(), runKey)
        return outcome.leads.toAvailableTools(budget).takeIf { it.isNotEmpty() }
    }
}
