package com.hereliesaz.geministrator.chat

/**
 * The closed set of things chat can ask. There is deliberately no member that approves, rejects or releases
 * anything: an approval-shaped message parses to [Unknown] (ChatCommandTest enumerates them).
 */
sealed interface ChatCommand {
    data object Help : ChatCommand
    data object Status : ChatCommand
    data object Hives : ChatCommand
    data object Runs : ChatCommand
    data class Run(val hive: String, val objective: String) : ChatCommand
    data object Stop : ChatCommand
    data object StopConfirm : ChatCommand

    /** `/pair`: asks for a one-time code to be shown in the app. */
    data object PairRequest : ChatCommand

    /** The six digits the keeper read in the app, alone or after `/pair`. */
    data class PairCode(val code: String) : ChatCommand

    /** Anything else. [reason] is a fixed phrase, never the sender's text. */
    data class Unknown(val reason: String) : ChatCommand
}

/** Strict, total parser for chat text. It never throws and never echoes what it was given. */
object ChatCommandParser {
    const val MAX_INPUT_CHARS = 2_000
    const val MAX_OBJECTIVE_CHARS = 1_500
    private const val MAX_HIVE_CHARS = 80
    private val hiveToken = Regex("^[A-Za-z0-9._:\\-]{1,$MAX_HIVE_CHARS}$")
    private val digits6 = Regex("^[0-9]{6}$")

    fun parse(raw: String): ChatCommand {
        if (raw.length > MAX_INPUT_CHARS) return ChatCommand.Unknown("too long")
        val text = ChatText.stripControls(raw, keepNewlines = false).trim()
        if (text.isEmpty()) return ChatCommand.Unknown("empty")
        if (digits6.matches(text)) return ChatCommand.PairCode(text)
        if (!text.startsWith("/")) return ChatCommand.Unknown("not a command")
        val parts = text.split(' ').filter { it.isNotEmpty() }
        // Telegram appends the bot's name in groups and sometimes in private chats: "/status@SomeBot".
        val word = parts.first().substring(1).substringBefore('@').lowercase()
        val args = parts.drop(1)
        return when (word) {
            "help", "start" -> if (args.isEmpty()) ChatCommand.Help else ChatCommand.Unknown("no arguments")
            "status" -> if (args.isEmpty()) ChatCommand.Status else ChatCommand.Unknown("no arguments")
            "hives" -> if (args.isEmpty()) ChatCommand.Hives else ChatCommand.Unknown("no arguments")
            "runs" -> if (args.isEmpty()) ChatCommand.Runs else ChatCommand.Unknown("no arguments")
            "stop" -> when {
                args.isEmpty() -> ChatCommand.Stop
                args.size == 1 && args[0].lowercase() == "confirm" -> ChatCommand.StopConfirm
                else -> ChatCommand.Unknown("use /stop, then /stop confirm")
            }
            "pair" -> when {
                args.isEmpty() -> ChatCommand.PairRequest
                args.size == 1 && digits6.matches(args[0]) -> ChatCommand.PairCode(args[0])
                else -> ChatCommand.Unknown("use /pair")
            }
            "run" -> run {
                val hive = args.firstOrNull()
                if (hive == null || !hiveToken.matches(hive)) return@run ChatCommand.Unknown("use /run hive objective")
                val objective = args.drop(1).joinToString(" ").trim()
                when {
                    objective.isEmpty() -> ChatCommand.Unknown("use /run hive objective")
                    objective.length > MAX_OBJECTIVE_CHARS -> ChatCommand.Unknown("objective too long")
                    else -> ChatCommand.Run(hive, objective)
                }
            }
            else -> ChatCommand.Unknown("unknown command")
        }
    }
}
