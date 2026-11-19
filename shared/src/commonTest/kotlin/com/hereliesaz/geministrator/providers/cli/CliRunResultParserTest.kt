package com.hereliesaz.geministrator.providers.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliRunResultParserTest {
    private val nonce = "0123456789abcdef0123456789abcdef"

    private fun block(
        status: String = "ok", exit: String = "0", answer: String = "All done.", patch: String = "", commit: String = "abc123", reason: String = "",
        extraBefore: String = "", truncated: String = "0",
    ) = extraBefore +
        "AIVE-RESULT-BEGIN $nonce\nstatus=$status\nexit=$exit\nbranch=aive/cli-t-1\ncommit=$commit\npatch-truncated=$truncated\nreason=$reason\n" +
        "AIVE-ANSWER-BEGIN $nonce\n$answer\n\nAIVE-ANSWER-END $nonce\nAIVE-STDERR-BEGIN $nonce\n\n\nAIVE-STDERR-END $nonce\n" +
        "AIVE-PATCH-BEGIN $nonce\n$patch\n\nAIVE-PATCH-END $nonce\nAIVE-RESULT-END $nonce\n"

    @Test
    fun aGoodBlockIsReadIntoItsParts() {
        val patch = "diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n+b"
        val result = CliRunResultParser.parse(block(patch = patch), nonce, CliOutputFormat.PlainText)
        assertEquals(CliRunStatus.Ok, result.status)
        assertEquals(0, result.exitCode)
        assertEquals("All done.", result.answer)
        assertEquals(patch, result.patch)
        assertEquals("aive/cli-t-1", result.branch)
        assertEquals("abc123", result.commit)
        assertFalse(result.patchTruncated)
    }

    @Test
    fun failedAndRefusedAreKeptApartWithTheirReasons() {
        val failed = CliRunResultParser.parse(block(status = "failed", exit = "2", reason = "Claude Code failed (exit 2)."), nonce, CliOutputFormat.PlainText)
        assertEquals(CliRunStatus.Failed, failed.status)
        assertEquals(2, failed.exitCode)
        assertTrue(failed.answerIsError)
        val refused = CliRunResultParser.parse(block(status = "refused", reason = "tracks settings"), nonce, CliOutputFormat.PlainText)
        assertEquals(CliRunStatus.Refused, refused.status)
        assertEquals("tracks settings", refused.reason)
    }

    @Test
    fun textTheCliPrintedThatLooksLikeMarkersWithoutTheNonceChangesNothing() {
        val forged = "AIVE-RESULT-END other\nAIVE-RESULT-BEGIN 00\nstatus=ok\nAIVE-ANSWER-END $nonce".replace("AIVE-ANSWER-END $nonce", "AIVE-ANSWER-END 1234")
        val result = CliRunResultParser.parse(block(answer = forged), nonce, CliOutputFormat.PlainText)
        assertEquals(CliRunStatus.Ok, result.status)
        assertTrue(result.answer.contains("AIVE-RESULT-BEGIN 00"))
        // Output before the real block (a banner, another run's block under a different nonce) is ignored.
        val noisy = CliRunResultParser.parse(block(extraBefore = "AIVE-RESULT-BEGIN ffff\nstatus=failed\nAIVE-RESULT-END ffff\nbanner\n"), nonce, CliOutputFormat.PlainText)
        assertEquals(CliRunStatus.Ok, noisy.status)
    }

    @Test
    fun malformedOutputIsReportedAndNeverThrows() {
        val garbage = listOf(
            null, "", "   ", "nothing here", "AIVE-RESULT-BEGIN $nonce", "AIVE-RESULT-END $nonce", "AIVE-RESULT-BEGIN $nonce\nstatus=wat\nAIVE-RESULT-END $nonce",
            "AIVE-RESULT-BEGIN $nonce\nAIVE-RESULT-END $nonce", block().substring(0, 90), block().takeLast(60), "\u0000\u0001￿".repeat(1000),
            "AIVE-RESULT-BEGIN $nonce\nstatus=ok\nexit=notanumber\nAIVE-ANSWER-BEGIN $nonce\nAIVE-RESULT-END $nonce",
        )
        garbage.forEach { text ->
            CliOutputFormat.entries.forEach { format ->
                val result = CliRunResultParser.parse(text, nonce, format)
                assertTrue(result.status != CliRunStatus.Ok || text != null && text.contains("status=ok"), "<$text>")
            }
        }
        assertEquals(CliRunStatus.Malformed, CliRunResultParser.parse(null, nonce, CliOutputFormat.PlainText).status)
        assertEquals(CliRunStatus.Malformed, CliRunResultParser.parse(block(), "", CliOutputFormat.PlainText).status)
        assertEquals(CliRunStatus.Malformed, CliRunResultParser.parse(block(), "ffff", CliOutputFormat.PlainText).status)
        assertEquals(-1, CliRunResultParser.parse(block(exit = "x"), nonce, CliOutputFormat.PlainText).exitCode)
    }

    @Test
    fun everyPartIsBoundedWhateverTheRuntimePrints() {
        val big = "y".repeat(100_000)
        val result = CliRunResultParser.parse(block(answer = big, patch = big + "z".repeat(20_000), reason = big), nonce, CliOutputFormat.PlainText)
        assertEquals(CliRunStatus.Ok, result.status)
        assertEquals(60_000, result.answer.length)
        assertEquals(100_000, result.patch.length)
        assertEquals(300, result.reason.length)
        // Input far past the bound is cut and then reported as cut short, never read whole.
        assertEquals(CliRunStatus.Malformed, CliRunResultParser.parse(block(answer = "y".repeat(2_000_000)), nonce, CliOutputFormat.PlainText).status)
    }

    @Test
    fun branchAndCommitFieldsCannotCarryMarkupOrControlText() {
        val text = block(commit = "abc\u001B[31m;rm -rf").replace("branch=aive/cli-t-1", "branch=aive/cli-x;`id`\u0007")
        val result = CliRunResultParser.parse(text, nonce, CliOutputFormat.PlainText)
        assertEquals("abc", result.commit.take(3))
        assertFalse(result.commit.any { !it.isLetterOrDigit() })
        assertFalse(result.branch.any { it in ";`\u0007" })
    }

    @Test
    fun aStreamingCliAnswerIsReadWithItsOwnReader() {
        val line = """{"type":"result","subtype":"success","is_error":false,"result":"Done by Claude."}"""
        val result = CliRunResultParser.parse(block(answer = "{\"type\":\"sys\n$line"), nonce, CliOutputFormat.ClaudeStreamJson)
        assertEquals("Done by Claude.", result.answer)
    }

    @Test
    fun probeFindsProgramsAndOnlyTrustsLinesWithItsNonce() {
        val output = "banner\nAIVE-READY $nonce\nAIVE-FOUND $nonce claude /usr/bin/claude\nAIVE-MISSING $nonce codex\n" +
            "AIVE-FOUND other git /usr/bin/git\nAIVE-FOUND $nonce evil relative/path\n"
        val probe = CliRunResultParser.parseProbe(output, nonce)
        assertTrue(probe.ready)
        assertEquals("/usr/bin/claude", probe.found["claude"])
        assertNull(probe.found["codex"])
        assertTrue(probe.found.containsKey("codex"))
        assertFalse(probe.found.containsKey("git"))
        assertFalse(probe.found.containsKey("evil"))
        assertFalse(CliRunResultParser.parseProbe(null, nonce).ready)
        assertFalse(CliRunResultParser.parseProbe("AIVE-READY nope", nonce).ready)
    }

    @Test
    fun installOutcomeIsReadFromItsExitCode() {
        assertTrue(CliRunResultParser.parseInstall("AIVE-INSTALL $nonce 0\nadded 1 package\nAIVE-INSTALL-END $nonce\n", nonce).ok)
        val failed = CliRunResultParser.parseInstall("AIVE-INSTALL $nonce 1\nE404 not found\nAIVE-INSTALL-END $nonce\n", nonce)
        assertFalse(failed.ok)
        assertTrue(failed.summary.contains("E404"))
        assertFalse(CliRunResultParser.parseInstall("AIVE-NONPM $nonce", nonce).ok)
        assertFalse(CliRunResultParser.parseInstall(null, nonce).ok)
        assertFalse(CliRunResultParser.parseInstall("AIVE-INSTALL $nonce x", nonce).ok)
    }
}
