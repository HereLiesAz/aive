package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * The compiled checks for the kinds of output a starting template makes beyond the first twelve (docs/Step-Templates.md,
 * "Kinds of output"): any JSON, any Mermaid diagram, a unified diff, a checklist, a numbered plan, a script, a verdict with
 * one line per criterion, and a change on a dedicated branch. Each parses text and never runs it. They are fixed entries
 * of the registry in [StepOutputValidators]; nothing a proposal names can add one.
 */
internal object OutputKindChecks {
    const val JSON_VALID = "json-valid"
    const val MERMAID_DIAGRAM = "mermaid-diagram"
    const val DIFF_UNIFIED = "diff-unified"
    const val CHECKLIST = "checklist"
    const val PLAN_STEPS = "plan-steps"
    const val SCRIPT_SCENES = "script-scenes"
    const val VERDICT_CRITERIA = "verdict-criteria"
    const val CHANGE_ON_BRANCH = "change-on-branch"

    /** Id to the family and level of strictness ([StepOutputValidators.isTighter] reads them). */
    val levels: Map<String, Pair<String, Int>> = mapOf(
        JSON_VALID to ("json" to 0),
        MERMAID_DIAGRAM to ("diagram" to 0),
        DIFF_UNIFIED to ("diff" to 1),
        CHECKLIST to ("checklist" to 1),
        PLAN_STEPS to ("plan" to 1),
        SCRIPT_SCENES to ("script" to 1),
        VERDICT_CRITERIA to ("verdict" to 2),
        CHANGE_ON_BRANCH to ("files" to 2),
    )

    /** The sentence [artifact] fails [id] with, or null; [id] is one of [levels]. */
    fun check(id: String, artifact: ArtifactRef, text: String): String? = when (id) {
        JSON_VALID -> jsonValid(text)
        MERMAID_DIAGRAM -> mermaidDiagram(text)
        DIFF_UNIFIED -> diff(text)
        CHECKLIST -> checklist(text)
        PLAN_STEPS -> plan(text)
        SCRIPT_SCENES -> script(text)
        VERDICT_CRITERIA -> verdictCriteria(artifact, text)
        CHANGE_ON_BRANCH -> changeOnBranch(artifact, text)
        else -> "Unknown output check '$id'."
    }

