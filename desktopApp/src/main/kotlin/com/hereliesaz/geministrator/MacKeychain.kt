package com.hereliesaz.geministrator

/**
 * Writes a generic password to the macOS keychain without putting the secret on a command line.
 * `security add-generic-password -w <secret>` exposes the secret in the process arguments, which
 * any local process can read with `ps`; the interactive mode (`security -i`) takes the command
 * from standard input instead.
 */
internal object MacKeychain {
    /** True when the item was stored. The caller deletes any existing item first. */
    fun addGenericPassword(service: String, account: String, secret: String): Boolean {
        // The interactive parser is line based, so a line break would end the command early.
        if (secret.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        return CredentialTool.run(
            listOf("security", "-i"),
            stdin = "add-generic-password -s ${quote(service)} -a ${quote(account)} -w ${quote(secret)} -U\n",
        )?.ok == true
    }

    /** Double-quoted argument as the `security -i` parser reads it: only `\` and `"` need escaping. */
    internal fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
