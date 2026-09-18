package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.HallMonitorReport
import kotlinx.serialization.json.Json

private const val HALL_MONITOR_TRIAL_LAUNCH_PREFIX = "__HAIVE_HALL_MONITOR_TEST_SOLUTION__"
private const val HALL_MONITOR_TRIAL_ACTION_PREFIX = "__haive_hall_monitor_trial_action__"
private const val HALL_MONITOR_TRIAL_LAUNCH_SEPARATOR = "::"

internal data class HallMonitorTrialLaunchRequest(
    val findingId: String,
    val solutionIndex: Int,
)

internal fun hallMonitorTrialLaunchObjective(findingId: String, solutionIndex: Int): String {
    require(findingId.isNotBlank()) { "Hall Monitor finding id is required" }
    require(solutionIndex >= 0) { "Hall Monitor solution index must not be negative" }
    return "$HALL_MONITOR_TRIAL_LAUNCH_PREFIX$solutionIndex$HALL_MONITOR_TRIAL_LAUNCH_SEPARATOR$findingId"
}

internal fun parseHallMonitorTrialLaunchObjective(objective: String): HallMonitorTrialLaunchRequest? =
    parseHallMonitorTrialRequest(objective, HALL_MONITOR_TRIAL_LAUNCH_PREFIX)

internal fun hallMonitorTrialActionId(findingId: String, solutionIndex: Int): String {
    require(findingId.isNotBlank()) { "Hall Monitor finding id is required" }
    require(solutionIndex >= 0) { "Hall Monitor solution index must not be negative" }
    return "$HALL_MONITOR_TRIAL_ACTION_PREFIX$solutionIndex$HALL_MONITOR_TRIAL_LAUNCH_SEPARATOR$findingId"
}

internal fun parseHallMonitorTrialActionId(actionId: String): HallMonitorTrialLaunchRequest? =
    parseHallMonitorTrialRequest(actionId, HALL_MONITOR_TRIAL_ACTION_PREFIX)

private fun parseHallMonitorTrialRequest(value: String, prefix: String): HallMonitorTrialLaunchRequest? {
    if (!value.startsWith(prefix)) return null
    val payload = value.removePrefix(prefix)
    val separator = payload.indexOf(HALL_MONITOR_TRIAL_LAUNCH_SEPARATOR)
    require(separator > 0) { "Malformed Hall Monitor solution trial request" }
    val solutionIndex = payload.substring(0, separator).toIntOrNull()
        ?: error("Malformed Hall Monitor solution index")
    val findingId = payload.substring(separator + HALL_MONITOR_TRIAL_LAUNCH_SEPARATOR.length)
    require(findingId.isNotBlank()) { "Malformed Hall Monitor finding id" }
    return HallMonitorTrialLaunchRequest(findingId, solutionIndex)
}

/** Tolerates old fenced Hall Monitor output while new prompts require raw JSON. */
internal fun decodeHallMonitorReportPayload(raw: String): HallMonitorReport {
    require(raw.isNotBlank()) { "Hall Monitor report has no machine-readable payload" }
    val trimmed = raw.trim()
    val normalized = if (trimmed.startsWith("```")) {
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) {
            trimmed.removePrefix("```").removeSuffix("```").trim()
        } else {
            trimmed.substring(firstNewline + 1).removeSuffix("```").trim()
        }
    } else {
        trimmed
    }
    val firstBrace = normalized.indexOf('{')
    val lastBrace = normalized.lastIndexOf('}')
    val candidates = buildList {
        add(normalized)
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            add(normalized.substring(firstBrace, lastBrace + 1))
        }
    }.distinct()
    candidates.forEach { candidate ->
        runCatching { HallMonitorReportJson.decodeFromString(HallMonitorReport.serializer(), candidate) }
            .getOrNull()
            ?.let { return it }
    }
    error(
        "Hall Monitor report is not machine-readable HallMonitorReport JSON. " +
            "Request a revised report before testing a proposed solution.",
    )
}

private val HallMonitorReportJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
