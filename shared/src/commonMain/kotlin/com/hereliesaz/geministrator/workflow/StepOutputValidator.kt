package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.OutputSpec
import com.hereliesaz.geministrator.domain.TaskContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * The deterministic check on a step's output (docs/Step-Templates.md). An agent's output is model text and
 * untrusted: a compiled validator parses it, never runs it. There are no scripts and no model calls here; a
 * validator id that is not in the registry is a violation, so nothing a proposal names can add code.
 */

/** Failure text and code of a task whose output did not meet its step contract. */
const val OUTPUT_CONTRACT_VIOLATION: String = "OUTPUT_CONTRACT_VIOLATION"

object StepOutputValidators {
    const val MARKDOWN_DOCUMENT = "markdown-document"
    const val MARKDOWN_SECTIONED = "markdown-document-sectioned"
    const val HTML_PAGE = "html-page"
    const val HTML_CONTAINED = "html-page-contained"
    const val TABLE_CSV = "table-csv"
    const val TABLE_CSV_FILLED = "table-csv-filled"
    const val VERDICT = "verdict"
    const val FOUND_TEXT = "found-text"
    const val CHANGE_RESULT = "change-result"
    const val MERMAID_FLOWCHART = "mermaid-flowchart"
    const val JSON_OBJECT = "json-object"
    const val FILES_CHANGE = "files-change"

    const val MAX_DOCUMENT_CHARS: Int = 60_000
    const val MAX_PAGE_CHARS: Int = 500_000
    const val MAX_TABLE_ROWS: Int = 5_000
    const val MAX_FOUND_CHARS: Int = 20_000

    /** Strictness within a family: a higher level accepts a subset of what a lower level accepts. */
    private val levels: Map<String, Pair<String, Int>> = mapOf(
        MARKDOWN_DOCUMENT to ("markdown" to 1),
        MARKDOWN_SECTIONED to ("markdown" to 2),
        HTML_PAGE to ("html" to 1),
        HTML_CONTAINED to ("html" to 2),
        TABLE_CSV to ("table" to 1),
        TABLE_CSV_FILLED to ("table" to 2),
        VERDICT to ("verdict" to 1),
        FOUND_TEXT to ("found" to 1),
        CHANGE_RESULT to ("change" to 1),
        MERMAID_FLOWCHART to ("diagram" to 1),
        JSON_OBJECT to ("json" to 1),
        FILES_CHANGE to ("files" to 1),
    )

    val ids: Set<String> get() = levels.keys

    fun isKnown(id: String): Boolean = id in levels

    /** True when [to] accepts a subset of what [from] accepts: the same family and a higher level. */
    fun isTighter(from: String, to: String): Boolean {
        val a = levels[from] ?: return false
        val b = levels[to] ?: return false
        return a.first == b.first && b.second > a.second
    }

    /** True when [to] is the same validator or tighter than [from] (loosening is anything else). */
    fun isSameOrTighter(from: String, to: String): Boolean = from == to || isTighter(from, to)

    /** A one-sentence reason [artifact] does not pass validator [id], or null when it does. */
    fun check(id: String, artifact: ArtifactRef): String? {
        val text = artifact.textContent
        if (!isKnown(id)) return "Unknown output check '$id'."
        if (text.isNullOrBlank()) return "The output is empty."
        return when (id) {
            MARKDOWN_DOCUMENT -> markdown(text, minHeadings = 1)
            MARKDOWN_SECTIONED -> markdown(text, minHeadings = 2)
            HTML_PAGE -> html(text, allowExternalUrls = true)
            HTML_CONTAINED -> html(text, allowExternalUrls = false)
            TABLE_CSV -> table(text, filled = false)
            TABLE_CSV_FILLED -> table(text, filled = true)
            VERDICT -> if (ArtifactVerdicts.of(artifact) == null) "The output must begin with a VERDICT line." else null
            FOUND_TEXT -> if (text.length > MAX_FOUND_CHARS) "The output is longer than $MAX_FOUND_CHARS characters." else null
            CHANGE_RESULT -> changeResult(text)
            MERMAID_FLOWCHART -> mermaid(text)
            JSON_OBJECT -> jsonObject(text)
            FILES_CHANGE -> filesChange(text)
            else -> "Unknown output check '$id'."
        }
    }

    private fun markdown(text: String, minHeadings: Int): String? {
        if (text.length > MAX_DOCUMENT_CHARS) return "The document is longer than $MAX_DOCUMENT_CHARS characters."
        val headings = text.lineSequence().count { it.trimStart().startsWith("#") && it.trimStart().dropWhile { c -> c == '#' }.startsWith(" ") }
        return if (headings < minHeadings) "The document needs at least $minHeadings heading(s)." else null
    }

    private val scriptSrc = Regex("""<script\b[^>]*\bsrc\s*=""", RegexOption.IGNORE_CASE)
    private val stylesheetLink = Regex("""<link\b[^>]*\brel\s*=\s*["']?stylesheet""", RegexOption.IGNORE_CASE)
    private val externalUrl = Regex("""\b(?:src|href)\s*=\s*["']?\s*(?:https?:)?//""", RegexOption.IGNORE_CASE)
    private val importUrl = Regex("""@import\b""", RegexOption.IGNORE_CASE)

    private fun html(text: String, allowExternalUrls: Boolean): String? {
        if (text.length > MAX_PAGE_CHARS) return "The page is larger than $MAX_PAGE_CHARS characters."
        val lower = text.lowercase()
        if (!lower.contains("<html") && !lower.contains("<body") && !lower.contains("<div") && !lower.contains("<main")) {
            return "The output is not an HTML page."
        }
        if (!lower.contains("</")) return "The page has no closing tags."
        if (scriptSrc.containsMatchIn(text)) return "The page loads an external script."
        if (stylesheetLink.containsMatchIn(text) || importUrl.containsMatchIn(text)) return "The page loads an external stylesheet."
        if (!allowExternalUrls && externalUrl.containsMatchIn(text)) return "The page points at an external address."
        return null
    }

