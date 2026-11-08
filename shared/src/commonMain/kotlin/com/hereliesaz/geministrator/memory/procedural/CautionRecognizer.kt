package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.control.ControlRedaction

/**
 * The programmatic recognizer of a `caution`: it reads what a model wrote while handling the result
 * of a habit's work and keeps the sentences in which the model warns about it. It is a filing step,
 * like the other memory clerks: it says what kind of statement a sentence is, never whether the
 * warning is true, never rewrites a word, and never infers a caution from silence. A model that
 * writes nothing of the kind leaves nothing.
 *
 * Deliberately conservative: a sentence counts only if it carries a warning cue and also refers
 * back to the thing that was done (a word of the habit's operation, or a plain noun for it).
 */
object CautionRecognizer {
    const val MAX_SENTENCES = 3
    const val MAX_SENTENCE_CHARS = 200

    /** Cues that read as a warning on their own. */
    private val strong = Regex(
        "(?i)\\b(unexpected(ly)?|failed|fails|failing|failure|error|errors|warning|careful|caution|" +
            "assum(e|es|ed|ing)|had to|needed to|have to|workaround|flaky|broken|missing|" +
            "not working|(does(n't| not)|did(n't| not)) work|unfortunately|watch out|beware|" +
            "timed out|timeout|retried|reran|re-ran|stale|only works)\\b",
    )

    /** Cues that read as a warning only when the sentence is plainly about the work. */
    private val weak = Regex("(?i)\\b(but|however|turned out|although|except)\\b")

    /** Phrases that name a calm outcome; they are set aside so "no errors" is not an error. */
    private val calm = Regex(
        "(?i)\\b(no (errors?|failures?|problems?|issues?|warnings?)|without (any )?(errors?|issues?|problems?|warnings?)|" +
            "all (green|passed|tests passed)|passed|succeeded|successful(ly)?)\\b",
    )

    /** Plain nouns for the thing that was done. */
    private val referent = Regex(
        "(?i)\\b(build|builds|run|runs|job|jobs|deploy|deployment|workflow|test|tests|step|steps|" +
            "pipeline|check|checks|merge|release|action|script|command|result|results|output)\\b",
    )

    private val sentenceBreak = Regex("(?<=[.!?])\\s+|\\n+")
    private val words = Regex("[^A-Za-z0-9]+")

    /**
     * The caution sentences in [text], verbatim, at most [MAX_SENTENCES]. [cues] are the words of the
     * habit's operation (see [HabitOffers.cuesOf]).
     */
    fun recognize(text: String, cues: List<String>): List<String> {
        val cueWords = cues.map { it.lowercase() }.toSet()
        return text.split(sentenceBreak)
            .map { it.trim() }
            .filter { it.length in MIN_SENTENCE_CHARS..MAX_SENTENCE_CHARS }
            .filter { sentence ->
                val body = calm.replace(sentence, " ")
                val about = referent.containsMatchIn(sentence) ||
                    sentence.lowercase().split(words).any { it in cueWords }
                about && (strong.containsMatchIn(body) || weak.containsMatchIn(body))
            }
            .map { ControlRedaction.scrub(it) }
            .distinct()
            .take(MAX_SENTENCES)
    }

    private const val MIN_SENTENCE_CHARS = 12
}
