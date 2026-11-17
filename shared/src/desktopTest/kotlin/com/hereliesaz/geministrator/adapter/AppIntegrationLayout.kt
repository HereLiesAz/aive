package com.hereliesaz.geministrator.adapter

import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider

/**
 * Prompt layout for context caching. The app orders a prompt as a stable prefix (the role, then the memory protocol when memory is on) and then a
 * dynamic suffix (task, criteria, recall, habits, artifacts). [check] holds every corpus row to that:
 * the stable part comes first and carries nothing volatile. `score.py` carries the same check.
 */
object AppIntegrationLayout {
    const val TASK_MARK = "\n\nTASK\n"
    private val LABEL = Regex("^[A-Z][A-Z ]+$")
    private val VOLATILE = listOf(Regex("\\d{4}-\\d{2}-\\d{2}"), Regex("\\b\\d{3,}\\b"), Regex("⟦memory⟧"), Regex("Habits formed"))

    /** The stable prefix of a rendered task: everything before the task block. */
    fun prefixOf(user: String): String = user.substringBefore(TASK_MARK)

    /** Block labels of [text]: all-capital lines that start the text or follow a blank line. */
    private fun labels(text: String): List<String> {
        val lines = text.lines()
        return lines.indices.filter { LABEL.matches(lines[it]) && (it == 0 || lines[it - 1].isBlank()) }.map { lines[it] }
    }

    fun check(system: String, user: String): Verdict {
        if (system != OnDeviceTextAgentProvider.SYSTEM_PROMPT) return Verdict.fail("system prompt is not the app's constant")
        if (!user.startsWith("ROLE\n")) return Verdict.fail("the stable role block is not first")
        if (!user.contains(TASK_MARK)) return Verdict.fail("no task block")
        val prefix = prefixOf(user)
        if (labels(prefix) != listOf("ROLE") && labels(prefix) != listOf("ROLE", "MEMORY PROTOCOL")) {
            return Verdict.fail("the stable prefix holds more than the role block and the memory protocol")
        }
        // The protocol is constant rules text and names the memory marker by design: not volatile content.
        val checked = prefix.replace(Regex("\n\nMEMORY PROTOCOL\n[^\n]*"), "")
        VOLATILE.firstOrNull { it.containsMatchIn(checked) }?.let { return Verdict.fail("volatile content in the stable prefix: ${it.pattern}") }
        val suffix = labels(user.substringAfter(TASK_MARK))
        if ("ROLE" in suffix) return Verdict.fail("a role block after the task")
        val criteria = suffix.indexOf("ACCEPTANCE CRITERIA")
        if (criteria > 0) return Verdict.fail("criteria after an artifact block")
        val plan = suffix.indexOf("PLAN ONLY")
        if (plan >= 0 && plan != suffix.lastIndex) return Verdict.fail("plan block is not last")
        return Verdict.PASS
    }

    /** Estimated tokens of the stable part (system prompt and prefix) and of the whole input, for the manifest. */
    fun tokenShare(system: String, user: String): Pair<Int, Int> =
        OnDeviceTextAgentProvider.estimateTokens(system.length + prefixOf(user).length) to
            OnDeviceTextAgentProvider.estimateTokens(system.length + user.length)
}
