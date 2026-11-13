package com.hereliesaz.geministrator.queen

import kotlinx.coroutines.CancellationException

/**
 * The [InvestigationLauncher] for the two kinds of investigation. [research] gathers what is missing and returns findings
 * (each already marked as untrusted reference with its source), or null when nothing could be consulted; [test] starts the
 * small test swarm and returns the id of its run, or null when it cannot. Either may be absent: that kind is then
 * "cannot investigate", and the keeper has it. One investigation per dispute is the caller's rule ([raiseDispute] enforces it).
 * Findings of a research are kept here by the id launch returned, for [findingsOf].
 */
class DisputeInvestigator(
    private val research: (suspend (missingFact: String) -> List<String>?)? = null,
    private val test: (suspend (InvestigationRequest) -> String?)? = null,
) : InvestigationLauncher {
    private val findings = LinkedHashMap<String, List<String>>()
    private var researches = 0

    override suspend fun launch(request: InvestigationRequest): String? = try {
        when (request.kind) {
            InvestigationKind.Research -> {
                val found = research?.invoke(request.missingFact)?.filter { it.isNotBlank() }.orEmpty()
                if (found.isEmpty()) {
                    null
                } else {
                    researches += 1
                    "research-$researches".also { id ->
                        findings[id] = found
                        while (findings.size > MAX_KEPT) findings.remove(findings.keys.first())
                    }
                }
            }
            InvestigationKind.Test -> test?.invoke(request)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** What a research returned under [investigationId], as facts she may cite; empty for a test (its run holds the evidence). */
    fun findingsOf(investigationId: String): List<String> = findings[investigationId].orEmpty()

    private companion object {
        const val MAX_KEPT = 8
    }
}