    /** [text] without one surrounding code fence. */
    fun unfence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val inner = trimmed.substringAfter('\n', "").trimEnd()
        return inner.removeSuffix("```").trim()
    }

    private fun jsonValid(text: String): String? {
        if (text.length > StepOutputValidators.MAX_DOCUMENT_CHARS) {
            return "The output is longer than ${StepOutputValidators.MAX_DOCUMENT_CHARS} characters."
        }
        val element = try {
            Json.parseToJsonElement(unfence(text))
        } catch (_: Exception) {
            return "The output is not valid JSON."
        }
        return when {
            element is JsonObject && element.isNotEmpty() -> null
            element is JsonArray && element.isNotEmpty() -> null
            element is JsonObject || element is JsonArray -> "The JSON is empty."
            else -> "The output must be a JSON object or list."
        }
    }

    private val mermaidKinds = setOf(
        "flowchart", "graph", "sequencediagram", "classdiagram", "statediagram", "statediagram-v2", "erdiagram",
        "gantt", "journey", "pie", "mindmap", "timeline", "gitgraph", "quadrantchart", "requirementdiagram",
    )

    private fun mermaidDiagram(text: String): String? {
        if (text.length > StepOutputValidators.MAX_DOCUMENT_CHARS) {
            return "The diagram is longer than ${StepOutputValidators.MAX_DOCUMENT_CHARS} characters."
        }
        val lines = unfence(text).lineSequence().map { it.substringBefore("%%").trim() }.filter { it.isNotEmpty() }.toList()
        val first = lines.firstOrNull() ?: return "The diagram is empty."
        val kind = first.substringBefore(' ').lowercase()
        if (kind !in mermaidKinds) return "The diagram must begin with its kind, such as `flowchart TD` or `sequenceDiagram`."
        return if (lines.size < 2) "The diagram has nothing in it." else null
    }

    private val diffHunk = Regex("""^@@ -\d+(?:,\d+)? \+\d+(?:,\d+)? @@""")

    private fun diff(text: String): String? {
        if (text.length > StepOutputValidators.MAX_PAGE_CHARS) return "The diff is larger than ${StepOutputValidators.MAX_PAGE_CHARS} characters."
        val lines = unfence(text).lines()
        val from = lines.indexOfFirst { it.startsWith("--- ") }
        if (from < 0 || lines.getOrNull(from + 1)?.startsWith("+++ ") != true) {
            return "The diff must name its files with a `--- ` line and a `+++ ` line."
        }
        val hunk = lines.indexOfFirst { diffHunk.containsMatchIn(it) }
        if (hunk < 0) return "The diff has no hunk (a line such as `@@ -1,3 +1,4 @@`)."
        val changed = lines.drop(hunk + 1).any { (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---")) }
        return if (changed) null else "The diff changes no line."
    }

    private val checkItem = Regex("""^\s*[-*]\s+\[[ xX]]\s+\S""")

    private fun checklist(text: String): String? {
        if (text.length > StepOutputValidators.MAX_DOCUMENT_CHARS) return "The checklist is longer than ${StepOutputValidators.MAX_DOCUMENT_CHARS} characters."
        val items = text.lines().count { checkItem.containsMatchIn(it) }
        return when {
            items == 0 -> "The checklist needs items such as `- [ ] Do this`."
            items > MAX_CHECKLIST_ITEMS -> "The checklist has more than $MAX_CHECKLIST_ITEMS items."
            else -> null
        }
    }

    private const val MAX_CHECKLIST_ITEMS = 200
    private val numbered = Regex("""^\s*(\d+)[.)]\s+\S""")
    private val producesLine = Regex("""(?im)^\W*produces\W*[:=]\s*\S""")

    private fun plan(text: String): String? {
        if (text.length > StepOutputValidators.MAX_DOCUMENT_CHARS) return "The plan is longer than ${StepOutputValidators.MAX_DOCUMENT_CHARS} characters."
        val numbers = text.lines().mapNotNull { numbered.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (numbers.size < 2) return "The plan needs at least two numbered steps."
        if (numbers.first() != 1 || numbers.zipWithNext().any { (a, b) -> b != a + 1 }) return "The steps must be numbered 1, 2, 3 and so on, in order."
        return if (producesLine.containsMatchIn(text)) null else "The plan must say what each step produces (a PRODUCES line)."
    }

    private val sceneHeading = Regex("""(?im)^\s*(?:int|ext|int/ext|i/e|est)\.\s+\S""")

    private fun script(text: String): String? {
        if (text.length > StepOutputValidators.MAX_DOCUMENT_CHARS) return "The script is longer than ${StepOutputValidators.MAX_DOCUMENT_CHARS} characters."
        val lines = text.lines()
        val headings = lines.count { sceneHeading.containsMatchIn(it) }
        if (headings == 0) return "The script needs scene headings such as `INT. KITCHEN - DAY`."
        val body = lines.count { it.isNotBlank() && !sceneHeading.containsMatchIn(it) }
        return if (body == 0) "The scenes have nothing seen or heard in them." else null
    }

    private val criterionLine = Regex("""(?im)^\W*criterion\s+(\d+)\s*[:=\-]\s*(met|unmet)\b""")

    private fun verdictCriteria(artifact: ArtifactRef, text: String): String? {
        if (ArtifactVerdicts.of(artifact) == null) return "The output must begin with a VERDICT line."
        val numbers = criterionLine.findAll(text).map { it.groupValues[1] }.toList()
        if (numbers.isEmpty()) return "Each criterion needs a line such as `CRITERION 1: met - why`."
        return if (numbers.toSet().size != numbers.size) "A criterion is answered twice." else null
    }

    private fun changeOnBranch(artifact: ArtifactRef, text: String): String? {
        StepOutputValidators.filesChangeProblem(text)?.let { return it }
        val branch = artifact.metadata["branch"]?.trim()?.lowercase()?.removePrefix("origin/")
        return if (branch != null && branch in StepOutputValidators.sharedBranchNames) {
            "The change is recorded on the shared branch '$branch'."
        } else {
            null
        }
    }
}
