package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.tour.TargetRow
import com.hereliesaz.geministrator.tour.TeachingLimits
import com.hereliesaz.geministrator.tour.Violation

/**
 * The task input each generic creature (Planner, Reviewer, writer) is handed for a tutorial. The roles themselves carry
 * no tutorial wording; everything tutorial-shaped arrives here, as input. Nothing in this file is a lesson: it says what
 * a good answer looks like and lists what exists, never what to teach or in which order. Every list it prints is the
 * registry or the keeper's own record, read at run time.
 */
object TeachingPrompts {
    /** The registry as the packet shows it: `id | name | needs | checkpoints | where`. Built at run time from the real rows. */
    fun targetTable(rows: List<TargetRow>, whereOf: (String) -> String): String = rows.joinToString("\n") { r ->
        listOf(
            r.id,
            r.label,
            r.needs.sorted().joinToString(",").ifEmpty { "-" },
            r.observable.joinToString(",") { it.wire },
            if (r.alwaysVisible) "always on screen" else whereOf(r.id),
        ).joinToString(" | ")
    }

    fun plannerTask(
        goal: String,
        table: String,
        facts: List<String>,
        shaky: List<String>,
        comfortable: List<String>,
        scope: TutorialScope,
        problems: List<Violation>,
        objections: List<String>,
    ): String = buildString {
        append("GOAL (from the keeper): ").append(goal.trim().take(300)).append('\n')
        append("The goal is to teach the keeper by having them use the real app. Plan a chain of SMALL steps.\n")
        append("Each step has exactly ONE target (a real control from the table) and exactly ONE checkpoint (one thing the keeper does that the app can see).\n")
        append("About a minute of attention per step. More, smaller steps are better than fewer, larger ones.\n")
        append("Write one line per step, in exactly this form (at most ${TeachingLimits.STEP_OBJECTIVE_MAX_WORDS} words before Target):\n")
        append("<what the keeper learns>. Target: <ID>. Checkpoint: <kind>.\n")
        append("Use only ids and checkpoint kinds from the table. Step 1 uses a target that is always on screen. A step may use only targets taught by earlier steps or always on screen.\n")
        append("Never plan an approval, never put two targets or two checkpoints in one step, never repeat a target. You decide what to teach, in what order and when to stop, from the goal, the facts and the keeper's record below; the table only says what exists.\n")
        append(if (scope == TutorialScope.Broad) "The goal asks for the whole app: plan at least ${TeachingLimits.BROAD_GOAL_MIN_STEPS} and at most ${TeachingLimits.MAX_STEPS} steps.\n" else "The goal is narrow: plan ${TeachingLimits.FOCUSED_MIN_STEPS} to ${TeachingLimits.FOCUSED_MAX_STEPS} steps.\n")
        append("\nTARGETS (id | name | needs | checkpoints | where):\n").append(table).append('\n')
        if (facts.isNotEmpty()) append("\nFACTS about this device:\n").append(facts.joinToString("\n") { "- $it" }).append('\n')
        if (shaky.isNotEmpty()) append("\nThe keeper found these shaky before (revisit them, from a fresh angle): ").append(shaky.joinToString(", ")).append('\n')
        if (comfortable.isNotEmpty()) append("The keeper is comfortable with these (skip them or keep them brief): ").append(comfortable.joinToString(", ")).append('\n')
        if (problems.isNotEmpty()) append("\nYour last plan broke these rules; fix every one:\n").append(problems.joinToString("\n") { "- ${it.rule}: ${it.message}" }).append('\n')
        if (objections.isNotEmpty()) append("\nReviewers turned your last plan back; answer each:\n").append(objections.joinToString("\n") { "- $it" }).append('\n')
        append("\nReturn the steps only, one per line.")
    }

