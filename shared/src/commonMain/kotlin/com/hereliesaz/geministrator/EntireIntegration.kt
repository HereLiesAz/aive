package com.hereliesaz.geministrator

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Entire (entire.com): the git-native record of agent work, as a repository host (docs/Entire.md).
 * Desktop provides it through the `entire` CLI; elsewhere it is absent and its card isn't shown.
 */
interface EntireIntegration {
    suspend fun status(): EntireStatus

    /** Signs in with Entire in the browser. Null on success, else what went wrong. */
    suspend fun logIn(): String?

    /** Record Local Git runs as Entire checkpoints, in repositories where Entire is set up. */
    fun setRecordRuns(record: Boolean)

    /**
     * Clones an Entire repository (`/gh/<owner>/<repo>` or `/et/<project>/<repo>`) into a folder the
     * keeper picks. The new working tree's path, or null if they cancelled; throws on failure.
     */
    suspend fun clone(reference: String): String?
}

data class EntireStatus(
    /** The `entire` command's version line, or null when it isn't installed. */
    val version: String? = null,
    /** Who is signed in, or null. */
    val account: String? = null,
    val recordRuns: Boolean = false,
) {
    val installed: Boolean get() = version != null
}

/** `/gh/<owner>/<repo>` or `/et/<project>/<repo>`. */
fun isEntireRepositoryReference(raw: String): Boolean = EntireReference.matches(raw.trim())

private val EntireReference = Regex("^/(gh|et)/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

const val ENTIRE_INSTALL_URL = "https://docs.entire.com/cli/installation"

internal val LocalEntire = staticCompositionLocalOf<EntireIntegration?> { null }
