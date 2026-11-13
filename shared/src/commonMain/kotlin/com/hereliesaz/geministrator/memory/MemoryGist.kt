package com.hereliesaz.geministrator.memory

/*
 * How recall is rendered when it is injected into an agent's chat: as gist. A short summary line, the
 * time range and lineage, a note when memories diverge or were settled; never a score, a node id or
 * the full stored text. Storage is unchanged: this only loosens what is shown.
 */

/** Characters of a gist line; the detail level of the depth ladder may show [DETAIL_GIST_CHARS]. */
internal const val GIST_CHARS = 240
internal const val DETAIL_GIST_CHARS = 400

/** [text] on one line, cut at a word boundary to at most [maxChars] (plus an ellipsis when cut). */
internal fun gistOf(text: String, maxChars: Int = GIST_CHARS): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    if (flat.length <= maxChars) return flat
    val head = flat.substring(0, maxChars)
    val cut = if (flat[maxChars] == ' ' || !head.contains(' ')) head else head.substring(0, head.lastIndexOf(' '))
    return cut.trimEnd() + "…"
}

/**
 * The lines under a gist: divergent partners (both remembered, nothing says which is right), the
 * summary of a link delivered with the hit, and that a contested memory has since been settled.
 */
internal fun MemoryRecallHit.gistNotes(): String = buildString {
    conflicts.forEach { partner ->
        append("\n  ↔ diverges (same subject, different content; both remembered): ").append(gistOf(partner.text))
    }
    pairSummaries.forEach { pair ->
        append("\n  ⇄ goes with: ").append(gistOf(pair.summary.text))
    }
    if (resolution != null) append("\n  ✓ settled since; an earlier view was set aside")
}
