package com.hereliesaz.geministrator.memory

/**
 * The one command an agent can write: `/remindme`, bare, on a line of its own. It takes no argument
 * and does one thing: the session so far is sent to memory now rather than at its end
 * ([MemorySessionObserver.onRemindMe]). Nothing is answered back.
 */
internal object MemoryRemindMeCommand {
    const val COMMAND = "/remindme"

    private val LINE = Regex("""(?m)^[ \t]*/remindme[ \t]*\r?$""")

    /** True when [text] has a line that is exactly the command (surrounding blanks allowed, nothing else). */
    fun isIn(text: String): Boolean = LINE.containsMatchIn(text)

    /** True when [text] is the command and nothing else: control syntax, not experience to bank. */
    fun isOnly(text: String): Boolean = isIn(text) && LINE.replace(text, "").isBlank()
}
