package com.hereliesaz.geministrator.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The real QuickJS-in-WebAssembly sandbox, with a fake bridge. Runs in wall-clock time. */
class QuickJsScriptSandboxTest {
    private class FakeBridge(private val waitMillis: Long = 0) : ScriptBridge {
        val calls = mutableListOf<Pair<String, String>>()
        override val toolNames = listOf("m1_echo", "m1_refused")

        override suspend fun call(toolName: String, argumentsJson: String): ScriptBridgeReply {
            calls += toolName to argumentsJson
            if (waitMillis > 0) delay(waitMillis)
            return if (toolName == "m1_refused") ScriptBridgeReply("not allowed", isError = true) else ScriptBridgeReply(argumentsJson, isError = false)
        }
    }

    private val sandbox = QuickJsScriptSandbox()

    @Test
    fun returnsTheFinalValueAsJson() = runBlocking<Unit> {
        assertEquals(ScriptRunResult.Completed("3"), sandbox.run("return 1 + 2;", ScriptLimits(), FakeBridge()))
        assertEquals(ScriptRunResult.Completed("\"hi\""), sandbox.run("return 'hi';", ScriptLimits(), FakeBridge()))
        assertEquals(ScriptRunResult.Completed("null"), sandbox.run("const x = 1;", ScriptLimits(), FakeBridge()))
    }

    @Test
    fun toolsAreSynchronousFunctionsThroughTheBridge() = runBlocking<Unit> {
        val bridge = FakeBridge()
        val result = sandbox.run(
            """
            const echoed = JSON.parse(tools.m1_echo({ n: 41 }));
            let refused = "no";
            try { tools.m1_refused({}); } catch (e) { refused = e.message; }
            return { n: echoed.n + 1, refused: refused, names: Object.keys(tools) };
            """.trimIndent(),
            ScriptLimits(),
            bridge,
        )
        assertEquals(ScriptRunResult.Completed("""{"n":42,"refused":"not allowed","names":["m1_echo","m1_refused"]}"""), result)
        assertEquals(listOf("m1_echo" to """{"n":41}""", "m1_refused" to "{}"), bridge.calls)
    }

    @Test
    fun theGuestHasNoNetworkFilesOrProcess() = runBlocking<Unit> {
        val result = sandbox.run(
            "return [typeof fetch, typeof XMLHttpRequest, typeof require, typeof std, typeof os, typeof process].join(',');",
            ScriptLimits(),
            FakeBridge(),
        )
        assertEquals(ScriptRunResult.Completed("\"undefined,undefined,undefined,undefined,undefined,undefined\""), result)
    }

    @Test
    fun aRunawayScriptIsStoppedByTheGuestTimeLimit() = runBlocking<Unit> {
        val result = sandbox.run("while (true) {}", ScriptLimits(guestTimeMillis = 2_000), FakeBridge())
        val failed = assertIs<ScriptRunResult.Failed>(result)
        assertTrue(failed.limitExceeded, failed.reason)
    }

    @Test
    fun waitingInTheBridgeDoesNotCountAsGuestTime() = runBlocking<Unit> {
        val result = sandbox.run("tools.m1_echo({}); return 'ok';", ScriptLimits(guestTimeMillis = 5_000), FakeBridge(waitMillis = 6_000))
        assertEquals(ScriptRunResult.Completed("\"ok\""), result)
    }

    @Test
    fun memoryIsCapped() = runBlocking<Unit> {
        val result = sandbox.run(
            "const keep = []; while (true) keep.push('x'.repeat(1 << 20) + keep.length);",
            ScriptLimits(memoryBytes = 16L * 1024 * 1024, guestTimeMillis = 20_000),
            FakeBridge(),
        )
        assertIs<ScriptRunResult.Failed>(result)
    }

    @Test
    fun aScriptErrorIsAFailureWithItsMessage() = runBlocking<Unit> {
        val result = sandbox.run("throw new Error('boom');", ScriptLimits(), FakeBridge())
        val failed = assertIs<ScriptRunResult.Failed>(result)
        assertTrue((failed.reason + failed.log).contains("boom"), failed.reason + failed.log)
    }
}
