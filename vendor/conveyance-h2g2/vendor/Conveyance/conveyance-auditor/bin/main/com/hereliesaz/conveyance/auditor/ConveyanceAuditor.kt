package com.hereliesaz.conveyance.auditor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.hereliesaz.conveyance.AuditFrame
import com.hereliesaz.conveyance.AuditReport
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Grade
import com.hereliesaz.conveyance.Prediction
import com.hereliesaz.conveyance.Verdict

/**
 * Runs the two rules no structural check can settle.
 *
 * *The reader can tell beforehand what each will do* needs a reader, and *not omit necessary
 * details* needs someone to notice what is missing. Both were written off as human-only work. They
 * are not — they are work for a viewer who has never seen the product, and that is something this
 * framework can arrange on demand.
 *
 * **The viewer is kept naive, deliberately and structurally.** The first pass receives the rendered
 * image and nothing else: no element names, no act identifiers, no source, no [AuditFrame]. The
 * moment it can read the code it stops predicting and starts reciting, and the measurement becomes
 * worthless. The truth is held back and used only in the second pass, to grade what the first pass
 * already committed to.
 *
 * That separation is the whole design. Everything else here is plumbing.
 */
class ConveyanceAuditor(
    /**
     * Who does the looking.
     *
     * Defaults to whatever this machine can reach, which with nothing configured is a model running
     * locally and no key at all. The judge's identity is recorded on the report, because a verdict
     * from a small local model and a verdict from a frontier one are not the same evidence and
     * should never be filed as though they were.
     */
    private val judge: Judge = Judges.detect(),
) {
    private val json = ObjectMapper().registerKotlinModule()

    /**
     * Show [png] to a viewer who has never seen it, then grade what they said against [frame].
     *
     * @param png the rendered surface, exactly as a person would see it.
     * @param frame what is actually true, which the viewer never sees.
     */
    fun audit(png: ByteArray, frame: AuditFrame): AuditReport {
        val naive = predict(png)
        return grade(naive, frame)
    }

    /**
     * What the naive viewer said, in their own words, before the truth arrives: what they expect
     * ([Naive.predictions]) and what they'd want to know before touching anything
     * ([Naive.missing]). Kept as one pass-one result rather than two calls, since both come out of
     * the same single, truth-blind look at [png].
     */
    private data class Naive(val predictions: List<Prediction>, val missing: List<String>)

    /** Pass one. Pixels in, expectations out. Nothing else crosses this boundary. */
    private fun predict(png: ByteArray): Naive = parse(judge.look(png, PREDICT))

    /** Pass two. The truth arrives only now, and only to judge an answer already given. */
    private fun grade(naive: Naive, frame: AuditFrame): AuditReport {
        val truth = json.writeValueAsString(frame)
        val guesses = json.writeValueAsString(naive.predictions)
        val wished = json.writeValueAsString(naive.missing)
        // No image on this pass. The grader is weighing an answer already committed to, and showing
        // it the screen would only invite it to form its own opinion and mark against that.
        val text = judge.look(
            null,
            "$GRADE\n\nWHAT THEY SAID:\n$guesses\n\n" +
                "WHAT THEY SAID THEY WISHED THEY KNEW:\n$wished\n\nWHAT IS TRUE:\n$truth",
        )
        val parsed = json.readTree(strip(text))
        val verdicts = parsed.path("verdicts").map { node ->
            Verdict(
                element = node.path("element").asText("").takeIf { it.isNotBlank() }?.let(::ElementId),
                grade = runCatching { Grade.valueOf(node.path("grade").asText()) }.getOrDefault(Grade.NoIdea),
                predicted = node.path("predicted").asText(""),
                actual = node.path("actual").asText(""),
                note = node.path("note").asText(""),
            )
        }
        val omissions = parsed.path("omissions").map { it.asText() }
        return AuditReport(frame.surface, verdicts, omissions, judge.name)
    }

    private fun parse(text: String): Naive {
        val node = json.readTree(strip(text))
        val predictions = node.path("predictions").map { prediction ->
            Prediction(
                where = prediction.path("where").asText(""),
                expectation = prediction.path("expectation").asText(""),
                certain = prediction.path("certain").asBoolean(false),
            )
        }
        val missing = node.path("missing").map { it.asText() }
        return Naive(predictions, missing)
    }

    /** Models fence JSON more often than not, and a fence is not a parse error worth failing on. */
    private fun strip(text: String): String =
        text.substringAfter("```json", text).substringAfter("```", text).substringBeforeLast("```")
            .trim()
            .ifBlank { "{}" }

    private companion object {
        val PREDICT = """
            You have never seen this application before. You are looking at one screen of it.

            Point at everything you believe you can interact with. For each one, say where it is in
            your own words, what you expect will happen if you touch it, and whether you are
            actually sure or just guessing.

            Then, separately: is there anything you would want to know before touching any of these
            that this screen does not tell you? Cost, permanence, whether it can be undone, what it
            will affect. Only list what is genuinely missing from what you can see.

            Guess honestly. If you cannot tell what something does, say so -- that is a useful
            answer and pretending otherwise is not.

            Reply with JSON only:
            {"predictions":[{"where":"...","expectation":"...","certain":true|false}],
             "missing":["..."]}
        """.trimIndent()

        val GRADE = """
            Below are one person's predictions about a screen they had never seen, and then the
            truth about what that screen actually does. They did not have the truth when they
            answered.

            Grade each prediction against the truth:
              Right  - they expected substantially what actually happens.
              Wrong  - they expected something that does not happen. Confident and wrong is the
                       worst case: the interface actively misled them.
              NoIdea - they said they could not tell.

            Match predictions to elements by position and description. An element in the truth that
            nobody predicted at all is a Verdict with grade NoIdea and a note saying it went
            unmentioned.

            Then list omissions: anything in the truth that a person would need before acting and
            that the screen does not convey. An act that is Heavy or not reversible, where nothing
            visible says so, is always an omission. You will also see what they said, unprompted
            and before seeing any of this, that they wished they'd known -- fold whichever of
            those turn out to genuinely be true, per what is actually true, into the omissions
            list too; discard the rest.

            Reply with JSON only:
            {"verdicts":[{"element":"...","grade":"Right|Wrong|NoIdea","predicted":"...",
                          "actual":"...","note":"..."}],
             "omissions":["..."]}
        """.trimIndent()
    }
}
