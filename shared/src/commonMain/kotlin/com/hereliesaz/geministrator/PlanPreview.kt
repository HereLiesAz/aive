package com.hereliesaz.geministrator

/** The first lines of a plan, and whether there is more of it than is shown. */
internal data class PlanPreview(val shown: String, val truncated: Boolean)

/** A plan's opening ([maxLines] lines, [maxChars] characters), so what is about to be approved is read first; null with no plan. */
internal fun planPreview(plan: String?, maxLines: Int = 6, maxChars: Int = 600): PlanPreview? {
    val text = plan?.trim().orEmpty()
    if (text.isEmpty()) return null
    val lines = text.lines()
    var shown = lines.take(maxLines).joinToString("\n")
    var truncated = lines.size > maxLines
    if (shown.length > maxChars) {
        shown = shown.take(maxChars)
        truncated = true
    }
    return PlanPreview(shown, truncated)
}
