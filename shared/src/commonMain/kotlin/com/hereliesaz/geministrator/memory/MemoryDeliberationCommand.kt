package com.hereliesaz.geministrator.memory

/**
 * The session command a running agent writes to record a deliberation ([MemoryTool.deliberate]):
 *
 * ```
 * /deliberate memory-node:<a> memory-node:<b> chosen=memory-node:<a> | <conclusion>
 * ```
 *
 * One command per line, in the agent's thinking or output. The ids before `|` are the memories it
 * considered (the `memory-node:` prefix is optional); `chosen=<id>` names the one it judged correct
 * (it is cited too if it was not listed), `chosen=none` or no `chosen=` records it as unresolved.
 * Everything after `|` is the conclusion; `evidence=` segments after a further `|` are kept as the
 * evidence it considered. Memory shows the exact command under every divergent recall hit.
 */
internal data class MemoryDeliberationCommand(
    val cited: List<MemoryNodeId>,
    val chosen: MemoryNodeId?,
    val conclusion: String,
    val evidence: List<String> = emptyList(),
) {
    fun toRequest(sourceSessionId: String, scope: MemoryBankScope, atEpochMillis: Long) = MemoryDeliberationRequest(
        sourceSessionId = sourceSessionId,
        conclusion = conclusion,
        citedNodeIds = cited,
        evidence = evidence,
        scope = scope,
        deliberatedAtEpochMillis = atEpochMillis,
        chosen = chosen,
    )

    companion object {
        const val COMMAND = "/deliberate"
        private const val NODE_PREFIX = "memory-node:"
        private val LINE = Regex("""(?m)^\s*/deliberate\s+(.+)$""")

        /** Every well-formed command in [text]; malformed ones are returned as errors. */
        fun parse(text: String): List<Result<MemoryDeliberationCommand>> =
            LINE.findAll(text).map { runCatching { parseLine(it.groupValues[1]) } }.toList()

        private fun parseLine(body: String): MemoryDeliberationCommand {
            val parts = body.split('|').map(String::trim)
            require(parts.size >= 2 && parts[1].isNotBlank()) { "write the conclusion after |" }
            var chosen: MemoryNodeId? = null
            val cited = mutableListOf<MemoryNodeId>()
            parts[0].split(Regex("[\\s,]+")).filter(String::isNotBlank).forEach { token ->
                if (token.startsWith("chosen=")) {
                    val value = token.removePrefix("chosen=")
                    chosen = if (value.equals("none", ignoreCase = true) || value.isBlank()) null else id(value)
                } else {
                    cited += id(token)
                }
            }
            chosen?.let { if (it !in cited) cited += it }
            require(cited.isNotEmpty()) { "cite at least one memory-node id" }
            val evidence = parts.drop(2).map { it.removePrefix("evidence=").trim() }.filter(String::isNotBlank)
            return MemoryDeliberationCommand(cited.distinct(), chosen, parts[1], evidence)
        }

        private fun id(token: String) = MemoryNodeId(token.removePrefix(NODE_PREFIX).trimEnd('.', ';'))

        /** The command template shown under a divergent hit, citing it and its partners. */
        fun template(hit: MemoryNodeId, partners: List<MemoryNodeId>): String =
            "$COMMAND " + (listOf(hit) + partners).joinToString(" ") { NODE_PREFIX + it.value } +
                " chosen=<one of them, or none> | <why>"
    }
}
