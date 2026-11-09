package com.hereliesaz.geministrator.providers.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliOutputTest {
    @Test
    fun claudeStreamJsonYieldsThoughtsToolUseUsageAndItsOwnFinalAnswer() {
        val r = CliOutputReader(CliOutputFormat.ClaudeStreamJson)
        val events = listOf(
            """{"type":"system","subtype":"init","session_id":"s"}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"I'll fix it."},{"type":"tool_use","name":"Edit","input":{"file_path":"src/a.kt"}}]}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"Fixed a.kt.","total_cost_usd":0.012,"usage":{"input_tokens":120,"output_tokens":40}}""",
        ).flatMap(r::read)

        assertEquals(CliEvent.Progress("Claude Code started"), events[0])
        assertEquals(CliEvent.Thinking("I'll fix it."), events[1])
        assertEquals(CliEvent.Progress("Using Edit: src/a.kt"), events[2])
        assertEquals(CliEvent.Usage(120, 40, 0.012), events[3])
        assertEquals(CliEvent.Final("Fixed a.kt.", isError = false), r.finish(0))
    }

    @Test
    fun aClaudeResultThatSaysItFailedIsAFailureEvenWithExitCodeZero() {
        val r = CliOutputReader(CliOutputFormat.ClaudeStreamJson)
        r.read("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":"Ran out of turns."}""")
        assertEquals(CliEvent.Final("Ran out of turns.", isError = true), r.finish(0))
    }

    @Test
    fun codexJsonlReadsItsAgentMessageAndUsageInEitherEventShape() {
        val r = CliOutputReader(CliOutputFormat.CodexJsonl)
        val a = r.read("""{"type":"item.completed","item":{"type":"agent_message","text":"Done: renamed the function."}}""")
        val b = r.read("""{"msg":{"type":"agent_message","message":"Older shape."}}""")
        val c = r.read("""{"type":"turn.completed","usage":{"input_tokens":10,"output_tokens":3}}""")

        assertEquals(listOf(CliEvent.Thinking("Done: renamed the function.")), a)
        assertEquals(listOf(CliEvent.Thinking("Older shape.")), b)
        assertEquals(listOf(CliEvent.Usage(10, 3, null)), c)
        assertEquals(CliEvent.Final("Older shape.", isError = false), r.finish(0), "the last thing it said")
        assertTrue(r.finish(2).isError)
    }

    @Test
    fun aLineItDoesNotRecogniseIsProgressAndGarbageNeverThrows() {
        val claude = CliOutputReader(CliOutputFormat.ClaudeStreamJson)
        assertEquals(listOf(CliEvent.Progress("not json at all")), claude.read("not json at all"))
        assertEquals(listOf(CliEvent.Progress("{broken")), claude.read("{broken"))
        assertEquals(emptyList(), claude.read("   "))
        val codex = CliOutputReader(CliOutputFormat.CodexJsonl)
        assertIs<CliEvent.Progress>(codex.read("""{"type":"something.new"}""").single())
    }

    @Test
    fun plainTextIsProgressLineByLineAndTheWholeIsTheAnswer() {
        val r = CliOutputReader(CliOutputFormat.PlainText)
        assertEquals(listOf(CliEvent.Progress("Editing a.kt")), r.read("Editing a.kt"))
        r.read("All done.")
        assertEquals(CliEvent.Final("Editing a.kt\nAll done.", isError = false), r.finish(0))
        assertTrue(r.finish(1).isError)
    }
}
