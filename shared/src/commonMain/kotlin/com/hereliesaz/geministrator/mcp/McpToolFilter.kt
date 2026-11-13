package com.hereliesaz.geministrator.mcp

import kotlinx.serialization.json.JsonObject

/** Error outcome text for a call to a tool a step's contract does not allow. */
const val TOOL_NOT_ALLOWED: String = "TOOL_NOT_ALLOWED"

/**
 * A run's toolbox cut down to the tools one step's contract allows (docs/Step-Templates.md). The offers are exactly
 * those named in [allowed]; a call to any other name is refused as an error outcome and never reaches a server.
 */
class FilteredMcpToolbox(
    private val inner: McpToolbox,
    private val allowed: Set<String>,
) : McpToolbox {
    override val offers: List<McpToolOffer> = inner.offers.filter { it.name in allowed }

    override suspend fun call(offerName: String, arguments: JsonObject): McpToolOutcome {
        val offer = offers.firstOrNull { it.name == offerName }
            ?: return McpToolOutcome(
                offerName = offerName,
                serverId = "",
                serverName = "",
                toolName = offerName,
                text = "$TOOL_NOT_ALLOWED: this step may not use '$offerName'.",
                isError = true,
            )
        return inner.call(offer.name, arguments)
    }
}

/**
 * [this] restricted to [names]: null leaves it whole (a task with no contract); an empty list leaves no tool. Returns
 * null when no tool is left, which is the same as offering none.
 */
fun McpToolbox.restrictedTo(names: List<String>?): McpToolbox? {
    if (names == null) return this
    val filtered = FilteredMcpToolbox(this, names.toSet())
    return filtered.takeIf { it.offers.isNotEmpty() }
}
