package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

class LiteRtLmPrefixReuseTest {
    private val roomy = OnDeviceResourceProbe { OnDeviceResourceSnapshot(availableMemoryBytes = 8L * 1024 * 1024 * 1024) }

    private val system = "SYS"
    private val roleA = "You write careful release notes. ".repeat(12)
    private val roleB = "You classify support tickets by urgency. ".repeat(12)

    private fun request(role: String, objective: String = "Summarise the notes.") = AgentTaskRequest(
        taskRunId = TaskRunId("t"),
        objective = objective,
        roleInstructions = role,
        acceptanceCriteria = listOf(AcceptanceCriterion("One sentence")),
    )

    /** Records every native-level call; the prefix path and the plain path both answer "ok". */
    private class FakeBackend : LiteRtLmPrefixBackend {
        val primed = mutableListOf<String>()
        val tails = mutableListOf<String>()
        val whole = mutableListOf<String>()
        var closedBases = 0
        var primeFails = false
        var startFails = false

        private fun answer(listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
            listener.onText("ok")
            listener.onFinished()
            return object : LiteRtLmCompletion {
                override fun cancel() = Unit

                override fun release() = Unit
            }
        }

        override fun prime(head: String): LiteRtLmPrimedPrefix {
            if (primeFails) error("prefill failed")
            primed += head
            return object : LiteRtLmPrimedPrefix {
                override fun start(tail: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
                    if (startFails) error("clone failed")
                    tails += tail
                    return answer(listener)
                }

                override fun close() {
                    closedBases++
                }
            }
        }

        override fun completeWhole(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
            whole += prompt
            return answer(listener)
        }
    }

    private class FakeModel(val reuse: LiteRtLmPrefixReuse, val backend: FakeBackend) : LiteRtLmModel {
        override fun complete(prompt: String, listener: LiteRtLmCompletionListener) = backend.completeWhole(prompt, listener)

        override fun complete(prefix: String, rest: String, listener: LiteRtLmCompletionListener) =
            reuse.complete(prefix, rest, listener)

        override fun prefixStats() = reuse.stats()

        override fun close() = reuse.close()
    }

    private class Rig(val backend: FakeBackend, val model: FakeModel, val generator: LiteRtLmTextGenerator, var adapter: String?)

    private fun rig(scope: CoroutineScope, configure: (FakeBackend) -> Unit = {}): Rig {
        val backend = FakeBackend().also(configure)
        lateinit var rig: Rig
        val reuse = LiteRtLmPrefixReuse(backend, adapterId = { rig.adapter })
        val model = FakeModel(reuse, backend)
        val loader = object : LiteRtLmEngineLoader {
            override suspend fun load(modelPath: String): LiteRtLmModel = model
        }
        val generator = LiteRtLmTextGenerator({ "/m.task" }, loader, roomy, scope, idleReleaseMillis = 1_000L)
        rig = Rig(backend, model, generator, adapter = null)
        return rig
    }

    private fun parts(role: String, planOnly: Boolean = false) =
        OnDeviceTextAgentProvider.renderTaskParts(request(role), planOnly)

    private suspend fun Rig.run(role: String, planOnly: Boolean = false): String {
        val p = parts(role, planOnly)
        return generator.generate(system, p.stable, p.dynamic, 64).text
    }

    @Test
    fun renderTaskPartsConcatenateToRenderTask() {
        listOf(false, true).forEach { planOnly ->
            listOf(roleA, "").forEach { role ->
                val p = OnDeviceTextAgentProvider.renderTaskParts(request(role), planOnly)
                assertEquals(OnDeviceTextAgentProvider.renderTask(request(role), planOnly), p.stable + p.dynamic)
                assertTrue(p.dynamic.startsWith("TASK\n"))
            }
        }
    }

    @Test
    fun theStablePartIsExactlyTheRoleBlock() {
        val p = parts(roleA)
        assertEquals("ROLE\n" + roleA.trim() + "\n\n", p.stable)
        assertTrue(p.stable.endsWith("\n\n"))
        assertEquals("", parts("").stable)
        assertEquals("", parts("   ").stable)
    }

    @Test
    fun planOnlyAndNormalPromptsOfOneRoleShareTheStablePart() {
        assertEquals(parts(roleA, planOnly = false).stable, parts(roleA, planOnly = true).stable)
        assertTrue(parts(roleA, planOnly = true).dynamic.endsWith("Do not perform it yet."))
    }

    @Test
    fun splitChatPromptEqualsTheUnsplitOneByteForByte() {
        val p = parts(roleA)
        val split = QwenChatPrompt.formatSplit(system, p.stable, p.dynamic)
        assertEquals(QwenChatPrompt.format(system, p.stable + p.dynamic), split.head + split.tail)
        assertEquals("<|im_start|>system\nSYS<|im_end|>\n<|im_start|>user\n" + p.stable, split.head)
        assertEquals(p.dynamic + "<|im_end|>\n<|im_start|>assistant\n", split.tail)
    }

    @Test
    fun theModelReceivesPrefixPlusRestEqualToTheUnsplitPrompt() = runTest {
        val r = rig(backgroundScope)
        val p = parts(roleA)
        assertEquals("ok", r.run(roleA))
        assertEquals(QwenChatPrompt.format(system, p.stable + p.dynamic), r.backend.primed.single() + r.backend.tails.single())
    }

