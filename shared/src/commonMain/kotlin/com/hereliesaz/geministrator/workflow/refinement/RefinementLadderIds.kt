package com.hereliesaz.geministrator.workflow.refinement

/**
 * The ids of the tasks a refinement ladder is expanded into. The expander writes them and the
 * dataset extractor reads them, so both go through here.
 */
object RefinementLadderIds {
    const val MARKER: String = "--rl-"

    enum class Kind { Level, Gate, Verifier }

    data class Parsed(val baseTaskId: String, val kind: Kind, val index: Int?)

    fun level(baseTaskId: String, index: Int): String = "$baseTaskId${MARKER}level-$index"

    fun gate(baseTaskId: String, index: Int): String = "$baseTaskId${MARKER}gate-$index"

    fun verifier(baseTaskId: String): String = "$baseTaskId${MARKER}verifier"

    /** The ladder role of a generated task id, or null for any other id. */
    fun parse(taskId: String): Parsed? {
        val at = taskId.lastIndexOf(MARKER)
        if (at <= 0) return null
        val base = taskId.substring(0, at)
        val tail = taskId.substring(at + MARKER.length)
        return when {
            tail == "verifier" -> Parsed(base, Kind.Verifier, null)
            tail.startsWith("level-") -> tail.removePrefix("level-").toIntOrNull()?.let { Parsed(base, Kind.Level, it) }
            tail.startsWith("gate-") -> tail.removePrefix("gate-").toIntOrNull()?.let { Parsed(base, Kind.Gate, it) }
            else -> null
        }
    }
}
