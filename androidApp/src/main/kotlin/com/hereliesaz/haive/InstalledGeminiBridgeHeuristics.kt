package com.hereliesaz.aive

/** Version-tolerant matching for the installed Gemini UI. */
internal object InstalledGeminiBridgeHeuristics {
    private val inputHints = listOf(
        "ask gemini", "enter a prompt", "message", "prompt", "ask",
    )
    private val sendHints = listOf("send", "submit", "run")
    private val copyHints = listOf("copy")
    private val generatingHints = listOf(
        "stop response", "stop generating", "cancel response", "stop generation",
    )
    private val chromeStrings = listOf(
        "Enter a prompt here", "Listening", "Tap to talk", "Ask Gemini",
    )

    fun extractResponse(snapshot: String, prompt: String): String {
        var text = snapshot
        if (prompt.isNotBlank()) text = text.replace(prompt, "").trim()
        chromeStrings.forEach { chrome -> text = text.replace(chrome, "", ignoreCase = true) }
        return text.trim()
    }

    fun isInputHint(hint: String?): Boolean = matches(hint, inputHints)
    fun isSendHint(hint: String?): Boolean = matches(hint, sendHints)
    fun isCopyHint(hint: String?): Boolean = matches(hint, copyHints)
    fun isGeneratingHint(hint: String?): Boolean = matches(hint, generatingHints)

    private fun matches(hint: String?, needles: List<String>): Boolean {
        val normalized = hint?.lowercase() ?: return false
        return needles.any(normalized::contains)
    }
}
