package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.decodeHallMonitorReportPayload
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.Requirements
import com.hereliesaz.geministrator.queen.parseLessons
import com.hereliesaz.geministrator.queen.parsePracticeResults
import com.hereliesaz.geministrator.template.TemplateRevisionDraft
import com.hereliesaz.geministrator.template.TemplateRevisionRules
import com.hereliesaz.geministrator.template.TemplateTarget
import com.hereliesaz.geministrator.tour.LessonValidator
import kotlinx.serialization.json.Json

/*
 * The shape checks of the outputs Aive's own swarms produce (the plan, a tutorial lesson, practice scores, the training
 * report, specialist lessons, the Nanny's report, a revision draft). They wrap the readers the app already uses and need
 * nothing but the text; the checks that need the app's registry or a run's facts stay where they are.
 */
internal object InternalOutputChecks {
    const val SWARM_PLAN = "swarm-plan-json"
    const val LESSON_JSON = "lesson-json"
    const val PRACTICE_LINES = "practice-lines"
    const val TRAINING_REPORT = "training-report-lines"
    const val LESSON_LINES = "lesson-lines"
    const val NANNY_REPORT = "nanny-report-json"
    const val REVISION_DRAFT = "revision-draft-json"

    val levels: Map<String, Pair<String, Int>> = mapOf(
        SWARM_PLAN to ("swarm-plan" to 1),
        LESSON_JSON to ("lesson" to 1),
        PRACTICE_LINES to ("practice" to 1),
        TRAINING_REPORT to ("training-report" to 1),
        LESSON_LINES to ("lesson-lines" to 1),
        NANNY_REPORT to ("nanny-report" to 1),
        REVISION_DRAFT to ("revision" to 1),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val ruling = Regex("""(?im)^\W*ruling\W*[:=]\W*(pass|refine|stop)\b""")
    private val reasonCite = Regex("""(?im)^\W*reason\W*[:=].*\[[A-Za-z0-9]+]""")

    fun check(id: String, text: String): String? {
        if (text.length > StepOutputValidators.MAX_DOCUMENT_CHARS) return "The output is longer than ${StepOutputValidators.MAX_DOCUMENT_CHARS} characters."
        return when (id) {
            SWARM_PLAN -> plan(text)
            LESSON_JSON -> LessonValidator.shapeProblem(text)
            PRACTICE_LINES -> if (parsePracticeResults(text, null, false) == null) {
                "Give `GENERIC: met/of` and `SPECIALIST: met/of` over the same number of tasks."
            } else {
                null
            }
            TRAINING_REPORT -> when {
                !ruling.containsMatchIn(text) -> "The report needs `RULING: PASS`, `REFINE` or `STOP`."
                !reasonCite.containsMatchIn(text) -> "The report needs a `REASON:` line citing a listed fact in square brackets."
                else -> null
            }
            LESSON_LINES -> if (parseLessons(text).isEmpty()) "Give lessons as `LESSON: <text> | SOURCE: <label> | FETCHED: <yyyy-mm-dd>`." else null
            NANNY_REPORT -> if (runCatching { decodeHallMonitorReportPayload(text) }.isSuccess) null else "The report is not one the app can read."
            REVISION_DRAFT -> revision(text)
            else -> "Unknown output check '$id'."
        }
    }

    private fun plan(text: String): String? {
        val plan = try {
            json.decodeFromString(OrchestrationPlan.serializer(), OutputKindChecks.unfence(text))
        } catch (_: Exception) {
            return "The plan is not a plan the app can read."
        }
        val ids = plan.steps.map { it.id.trim() }
        return when {
            plan.steps.isEmpty() -> "The plan has no steps."
            plan.steps.size > Requirements.MAX_PLAN_STEPS -> "The plan has more than ${Requirements.MAX_PLAN_STEPS} steps."
            ids.any { it.isEmpty() } || ids.toSet().size != ids.size -> "Every step needs its own id."
            plan.steps.any { it.name.isBlank() || it.objective.isBlank() } -> "Every step needs a name and an objective."
            plan.steps.any { s -> s.id in s.dependsOn || s.dependsOn.any { it !in ids } } -> "A step depends on itself or on a step that is not in the plan."
            else -> null
        }
    }

    private fun revision(text: String): String? {
        val draft = try {
            json.decodeFromString(TemplateRevisionDraft.serializer(), OutputKindChecks.unfence(text))
        } catch (_: Exception) {
            return "The revision is not one the app can read."
        }
        return when {
            TemplateTarget.parse(draft.target) == null -> "The revision must name its target as `step:<id>@<version>` or `role:<id>@<version>`."
            draft.intent == null -> "A revision must say what it aims to improve."
            (draft.wording?.length ?: 0) > TemplateRevisionRules.MAX_WORDING_CHARS -> "The wording is longer than ${TemplateRevisionRules.MAX_WORDING_CHARS} characters."
            else -> null
        }
    }
}