    fun reviewerTask(goal: String, plan: List<TeachingStep>, rows: List<TargetRow>): String = buildString {
        val label = rows.associate { it.id to it.label }
        append("Judge the plan below against these requirements. Say which it fails.\n")
        append("R1 It serves the keeper's goal: ").append(goal.trim().take(300)).append('\n')
        append("R2 Each step is small: one target, one checkpoint, about a minute.\n")
        append("R3 Each step uses only targets taught earlier or always on screen.\n")
        append("R4 Nothing asks the keeper for more than tapping or opening a real control.\n")
        append("R5 It is shaped by this goal, not a generic tour of everything.\n\n")
        append("PLAN:\n")
        plan.forEachIndexed { i, s -> append("${i + 1}. ").append(s.learns).append(" [").append(label[s.target] ?: s.target).append(", ").append(s.kind.wire).append("]\n") }
        append("\nFirst line exactly `VERDICT: approve` or `VERDICT: revise`. If revise, then up to 3 lines starting with `- `, each at most ${TeachingLimits.STEP_OBJECTIVE_MAX_WORDS} words, naming the step number and the requirement. Judge only; change nothing.")
    }

    /** What a lesson's text must carry, from the rules the validator enforces (so a repair is told the same limits). */
    fun lessonTask(
        step: TeachingStep,
        row: TargetRow,
        place: String,
        introducedLabels: List<String>,
        goal: String,
        first: Boolean,
        smaller: Boolean,
        shakyBefore: Boolean,
        previousSentence: String?,
        problems: List<Violation>,
    ): String = buildString {
        append("Write ONE lesson step for a keeper using the real app. Reply with one JSON object and nothing else.\n")
        append("The keeper's goal: ").append(goal.trim().take(300)).append('\n')
        append("This step teaches: ").append(step.learns).append('\n')
        append("The control is named ").append(row.label).append(" (id ").append(row.id).append("), found ").append(if (row.alwaysVisible) "always on screen" else place).append(".\n")
        append("The checkpoint is: ").append(step.kind.wire).append(" on that control.\n")
        if (introducedLabels.isNotEmpty()) append("Controls already taught, which you may mention: ").append(introducedLabels.joinToString(", ")).append('\n')
        if (previousSentence != null) append("The step before said: ").append(previousSentence.take(TeachingLimits.SENTENCE_MAX_CHARS)).append('\n')
        append("\nKeys (all required unless marked optional):\n")
        append("target: exactly ").append(row.id).append('\n')
        append("sentence: the one thing to do, at most ${TeachingLimits.SENTENCE_MAX_CHARS} characters, plain words\n")
        append("expect: {\"type\":\"").append(step.kind.wire).append("\",\"target\":\"").append(row.id).append("\"}\n")
        append("explain: why it matters, at most ${TeachingLimits.EXPLAIN_MAX_WORDS} words\n")
        append("ifMissed: a nudge if they have not done it yet, at most ${TeachingLimits.IF_MISSED_MAX_WORDS} words\n")
        append("checkIn: one short question about how the keeper feels so far, ending with a question mark, at most ${TeachingLimits.CHECKIN_LINE_MAX_CHARS} characters; it must not test knowledge and must not name a control\n")
        append("anotherWay: the same idea from a new angle, at most ${TeachingLimits.ANOTHER_WAY_MAX_WORDS} words\n")
        append("smaller: a list of exactly ${TeachingLimits.SMALLER_STEPS} tiny moves splitting the same action, each at most ${TeachingLimits.SMALLER_STEP_MAX_WORDS} words\n")
        append("bridge: optional\n")
        append("\nRules: name a control only by its exact name, in double quotes, and only this one or one already taught. No web addresses, no markup, no code. Never ask for a key, a login or a password. ")
        append("Vocabulary: keeper (the person), queen, swarm, hive (the goal), creature, Messages, Story, Source, Nanny, Orphan, Host, Edit view. Do not claim how many hives the keeper has.\n")
        if (first) {
            append("This is the first step: in explain, say in your own words that you read a short summary of the keeper's setup, never their goals or keys, and that free Sources may log what they get. ")
            append("Use the words summary, goals, keys and the phrase free Sources may log.\n")
        }
        if (smaller) append("The keeper asked to go slower: keep every field as short and simple as the limits allow.\n")
        if (shakyBefore) append("The keeper found this shaky before: say it from a fresh angle.\n")
        if (problems.isNotEmpty()) append("\nYour last attempt broke these rules; fix every one:\n").append(problems.joinToString("\n") { "- ${it.rule}: ${it.message}" }).append('\n')
    }
}

