package com.hereliesaz.geministrator.azphalt

/** Azphalt 0.1 host API version implemented by this integration. */
const val HAIVE_AZPHALT_API_VERSION: String = "0.1.0"

internal data class AzphaltApiVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AzphaltApiVersion> {
    override fun compareTo(other: AzphaltApiVersion): Int =
        compareValuesBy(this, other, AzphaltApiVersion::major, AzphaltApiVersion::minor, AzphaltApiVersion::patch)

    companion object {
        fun parse(raw: String): AzphaltApiVersion? {
            val parts = raw.trim().split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val values = parts.map { part ->
                if (part.isEmpty() || part.any { !it.isDigit() }) return null
                part.toIntOrNull() ?: return null
            }
            return AzphaltApiVersion(
                major = values[0],
                minor = values.getOrElse(1) { 0 },
                patch = values.getOrElse(2) { 0 },
            )
        }
    }
}

/**
 * Implements the exact Azphalt 0.1 `compat` grammar: one optional comparator followed by
 * MAJOR[.MINOR[.PATCH]]. No ranges, unions, prerelease tags, caret or tilde syntax are accepted.
 */
fun azphaltCompatSatisfies(
    compat: String,
    hostVersion: String = HAIVE_AZPHALT_API_VERSION,
): Boolean? {
    val expression = compat.trim()
    if (expression.isEmpty()) return null
    val match = Regex("^(>=|>|<=|<|=)?\\s*([0-9]+(?:\\.[0-9]+){0,2})$").matchEntire(expression) ?: return null
    val comparator = match.groupValues[1].ifEmpty { ">=" }
    val required = AzphaltApiVersion.parse(match.groupValues[2]) ?: return null
    val host = AzphaltApiVersion.parse(hostVersion) ?: return null
    val comparison = host.compareTo(required)
    return when (comparator) {
        ">=" -> comparison >= 0
        ">" -> comparison > 0
        "<=" -> comparison <= 0
        "<" -> comparison < 0
        "=" -> comparison == 0
        else -> null
    }
}