    private fun table(text: String, filled: Boolean): String? {
        val parsed = try {
            DelimitedTableCodec.parse(text, ',', firstRowHeaders = true, maxRows = MAX_TABLE_ROWS + 1)
        } catch (_: IllegalArgumentException) {
            return "The table is not valid comma-separated text."
        }
        if (parsed.columns.isEmpty() || parsed.columns.all(String::isBlank)) return "The table has no header row."
        if (parsed.rows.isEmpty()) return "The table has no rows."
        if (parsed.rows.size > MAX_TABLE_ROWS) return "The table has more than $MAX_TABLE_ROWS rows."
        if (filled && parsed.columns.any(String::isBlank)) return "Every column needs a header."
        return null
    }

    /** [text] without one surrounding code fence (a model often wraps a diagram or JSON in one). */
    private fun unfence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val inner = trimmed.substringAfter('\n', "").trimEnd()
        return inner.removeSuffix("```").trim()
    }

    private val flowchartHeader = Regex("""^(?:flowchart|graph)\s+[A-Za-z]{2}$""", RegexOption.IGNORE_CASE)

    /** A Mermaid flowchart the common reader ([MermaidFlowchartParser]) understands, with nothing it has to skip. */
    private fun mermaid(text: String): String? {
        if (text.length > MAX_DOCUMENT_CHARS) return "The diagram is longer than $MAX_DOCUMENT_CHARS characters."
        val body = unfence(text)
        val first = body.lineSequence().map { it.substringBefore("%%").trim() }.firstOrNull { it.isNotEmpty() }
            ?: return "The diagram is empty."
        if (!flowchartHeader.matches(first)) return "The diagram must begin with a flowchart line and a direction, such as `flowchart TD`."
        val graph = try {
            MermaidFlowchartParser.parse(body)
        } catch (_: Exception) {
            return "The diagram is not a flowchart the app can read."
        }
        return if (graph.warnings.isNotEmpty()) "The diagram has a statement the flowchart reader does not understand." else null
    }

    private fun jsonObject(text: String): String? {
        if (text.length > MAX_DOCUMENT_CHARS) return "The output is longer than $MAX_DOCUMENT_CHARS characters."
        val element = try {
            Json.parseToJsonElement(unfence(text))
        } catch (_: Exception) {
            return "The output is not valid JSON."
        }
        val obj = element as? JsonObject ?: return "The output must be one JSON object."
        return if (obj.isEmpty()) "The JSON object is empty." else null
    }

    private val branchLine = Regex("""(?im)^\W*branch\W*[:=]\s*(\S+)""")
    private val filesLine = Regex("""(?im)^\W*files?\W*[:=]\s*\S""")
    private val sharedBranches = setOf("main", "master", "develop", "trunk", "head", "release")

    /** Files written on a dedicated branch: a BRANCH line that is not a shared branch, and a FILES line. */
    private fun filesChange(text: String): String? {
        if (text.length > MAX_FOUND_CHARS) return "The output is longer than $MAX_FOUND_CHARS characters."
        val named = branchLine.find(text)?.groupValues?.get(1)?.trim('`', '"', '\'', '.', ',')
            ?: return "The result must name the branch (a BRANCH line)."
        if (named.lowercase().removePrefix("origin/") in sharedBranches) return "The files must be on a dedicated branch, not '$named'."
        if (!filesLine.containsMatchIn(text)) return "The result must list the files written (a FILES line)."
        return null
    }

    private val targetLine = Regex("""(?im)^\W*target\W*[:=]\s*\S""")
    private val changedLine = Regex("""(?im)^\W*changed\W*[:=]\s*\S""")

    private fun changeResult(text: String): String? {
        if (text.length > MAX_FOUND_CHARS) return "The output is longer than $MAX_FOUND_CHARS characters."
        if (!targetLine.containsMatchIn(text)) return "The result must state what was changed (a TARGET line)."
        if (!changedLine.containsMatchIn(text)) return "The result must state how it changed (a CHANGED line)."
        return null
    }
}

/** Base media type, lowercased, without parameters. */
private fun baseMediaType(mediaType: String): String = mediaType.substringBefore(';').trim().lowercase()

/** The artifact among [artifacts] that fills [spec], or null: the first of its kind whose media type, when stated, matches. */
fun findOutputFor(spec: OutputSpec, artifacts: List<ArtifactRef>): ArtifactRef? =
    artifacts.firstOrNull { artifact ->
        artifact.kind == spec.kind &&
            (artifact.mediaType == null || baseMediaType(artifact.mediaType!!) == baseMediaType(spec.mediaType))
    }

/**
 * Every way [artifacts] fall short of [contract]'s declared outputs, in plain sentences; empty when the contract
 * is met (or declares no outputs). Pure: the engine calls it beside the verdict gate when a task completes.
 */
fun checkStepOutputs(contract: TaskContract, artifacts: List<ArtifactRef>): List<String> =
    contract.outputs.mapNotNull { spec ->
        val artifact = findOutputFor(spec, artifacts)
            ?: return@mapNotNull "The step did not hand back its '${spec.name}' (${spec.kind.name})."
        StepOutputValidators.check(spec.validatorId, artifact)?.let { "'${spec.name}': $it" }
    }

/** The next attempt's context line for a violation: what to fix. */
fun contractViolationMessage(problems: List<String>): String =
    "The output did not meet the step's contract: " + problems.joinToString(" ")
