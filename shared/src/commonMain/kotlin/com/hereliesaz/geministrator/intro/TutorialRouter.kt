package com.hereliesaz.geministrator.intro

/** How much of the app a tutorial goal asks for. Decides only the sanity bounds on the plan's length. */
enum class TutorialScope { Broad, Focused }

/**
 * Decides whether a goal the keeper fed is a request to be taught the app itself. Pure and conservative: a goal must
 * ask to be taught or shown AND name the app or one of its own words, so a coding goal such as "explain the login code"
 * is never routed here. It reads only the goal's words; it never reads anything else on the device.
 */
object TutorialRouter {
    private val asksToBeTaught = Regex("""(?i)^\s*(?:please\s+)?(?:teach|show|walk|guide|train)\s+me\b""")
    private val appWords = Regex(
        """(?i)\b(aive|this app|the app|hives?|swarms?|queen|keeper|creatures?|messages|story|sources?|nanny|orphans?|roster|memory|breeds?)\b""",
    )
    private val wholeApp = Regex("""(?i)\b(?:use|using|the aive|this app|the app|ins and outs|everything|basics|get started)\b""")

    /** The scope of a tutorial goal, or null when [goal] is not one. */
    fun classify(goal: String): TutorialScope? {
        if (!asksToBeTaught.containsMatchIn(goal)) return null
        if (!appWords.containsMatchIn(goal)) return null
        val broad = wholeApp.containsMatchIn(goal) && !Regex("""(?i)\b(?:how|what)\b.*\b(?:work|works|is|are|means?)\b""").containsMatchIn(goal)
        return if (broad) TutorialScope.Broad else TutorialScope.Focused
    }

    fun isTutorialGoal(goal: String): Boolean = classify(goal) != null
}
