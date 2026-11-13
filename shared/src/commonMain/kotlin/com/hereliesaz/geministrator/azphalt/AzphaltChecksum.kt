package com.hereliesaz.geministrator.azphalt

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * The lower-case hex digest in a `sha256-<hex>` or `sha256:<hex>` checksum, or null when [value] is
 * neither. One parser for the store's package check and the installer's download check.
 */
fun azphaltSha256Hex(value: String?): String? {
    val text = value?.trim()?.lowercase() ?: return null
    val hex = when {
        text.startsWith("sha256-") -> text.removePrefix("sha256-")
        text.startsWith("sha256:") -> text.removePrefix("sha256:")
        else -> return null
    }
    return hex.takeIf { SHA256_HEX.matches(it) }
}
