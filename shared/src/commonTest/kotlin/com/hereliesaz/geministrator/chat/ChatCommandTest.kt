package com.hereliesaz.geministrator.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatCommandTest {
    @Test
    fun theCommandsParseExactly() {
        assertEquals(ChatCommand.Status, ChatCommandParser.parse("/status"))
        assertEquals(ChatCommand.Hives, ChatCommandParser.parse("/hives"))
        assertEquals(ChatCommand.Runs, ChatCommandParser.parse("/runs"))
        assertEquals(ChatCommand.Help, ChatCommandParser.parse("/help"))
        assertEquals(ChatCommand.Stop, ChatCommandParser.parse("/stop"))
        assertEquals(ChatCommand.StopConfirm, ChatCommandParser.parse("/stop confirm"))
        assertEquals(ChatCommand.Run("hive-a", "write the docs"), ChatCommandParser.parse("/run hive-a write the docs"))
        // Telegram may append the bot's name; case of the command word does not matter.
        assertEquals(ChatCommand.Status, ChatCommandParser.parse("/Status@SomeBot"))
    }

    @Test
    fun pairingMessages() {
        assertEquals(ChatCommand.PairRequest, ChatCommandParser.parse("/pair"))
        assertEquals(ChatCommand.PairCode("123456"), ChatCommandParser.parse("123456"))
        assertEquals(ChatCommand.PairCode("123456"), ChatCommandParser.parse("/pair 123456"))
        assertIs<ChatCommand.Unknown>(ChatCommandParser.parse("1234567"))
        assertIs<ChatCommand.Unknown>(ChatCommandParser.parse("/pair 12ab56"))
    }

    @Test
    fun malformedOrExtraArgumentsAreRefused() {
        listOf(
            "", "   ", "status", "/status now", "/hives all", "/stop now", "/stop confirm yes", "/run", "/run hive-a",
            "/run hive-a    ", "/run bad;hive do it", "/nope", "/", "//status",
        ).forEach { assertIs<ChatCommand.Unknown>(ChatCommandParser.parse(it), "should refuse: '$it'") }
    }

    @Test
    fun noApprovalShapedMessageIsACommand() {
        // THREAT_MODEL invariant 8: nothing from chat approves, rejects or releases a gate.
        listOf(
            "/approve", "/approve gate-1", "/reject", "/release", "/approve_all", "/yes", "/ok", "/allow", "/deny",
            "approve", "yes approve it", "/decide approve", "/gate approve", "/run hive-a approve everything",
        ).forEach { text ->
            val parsed = ChatCommandParser.parse(text)
            assertTrue(
                parsed is ChatCommand.Unknown || parsed is ChatCommand.Run,
                "'$text' must not parse to anything but Unknown (or a plain run objective), was $parsed",
            )
        }
        // The closed set has no member that could mean an approval.
        val names = listOf(ChatCommand.Help, ChatCommand.Status, ChatCommand.Hives, ChatCommand.Runs, ChatCommand.Stop, ChatCommand.StopConfirm, ChatCommand.PairRequest)
            .map { it::class.simpleName.orEmpty().lowercase() }
        assertTrue(names.none { "approv" in it || "reject" in it || "releas" in it })
    }

    @Test
    fun lengthsAreBoundedAndControlsRemoved() {
        assertIs<ChatCommand.Unknown>(ChatCommandParser.parse("/run hive-a " + "x".repeat(ChatCommandParser.MAX_OBJECTIVE_CHARS + 1)))
        assertIs<ChatCommand.Unknown>(ChatCommandParser.parse("/status" + " ".repeat(ChatCommandParser.MAX_INPUT_CHARS)))
        val run = assertIs<ChatCommand.Run>(ChatCommandParser.parse("/run hive-a do\u0000 it‮ now\nplease"))
        assertEquals("do it now please", run.objective)
    }

    @Test
    fun theUnknownReasonNeverEchoesTheText() {
        val unknown = assertIs<ChatCommand.Unknown>(ChatCommandParser.parse("/leak sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue("sk-" !in unknown.reason)
    }
}
