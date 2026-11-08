package com.hereliesaz.geministrator.workflow.refinement

import com.hereliesaz.geministrator.memory.procedural.ProcedureSideEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LadderArtifactTest {
    private val coarse = LadderArtifact(
        level = 1,
        of = 2,
        summary = "Verify the build",
        decisions = listOf(LadderDecision("d1", "Run the debug build"), LadderDecision("d2", "Report failures")),
    )

    private val fine = LadderArtifact(
        level = 2,
        of = 2,
        summary = "Steps",
        decisions = listOf(
            LadderDecision("e1", "Build then report", refines = listOf("d1", "d2")),
        ),
        steps = listOf(
            LadderStep(
                id = "build",
                summary = "Run the build",
                acceptance = listOf("exit status 0"),
                operation = LadderOperation("script", "verify.build", ProcedureSideEffect.ReadOnly, mapOf("target" to "debug")),
            ),
            LadderStep(id = "report", summary = "Report", dependsOn = listOf("build")),
        ),
    )

    @Test
    fun aSoundPairValidatesAndRoundTripsThroughTheCodec() {
        assertEquals(emptyList(), LadderArtifactValidator.validate(coarse, 1, 2))
        assertEquals(emptyList(), LadderArtifactValidator.validate(fine, 2, 2, coarse))
        val parsed = LadderArtifactCodec.decode("```json\n${LadderArtifactCodec.encode(fine)}\n```")
        assertIs<LadderParse.Parsed>(parsed)
        assertEquals(fine, parsed.artifact)
    }

    @Test
    fun aFinerLevelMustRefineEveryCoarserDecisionAndOnlyKnownOnes() {
        val missing = fine.copy(decisions = listOf(LadderDecision("e1", "Only build", refines = listOf("d1"))))
        assertTrue(LadderArtifactValidator.validate(missing, 2, 2, coarse).any { "not refined" in it })
        val unknown = fine.copy(decisions = listOf(LadderDecision("e1", "x", refines = listOf("d1", "d2", "zz"))))
        assertTrue(LadderArtifactValidator.validate(unknown, 2, 2, coarse).any { "unknown" in it })
        val rooted = coarse.copy(decisions = listOf(LadderDecision("d1", "x", refines = listOf("q"))))
        assertTrue(LadderArtifactValidator.validate(rooted, 1, 2).any { "refine nothing" in it })
    }

    @Test
    fun onlyTheFinestLevelListsStepsAndTheyStayFiniteAndAcyclic() {
        assertTrue(LadderArtifactValidator.validate(coarse.copy(steps = fine.steps), 1, 2).any { "only the finest" in it })
        assertTrue(LadderArtifactValidator.validate(fine.copy(steps = emptyList()), 2, 2, coarse).any { "must list steps" in it })
        val forward = fine.copy(steps = listOf(LadderStep("a", "a", dependsOn = listOf("b")), LadderStep("b", "b")))
        assertTrue(LadderArtifactValidator.validate(forward, 2, 2, coarse).any { "not an earlier step" in it })
        val self = fine.copy(steps = listOf(LadderStep("a", "a", dependsOn = listOf("a"))))
        assertTrue(LadderArtifactValidator.validate(self, 2, 2, coarse).any { "not an earlier step" in it })
        val twice = fine.copy(steps = listOf(LadderStep("a", "a"), LadderStep("a", "again")))
        assertTrue(LadderArtifactValidator.validate(twice, 2, 2, coarse).any { "duplicate step" in it })
    }

    @Test
    fun anOperationIsHeldToTheProcedureStepBounds() {
        val bad = fine.copy(
            steps = listOf(
                LadderStep("a", "a", operation = LadderOperation("", "op", ProcedureSideEffect.ReadOnly)),
            ),
        )
        assertTrue(LadderArtifactValidator.validate(bad, 2, 2, coarse).any { "operation" in it })
    }

    @Test
    fun malformedOrOffSchemaTextIsRejectedNotGuessed() {
        assertIs<LadderParse.Malformed>(LadderArtifactCodec.decode("A plan in prose."))
        assertIs<LadderParse.Malformed>(LadderArtifactCodec.decode("{\"level\":1,\"of\":1,\"summary\":\"s\",\"extra\":1}"))
        assertTrue(LadderArtifactValidator.validate(coarse.copy(schema = "other"), 1, 2).any { "schema" in it })
        assertTrue(LadderArtifactValidator.validate(coarse.copy(level = 2), 1, 2).any { "level must be 1" in it })
        assertTrue(LadderArtifactValidator.validate(coarse.copy(summary = " "), 1, 2).any { "blank" in it })
    }

    @Test
    fun generatedIdsParseBackAndOtherIdsDoNot() {
        val ids = RefinementLadderIds
        assertEquals(RefinementLadderIds.Parsed("work", RefinementLadderIds.Kind.Level, 2), ids.parse(ids.level("work", 2)))
        assertEquals(RefinementLadderIds.Parsed("work", RefinementLadderIds.Kind.Gate, 1), ids.parse(ids.gate("work", 1)))
        assertEquals(RefinementLadderIds.Parsed("a--rl-b", RefinementLadderIds.Kind.Verifier, null), ids.parse(ids.verifier("a--rl-b")))
        assertEquals(null, ids.parse("work"))
        assertEquals(null, ids.parse("work--rl-level-x"))
        assertEquals(null, ids.parse("work--rl-other"))
    }
}
