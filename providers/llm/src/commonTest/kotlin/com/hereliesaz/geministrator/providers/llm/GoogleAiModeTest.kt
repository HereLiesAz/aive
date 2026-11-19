package com.hereliesaz.geministrator.providers.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleAiModeTest {
    private val question = "what is a laryngectomy"
    private val finishedPage = """
        AI Mode
        All
        Images
        what is a laryngectomy
        A laryngectomy is a surgical procedure to remove part or all of the larynx.

        Types of Laryngectomy
        Total and partial.
        This is for informational purposes only. For medical advice or diagnosis, consult a professional. AI responses may include mistakes. Learn more
        Sponsored
        You might consider these options:
        Ask anything
    """.trimIndent()

    @Test
    fun theQuestionIsPercentEncodedAndNothingElseIsSent() {
        assertEquals("https://www.google.com/search?udm=50&hl=en&q=what%20is%20a%20larynx%3F%20%C3%A9", GoogleAiMode.url(" what is a larynx? é "))
    }

    @Test
    fun theAnswerIsWhatFollowsTheQuestionUpToTheDisclaimer() {
        val answer = assertNotNull(GoogleAiMode.answer(finishedPage, question))
        assertTrue(answer.startsWith("A laryngectomy is a surgical procedure"))
        assertTrue("Total and partial." in answer)
        assertFalse("AI responses" in answer || "Sponsored" in answer || "Images" in answer || "informational purposes" in answer)
        assertTrue(GoogleAiMode.finished(finishedPage, question))
    }

    @Test
    fun anAnswerStillWritingIsNotFinished() {
        val partial = "AI Mode\nwhat is a laryngectomy\nA laryngectomy is a surg"
        assertFalse(GoogleAiMode.finished(partial, question))
        assertEquals("A laryngectomy is a surg", GoogleAiMode.answer(partial, question))
    }

    @Test
    fun aPageWithoutTheQuestionHasNoAnswerYet() {
        assertNull(GoogleAiMode.answer("AI Mode\nAll\nImages", question))
    }

    @Test
    fun aHumanCheckOrConsentWallIsReportedPlainly() {
        assertNotNull(GoogleAiMode.refusal("Our systems have detected unusual traffic from your computer network."))
        assertNotNull(GoogleAiMode.refusal("Before you continue to Google Search, cookies and consent"))
        assertNull(GoogleAiMode.refusal(finishedPage))
    }

    private fun api(pages: List<String>, clock: LongArray = longArrayOf(0L)): GoogleAiModeApi {
        var i = 0
        val browser = object : AiModeBrowser {
            override suspend fun <T> withPage(url: String, read: suspend (AiModePage) -> T): T = read(
                object : AiModePage {
                    override suspend fun text(): String = pages[minOf(i++, pages.lastIndex)]
                },
            )
        }
        return GoogleAiModeApi(browser, now = { clock[0] }, pause = { clock[0] += it })
    }

    @Test
    fun theApiWaitsForTheFinishedAnswerAndReturnsOnlyIt() = runTest {
        val partial = "what is a laryngectomy\nA laryngectomy is"
        val result = api(listOf("AI Mode", partial, finishedPage)).generate(question)
        assertTrue(result.text.startsWith("A laryngectomy is a surgical"))
        assertFalse("AI responses" in result.text)
    }

    @Test
    fun aRefusalBecomesAnUnavailableErrorWithWordsForTheKeeper() = runTest {
        val e = assertFailsWith<GoogleAiModeUnavailable> { api(listOf("unusual traffic")).generate(question) }
        assertTrue("human check" in e.message.orEmpty())
    }

    @Test
    fun noAnswerInTimeIsAnErrorNotAnEmptyReply() = runTest {
        assertFailsWith<GoogleAiModeUnavailable> { api(listOf("AI Mode")).generate(question) }
    }

    @Test
    fun itNeverBillsAndNamesItself() {
        val api = api(listOf(""))
        assertFalse(api.billsPerToken)
        assertEquals("google-ai-mode", api.modelId)
    }

    private val noBrowser = object : AiModeBrowser {
        override suspend fun <T> withPage(url: String, read: suspend (AiModePage) -> T): T = error("not used")
    }

    @Test
    fun theSourceExistsOnlyWhereThePlatformProvidesABrowserView() {
        GoogleAiModeHost.browser = null
        try {
            assertNull(HostedLlmProviders.entries.firstOrNull { it.id == GoogleAiMode.ID })
            GoogleAiModeHost.browser = noBrowser
            val spec = assertNotNull(HostedLlmProviders.entry(GoogleAiMode.ID))
            assertTrue(spec.keyOptional)
            assertTrue(HostedLlmProviders.GOOGLE_AI_MODE_PROTOCOL in spec.protocols)
            // A provider is built from it, but it is never a planner's raw chat API.
            assertEquals(GoogleAiMode.ID, HostedLlmProviders.create(spec, "anonymous").id.value)
            assertFailsWith<IllegalArgumentException> { HostedLlmProviders.textApi(spec, "anonymous") }
            // And it is never among the built-in entries a first launch connects by itself.
            assertFalse(HostedLlmProviders.builtInEntries.any { it.id == GoogleAiMode.ID })
        } finally {
            GoogleAiModeHost.browser = null
        }
    }
}
