package com.hereliesaz.geministrator.prompts

import com.hereliesaz.geministrator.control.ControlRedaction

/** One menu choice: [label] is what the keeper reads, [text] is what the field takes when it is picked. */
internal data class PromptChoice(val label: String, val text: String)

/**
 * Deterministic starting text for a field, from what the screen already knows. No model is called and nothing
 * is sent anywhere. Every function returns null when nothing sensible exists, and the field stays empty.
 */
internal object PromptSuggestions {
    const val MAX_REASON_CHARS = 160

    private val driveLetter = Regex("^[A-Za-z]:[\\\\/]")

    /** The objective last fed, from [history] (newest first). */
    fun lastObjective(history: List<PromptEntry>): String? = history.firstOrNull()?.text

    /** A first launch's seed: the last objective fed on this device, else the static [starter] goal. */
    fun firstObjective(history: List<PromptEntry>, starter: String?): String? =
        lastObjective(history) ?: starter?.takeIf(String::isNotBlank)

    /** The feed bar's seed: what a swarm that has finished was fed, to run again with a tweak. Nothing while it runs. */
    fun feedObjective(runObjective: String?, runFinished: Boolean): String? =
        runObjective?.trim()?.takeIf { runFinished && it.isNotEmpty() }

    /** A continuation's seed: the goal of the run it reads, to edit into the next one. */
    fun continueObjective(parentObjective: String?): String? = parentObjective?.trim()?.takeIf(String::isNotEmpty)

    /**
     * Why a step stopped, in one short line: scrubbed of key shapes, first line only, cut to [MAX_REASON_CHARS].
     * Null when [failure] is blank. It can hold a provider's error body, so it is never used unscrubbed.
     */
    fun retryReason(failure: String?): String? {
        val line = failure?.let { ControlRedaction.scrub(it) }
            ?.lineSequence()?.map { PromptHistoryRules.normalize(it) }?.firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (line.length <= MAX_REASON_CHARS) line else line.take(MAX_REASON_CHARS - 1).trimEnd() + "…"
    }

    enum class LocatorSource { GitHub, GitLab, Local }

    /** Which source a remembered locator belongs to, so a GitLab URL is not seeded into a GitHub field. */
    fun locatorSource(locator: String): LocatorSource {
        val clean = locator.trim()
        return when {
            clean.startsWith("/") || clean.startsWith("~") || clean.startsWith("\\") ||
                driveLetter.containsMatchIn(clean) -> LocatorSource.Local
            "gitlab" in clean.lowercase() -> LocatorSource.GitLab
            else -> LocatorSource.GitHub
        }
    }

    /** A short name for a repository locator: `owner/repo` of a URL or shorthand, else the last folder of a path. */
    fun repositoryLabel(locator: String): String? {
        val clean = locator.trim().trimEnd('/', '\\').removeSuffix(".git")
        if (clean.isEmpty()) return null
        val parts = clean.split('/', '\\').filter { it.isNotEmpty() }
        return when {
            locatorSource(clean) == LocatorSource.Local -> parts.lastOrNull()
            parts.size >= 2 -> parts.takeLast(2).joinToString("/")
            else -> parts.firstOrNull()
        }?.takeIf { it.isNotBlank() }
    }

    /** Remembered locators that fit [source], newest first. */
    fun repositoryEntries(history: List<PromptEntry>, source: LocatorSource): List<PromptEntry> =
        history.filter { locatorSource(it.text) == source }

    /** A new hive's name: the name the planner drafted, else the first words of what it was fed. */
    fun hiveName(drafted: String?, objective: String?, nameFromObjective: (String) -> String): String? =
        drafted?.trim()?.takeIf(String::isNotEmpty)
            ?: objective?.trim()?.takeIf(String::isNotEmpty)?.let(nameFromObjective)?.takeIf(String::isNotBlank)

    /** A swarm draft's seed: the last prompt typed there, else the static [starter] when a repository is linked. */
    fun swarmDraft(history: List<PromptEntry>, starter: String?): String? =
        lastObjective(history) ?: starter?.takeIf(String::isNotBlank)
}
