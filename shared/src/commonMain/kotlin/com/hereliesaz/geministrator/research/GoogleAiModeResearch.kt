package com.hereliesaz.geministrator.research

import kotlinx.coroutines.CancellationException

/** Asks one question of Gemini in Google Search and returns its answer text (providers/llm GoogleAiMode); set by a platform that has a browser view. */
fun interface QuestionAnswerer {
    suspend fun ask(question: String): String
}

/**
 * Gemini in Google Search as one of the queen's outside lookups before she plans. It is asked only when the keeper has added Google
 * AI Mode as a Source and left it switched on, and only after the keeper has read the exact question (the consent gate shows
 * [question]). Its answer is one untrusted lead, never a gate and never connected.
 */
object GoogleAiModeResearch {
    const val SERVER_ID = "research-google-ai-mode"
    const val LOCATION = "https://www.google.com/search?udm=50"

    /** The platform's way to ask; null where there is no browser view. */
    var answerer: QuestionAnswerer? = null

    /** The whole question that is sent, built from the few generic words only: what the keeper sees is what Google gets. */
    fun question(query: String): String = "In two sentences: what tools, documentation or services could help with: $query?"
}

class GoogleAiModeLookup(private val answerer: QuestionAnswerer) : ResearchLookup {
    override suspend fun search(query: String): List<ResearchLead> {
        val answer = try {
            answerer.ask(GoogleAiModeResearch.question(query))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw ResearchUnavailable(failure.message ?: "Google AI Mode did not answer", failure)
        }
        val text = ResearchText.clean(answer, 600)
        if (text.isBlank()) return emptyList()
        return listOf(
            ResearchLead(
                name = "Google AI Mode answer",
                source = ResearchSource.GoogleAiMode,
                trust = "an AI search answer, unchecked",
                why = text,
                connected = false,
            ),
        )
    }
}
