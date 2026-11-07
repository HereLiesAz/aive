package com.hereliesaz.geministrator.memory

/**
 * The Memory journal's line for one consolidation step; null when there was nothing to say
 * ([MemoryConsolidationResult.Idle], or no pair summaries written).
 */
internal fun MemoryConsolidationResult.logText(): String? = when (this) {
    MemoryConsolidationResult.Idle -> null
    is MemoryConsolidationResult.Applied -> "${stage.name}: applied, $itemCount item${if (itemCount == 1) "" else "s"} (queue ${queueId.value})"
    is MemoryConsolidationResult.Completed -> "Done with queue ${queueId.value}"
    is MemoryConsolidationResult.Failed -> "${stage.name}: failed (queue ${queueId.value}): $reason"
    is MemoryConsolidationResult.PairSummaries -> if (count > 0) "Wrote $count pair summar${if (count == 1) "y" else "ies"}" else null
    is MemoryConsolidationResult.Declined -> "Declined (queue ${queueId.value}): $reason"
}
