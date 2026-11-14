package com.hereliesaz.geministrator.workflow

/*
 * Checks that name what the output must contain: headings, columns or marker lines. Each id is a fixed entry with its words
 * written here, never read from a proposal, so a revision can only choose among them (and only a tighter one).
 */
internal object NamedOutputChecks {
    private sealed interface Spec {
        val family: String
        val level: Int
    }

    private data class Sections(val names: List<String>) : Spec {
        override val family = "markdown"
        override val level = 3
    }

    private data class Columns(val names: List<String>) : Spec {
        override val family = "table"
        override val level = 3
    }

    private data class Own(override val family: String, val rule: Rule) : Spec {
        override val level = 1
    }

    private enum class Rule { EmailMarked, SummaryShort, ExplainerShort, QuestionsShort, ReproNumbered, ChangelogLines, PolicyMarked, FaqQuestions, RewordedStep }

    private val specs: Map<String, Spec> = mapOf(
        "sections-brief" to Sections(listOf("Goal", "Audience", "Limits", "Done when")),
        "sections-report" to Sections(listOf("Summary", "Findings", "Limits")),
        "sections-meeting-notes" to Sections(listOf("Decisions", "Actions", "Open questions")),
        "sections-decision-memo" to Sections(listOf("Decision", "Options", "Recommendation", "Risks")),
        "sections-research-brief" to Sections(listOf("Answer", "Evidence", "Gaps")),
        "sections-critique" to Sections(listOf("Findings", "Sound")),
        "sections-proofread" to Sections(listOf("Edited text", "Changes")),
        "sections-press-release" to Sections(listOf("Headline", "Body", "About")),
        "sections-film-outline" to Sections(listOf("Idea", "Tone", "Beats")),
        "sections-spec" to Sections(listOf("Purpose", "Requirements", "Out of scope")),
        "sections-api-doc" to Sections(listOf("Inputs", "Outputs", "Errors")),
        "sections-adr" to Sections(listOf("Context", "Decision", "Consequences")),
        "sections-release-notes" to Sections(listOf("New", "Fixed", "Action needed")),
        "sections-pr-description" to Sections(listOf("What", "Why", "How to check")),
        "sections-bug-report" to Sections(listOf("Expected", "Actual", "Steps", "Evidence")),
        "sections-test-plan" to Sections(listOf("Scope", "Cases", "Pass criteria")),
        "media-brief" to Sections(listOf("Subject", "Style", "Format")),
        "columns-glossary" to Columns(listOf("term", "definition")),
        "columns-timeline" to Columns(listOf("item", "start", "end", "waits for")),
        "columns-risks" to Columns(listOf("risk", "signal", "impact", "reduction")),
        "columns-sources" to Columns(listOf("title", "where", "fetched", "note")),
        "columns-claims" to Columns(listOf("claim", "result", "evidence", "source")),
        "columns-posts" to Columns(listOf("place", "text")),
        "columns-storyboard" to Columns(listOf("shot", "in frame", "camera", "seconds", "line")),
        "columns-shots" to Columns(listOf("shot", "setup", "seconds")),
        "columns-test-cases" to Columns(listOf("id", "steps", "expected")),
        "email-marked" to Own("found", Rule.EmailMarked),
        "summary-short" to Own("summary", Rule.SummaryShort),
        "explainer-short" to Own("explainer", Rule.ExplainerShort),
        "questions-short" to Own("questions", Rule.QuestionsShort),
        "repro-numbered" to Own("repro", Rule.ReproNumbered),
        "changelog-lines" to Own("changelog", Rule.ChangelogLines),
        "policy-marked" to Own("policy", Rule.PolicyMarked),
        "faq-questions" to Own("faq", Rule.FaqQuestions),
        "reworded-step" to Own("reworded", Rule.RewordedStep),
    )

    val levels: Map<String, Pair<String, Int>> = specs.mapValues { it.value.family to it.value.level }

    /** The headings and columns an id names, for the wording of a template; empty for the others. */
    fun namesOf(id: String): List<String> = when (val s = specs[id]) {
        is Sections -> s.names
        is Columns -> s.names
        else -> emptyList()
    }

    fun check(id: String, text: String): String? = when (val spec = specs[id]) {
        is Sections -> sections(text, spec.names)
        is Columns -> columns(text, spec.names)
        is Own -> own(spec.rule, text)
        null -> "Unknown output check '$id'."
    }

    private fun headings(text: String): List<String> = text.lines().map { it.trim() }
        .filter { it.startsWith("#") && it.dropWhile { c -> c == '#' }.startsWith(" ") }
        .map { it.dropWhile { c -> c == '#' }.trim().trim('*', '_', ':').lowercase() }

    private fun sections(text: String, names: List<String>): String? {
        StepOutputValidators.markdown(text, minHeadings = 2)?.let { return it }
        val found = headings(text)
        val missing = names.filter { name -> found.none { it.contains(name.lowercase()) } }
        return if (missing.isEmpty()) null else "The document needs a heading for: ${missing.joinToString(", ")}."
    }

    private fun normal(name: String) = name.lowercase().replace('_', ' ').replace('-', ' ').trim()

    private fun columns(text: String, names: List<String>): String? {
        StepOutputValidators.table(text, filled = true)?.let { return it }
        val header = DelimitedTableCodec.parse(text, ',', firstRowHeaders = true, maxRows = 1).columns.map(::normal)
        val missing = names.filter { it !in header }
        return if (missing.isEmpty()) null else "The table needs the columns: ${missing.joinToString(", ")}."
    }

    private val numbered = Regex("""^\s*\d+[.)]\s+\S""")
    private val changeWord = Regex("""(?i)^\s*(?:[-*]\s+)?\W*(added|fixed|changed|removed|deprecated|security)\b""")

    private fun own(rule: Rule, text: String): String? = when (rule) {
        Rule.EmailMarked -> when {
            text.length > 4_000 -> "The email is longer than 4000 characters."
            text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.startsWith("Subject:", ignoreCase = true) != true ->
                "The email must begin with a `Subject:` line."
            else -> null
        }
        Rule.SummaryShort -> if (text.length > 6_000) "The summary is longer than 6000 characters." else null
        Rule.ExplainerShort -> StepOutputValidators.markdown(text, minHeadings = 1) ?: if (text.length > 4_500) "The explainer is longer than one page (4500 characters)." else null
        Rule.QuestionsShort -> {
            val n = text.lines().count { numbered.containsMatchIn(it) }
            if (n in 1..7) null else "Give between 1 and 7 numbered questions."
        }
        Rule.ReproNumbered -> if (text.lines().count { numbered.containsMatchIn(it) } >= 2) null else "Give at least two numbered steps."
        Rule.ChangelogLines -> if (text.lines().any { changeWord.containsMatchIn(it) }) null else "Name each change as Added, Fixed or Changed."
        Rule.PolicyMarked -> {
            val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.trim('#', '*', '_', ' ')?.lowercase()
            if (first?.startsWith("draft for review") != true) "The first line must say `DRAFT for review`." else StepOutputValidators.markdown(text, minHeadings = 2)
        }
        Rule.FaqQuestions -> if (headings(text).count { it.endsWith("?") } >= 2) null else "Give at least two questions as headings that end with a question mark."
        Rule.RewordedStep -> when {
            text.length > 4_000 -> "The wording is longer than 4000 characters."
            text.any { it.code < 32 && it != '\n' && it != '\t' } -> "The wording holds characters that are not plain text."
            else -> null
        }
    }
}