    @Test
    fun theSameHeadTwiceReusesTheCachedPrefix() = runTest {
        val r = rig(backgroundScope)
        r.run(roleA)
        r.run(roleA)
        assertEquals(1, r.backend.primed.size)
        assertEquals(2, r.backend.tails.size)
        assertTrue(r.backend.whole.isEmpty())
        val stats = r.generator.prefixStats()!!
        assertEquals(1, stats.hits)
        assertEquals(1, stats.misses)
        assertEquals(0, stats.fallbacks)
        assertTrue(stats.prefixTokensEstimate >= LiteRtLmPrefixReuse.MIN_PREFIX_TOKENS)
    }

    @Test
    fun aDifferentRoleReprimesAndClosesTheOldBase() = runTest {
        val r = rig(backgroundScope)
        r.run(roleA)
        r.run(roleB)
        assertEquals(2, r.backend.primed.size)
        assertEquals(1, r.backend.closedBases)
        r.run(roleB)
        assertEquals(2, r.backend.primed.size)
    }

    @Test
    fun aChangedAdapterReprimesEvenForTheSameHead() = runTest {
        val r = rig(backgroundScope)
        r.run(roleA)
        r.adapter = "lora-v1"
        r.run(roleA)
        assertEquals(2, r.backend.primed.size)
        r.run(roleA)
        assertEquals(2, r.backend.primed.size)
        assertEquals(LiteRtLmPrefixKey("none", "h"), LiteRtLmPrefixKey.of(null, "h"))
        assertEquals(LiteRtLmPrefixKey("none", "h"), LiteRtLmPrefixKey.of("  ", "h"))
    }

    @Test
    fun planOnlyReusesTheNormalPromptsPrefix() = runTest {
        val r = rig(backgroundScope)
        r.run(roleA, planOnly = false)
        r.run(roleA, planOnly = true)
        assertEquals(1, r.backend.primed.size)
        assertEquals(2, r.backend.tails.size)
    }

    @Test
    fun aFailedPrimeFallsBackToTheWholePromptAndNeverReachesTheCaller() = runTest {
        val r = rig(backgroundScope) { it.primeFails = true }
        val p = parts(roleA)
        assertEquals("ok", r.run(roleA))
        assertEquals(QwenChatPrompt.format(system, p.stable + p.dynamic), r.backend.whole.single())
        assertEquals(1, r.generator.prefixStats()!!.fallbacks)
        // Given up for the process: later calls go straight to the plain path.
        r.run(roleA)
        assertEquals(2, r.backend.whole.size)
        assertEquals(1, r.generator.prefixStats()!!.fallbacks)
    }

    @Test
    fun aFailedCloneFallsBackAndFreesTheBase() = runTest {
        val r = rig(backgroundScope) { it.startFails = true }
        assertEquals("ok", r.run(roleA))
        assertEquals(1, r.backend.whole.size)
        assertEquals(1, r.backend.closedBases)
        assertEquals(0, r.generator.prefixStats()!!.hits)
        assertEquals(1, r.generator.prefixStats()!!.fallbacks)
    }

    @Test
    fun aHeadTooSmallToPayForItselfUsesThePlainPath() = runTest {
        val r = rig(backgroundScope)
        assertEquals("ok", r.run("Be brief."))
        assertTrue(r.backend.primed.isEmpty())
        assertEquals(1, r.backend.whole.size)
    }

    @Test
    fun noStablePrefixIsThePlainUnsplitPath() = runTest {
        val r = rig(backgroundScope)
        r.run("")
        assertTrue(r.backend.primed.isEmpty())
        assertEquals(QwenChatPrompt.format(system, parts("").dynamic), r.backend.whole.single())
        assertEquals("ok", r.generator.generate(system, "plain user text", 8).text)
    }

    @Test
    fun theContextGuardStillCoversTheWholePrompt() = runTest {
        val r = rig(backgroundScope)
        val huge = "x".repeat(6_000)
        assertFailsWith<OnDeviceRuntimeUnavailableException> { r.generator.generate(system, "ROLE\n$huge\n\n", "TASK\nGo", 512) }
        assertTrue(r.backend.primed.isEmpty() && r.backend.whole.isEmpty())
    }

    @Test
    fun theProviderPassesTheRoleBlockAsTheStablePrefix() = runTest {
        val seen = mutableListOf<Pair<String, String>>()
        val generator = object : OnDeviceTextGenerator {
            override fun isReady() = true

            override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
                error("the split overload must be used")
            }

            override suspend fun generate(
                system: String,
                stablePrefix: String,
                dynamic: String,
                maxNewTokens: Int,
            ): OnDeviceTextGeneration {
                seen += stablePrefix to dynamic
                return OnDeviceTextGeneration("plan")
            }
        }
        OnDeviceTextAgentProvider(generator).draftPlan(request(roleA))
        assertEquals(parts(roleA, planOnly = true).let { it.stable to it.dynamic }, seen.single())
    }

    @Test
    fun theDefaultSplitOverloadJoinsTheParts() = runTest {
        var received: String? = null
        val generator = object : OnDeviceTextGenerator {
            override fun isReady() = true

            override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
                received = user
                return OnDeviceTextGeneration("x")
            }
        }
        generator.generate("s", "AB", "CD", 8)
        assertEquals("ABCD", received)
    }
}
