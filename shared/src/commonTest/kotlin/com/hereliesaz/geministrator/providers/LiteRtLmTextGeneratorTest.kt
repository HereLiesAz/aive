package com.hereliesaz.geministrator.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LiteRtLmTextGeneratorTest {
    private val roomy = OnDeviceResourceProbe { OnDeviceResourceSnapshot(availableMemoryBytes = 8L * 1024 * 1024 * 1024) }

    private class FakeModel(private val script: (LiteRtLmCompletionListener) -> Unit) : LiteRtLmModel {
        var closed = false
        var prompts = mutableListOf<String>()
        var cancelled = false
        var released = false

        override fun complete(prompt: String, listener: LiteRtLmCompletionListener): LiteRtLmCompletion {
            prompts += prompt
            script(listener)
            return object : LiteRtLmCompletion {
                override fun cancel() {
                    cancelled = true
                }

                override fun release() {
                    released = true
                }
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeLoader(val model: FakeModel?, val failure: Throwable? = null) : LiteRtLmEngineLoader {
        var loads = 0

        override suspend fun load(modelPath: String): LiteRtLmModel {
            loads++
            failure?.let { throw it }
            return model!!
        }
    }

    private fun TestScopeHolder.generator(
        loader: FakeLoader,
        resources: OnDeviceResourceProbe = roomy,
        path: String? = "/m.task",
        timeout: Long = 10_000L,
    ) = LiteRtLmTextGenerator({ path }, loader, resources, scope, timeoutMillis = timeout, idleReleaseMillis = 1_000L)

    private class TestScopeHolder(val scope: kotlinx.coroutines.CoroutineScope)

    @Test
    fun generatesAndSanitisesTheAnswer() = runTest {
        val model = FakeModel { l ->
            l.onText(" Hello")
            l.onText(" world<|im_end|>")
            l.onFinished()
        }
        val g = TestScopeHolder(backgroundScope).generator(FakeLoader(model))
        val out = g.generate("sys", "usr", 64)
        assertEquals("Hello world", out.text)
        assertTrue(model.prompts.single().startsWith("<|im_start|>system\nsys<|im_end|>"))
        assertTrue(model.released)
    }

    @Test
    fun aRawPromptRunsAsIsAndStopsAtItsMarker() = runTest {
        val model = FakeModel { l ->
            l.onText("<think>\nhm")
            l.onText("</think>\nHi<|im_end|>")
            l.onText("never")
            l.onFailed("cancelled")
        }
        val g = TestScopeHolder(backgroundScope).generator(FakeLoader(model))
        val out = g.generateRaw("<|im_start|>user\nhi<|im_end|>\n", 64, 2048, listOf("<|im_end|>"))
        assertEquals("<think>\nhm</think>\nHi<|im_end|>", out)
        assertEquals("<|im_start|>user\nhi<|im_end|>\n", model.prompts.single())
        assertTrue(model.cancelled)
    }

    @Test
    fun aRawPromptThatCannotFitItsContextIsRefused() = runTest {
        val g = TestScopeHolder(backgroundScope).generator(FakeLoader(FakeModel { it.onFinished() }))
        assertFailsWith<OnDeviceRuntimeUnavailableException> { g.generateRaw("x".repeat(10_000), 64, 512) }
    }

    @Test
    fun stopsAtTheAnswerLengthLimit() = runTest {
        val model = FakeModel { l ->
            repeat(10) { l.onText("a") }
            l.onFailed("cancelled")
        }
        val out = TestScopeHolder(backgroundScope).generator(FakeLoader(model)).generate("s", "u", 3)
        assertEquals("aaa", out.text)
        assertTrue(model.cancelled)
    }

    @Test
    fun aFailureThatWeDidNotAskForIsAnError() = runTest {
        val model = FakeModel { l -> l.onFailed("boom") }
        val g = TestScopeHolder(backgroundScope).generator(FakeLoader(model))
        assertFailsWith<IllegalStateException> { g.generate("s", "u", 8) }
    }

    @Test
    fun aGenerationThatNeverEndsTimesOutAndIsCancelled() = runTest {
        val model = FakeModel { }
        val g = TestScopeHolder(backgroundScope).generator(FakeLoader(model), timeout = 500L)
        assertFailsWith<OnDeviceGenerationTimedOut> { g.generate("s", "u", 8) }
        assertTrue(model.cancelled)
    }

    @Test
    fun lowMemoryRefusesToLoad() = runTest {
        val loader = FakeLoader(FakeModel { it.onFinished() })
        val tight = OnDeviceResourceProbe { OnDeviceResourceSnapshot(availableMemoryBytes = 100L * 1024 * 1024) }
        val g = TestScopeHolder(backgroundScope).generator(loader, resources = tight)
        assertFailsWith<OnDeviceGenerationDeclined> { g.generate("s", "u", 8) }
        assertEquals(0, loader.loads)
    }

    @Test
    fun aLoadFailureMarksItNotReadyUntilReleased() = runTest {
        val loader = FakeLoader(null, OnDeviceRuntimeUnavailableException("no native"))
        val g = TestScopeHolder(backgroundScope).generator(loader)
        assertTrue(g.isReady())
        assertFailsWith<OnDeviceRuntimeUnavailableException> { g.generate("s", "u", 8) }
        assertFalse(g.isReady())
        g.release()
        assertTrue(g.isReady())
    }

    @Test
    fun noModelMeansNotReady() = runTest {
        val g = TestScopeHolder(backgroundScope).generator(FakeLoader(null), path = null)
        assertFalse(g.isReady())
    }

    @Test
    fun aTaskTooLongForTheContextIsUnavailableNotLoaded() = runTest {
        val loader = FakeLoader(FakeModel { it.onFinished() })
        val g = TestScopeHolder(backgroundScope).generator(loader)
        assertFailsWith<OnDeviceRuntimeUnavailableException> { g.generate("s", "x".repeat(6_000), 512) }
        assertEquals(0, loader.loads)
    }

    @Test
    fun theEngineIsKeptThenReleasedWhenIdle() = runTest {
        val model = FakeModel { l -> l.onText("ok"); l.onFinished() }
        val loader = FakeLoader(model)
        val g = TestScopeHolder(backgroundScope).generator(loader)
        g.generate("s", "u", 8)
        g.generate("s", "u", 8)
        assertEquals(1, loader.loads)
        assertFalse(model.closed)
        advanceTimeBy(1_500L)
        runCurrent()
        assertTrue(model.closed)
    }

    @Test
    fun sanitiserCutsAtEndOfTurnAndStripsMarkers() {
        assertEquals("a b", sanitizeOnDeviceText("  a b <|im_end|>junk"))
        assertEquals("x", sanitizeOnDeviceText("<|im_start|>x<|endoftext|>"))
    }

    @Test
    fun chatTemplateMatchesTheQwenFormat() {
        assertEquals(
            "<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n",
            QwenChatPrompt.format("S", "U"),
        )
    }
}
