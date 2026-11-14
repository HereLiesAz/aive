package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskRunId
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutputKindChecksTest {
    private fun check(id: String, text: String, kind: ArtifactKind = ArtifactKind.Research, meta: Map<String, String> = emptyMap()) =
        StepOutputValidators.check(id, ArtifactRef(ArtifactId("a"), kind, TaskRunId("r"), "x", textContent = text, metadata = meta, createdAtEpochMillis = 0))

    private fun ok(id: String, text: String, kind: ArtifactKind = ArtifactKind.Research) = assertNull(check(id, text, kind), id)
    private fun bad(id: String, text: String, kind: ArtifactKind = ArtifactKind.Research) = assertNotNull(check(id, text, kind), id)

    @Test
    fun json() {
        ok(StepOutputValidators.JSON_VALID, "[1,2]")
        ok(StepOutputValidators.JSON_VALID, "```json\n{\"a\":1}\n```")
        bad(StepOutputValidators.JSON_VALID, "[]")
        bad(StepOutputValidators.JSON_VALID, "3")
        bad(StepOutputValidators.JSON_VALID, "{nope")
    }

    @Test
    fun mermaidAndDiff() {
        ok(StepOutputValidators.MERMAID_DIAGRAM, "sequenceDiagram\n  A->>B: hi")
        bad(StepOutputValidators.MERMAID_DIAGRAM, "sequenceDiagram")
        bad(StepOutputValidators.MERMAID_DIAGRAM, "A diagram")
        ok(StepOutputValidators.DIFF_UNIFIED, "--- a/x.txt\n+++ b/x.txt\n@@ -1,2 +1,2 @@\n-old\n+new\n same")
        bad(StepOutputValidators.DIFF_UNIFIED, "--- a/x\n+++ b/x\n@@ -1 +1 @@\n same")
        bad(StepOutputValidators.DIFF_UNIFIED, "just words")
    }

    @Test
    fun checklistPlanScript() {
        ok(StepOutputValidators.CHECKLIST, "- [ ] one\n- [x] two")
        bad(StepOutputValidators.CHECKLIST, "- one")
        ok(StepOutputValidators.PLAN_STEPS, "1. Dig\nPRODUCES: a hole\n2. Plant\nPRODUCES: a tree\nAFTER: 1")
        bad(StepOutputValidators.PLAN_STEPS, "1. Dig\n3. Plant\nPRODUCES: x")
        bad(StepOutputValidators.PLAN_STEPS, "1. Dig\n2. Plant")
        ok(StepOutputValidators.SCRIPT_SCENES, "INT. KITCHEN - DAY\nA kettle boils.")
        bad(StepOutputValidators.SCRIPT_SCENES, "A kettle boils.")
    }

    @Test
    fun verdictWithCriteriaAndChangeOnBranch() {
        ok(StepOutputValidators.VERDICT_CRITERIA, "VERDICT: approve\nCRITERION 1: met - fine\nCRITERION 2: unmet - gap", ArtifactKind.Verification)
        bad(StepOutputValidators.VERDICT_CRITERIA, "VERDICT: approve", ArtifactKind.Verification)
        bad(StepOutputValidators.VERDICT_CRITERIA, "CRITERION 1: met", ArtifactKind.Verification)
        ok(StepOutputValidators.CHANGE_ON_BRANCH, "BRANCH: aive/x\nFILES: a.kt", ArtifactKind.TestCode)
        bad(StepOutputValidators.CHANGE_ON_BRANCH, "BRANCH: main\nFILES: a.kt", ArtifactKind.TestCode)
        assertNotNull(check(StepOutputValidators.CHANGE_ON_BRANCH, "BRANCH: aive/x\nFILES: a.kt", ArtifactKind.TestCode, mapOf("branch" to "main")))
    }

    @Test
    fun namedSectionsColumnsAndMarkers() {
        ok("sections-brief", "## Goal\na\n## Audience\nb\n## Limits\nc\n## Done when\nd")
        bad("sections-brief", "## Goal\na\n## Audience\nb")
        ok("columns-glossary", "term,definition\nbee,an insect")
        bad("columns-glossary", "word,meaning\nbee,an insect")
        ok("email-marked", "Subject: Hello\n\nHi")
        bad("email-marked", "Hello\nSubject: late")
        ok("questions-short", "1. Who?\n2. When?")
        bad("questions-short", (1..8).joinToString("\n") { "$it. q" })
        ok("policy-marked", "DRAFT for review: not advice.\n## Scope\nx\n## Rules\ny")
        bad("policy-marked", "## Scope\nx\n## Rules\ny")
        ok("faq-questions", "## What is it?\nx\n## Why?\ny")
        ok("changelog-lines", "- Added a thing\n- Fixed a bug")
        bad("reworded-step", "x".repeat(4_001))
    }

    @Test
    fun theAppsOwnOutputs() {
        ok("practice-lines", "GENERIC: 2/5\nSPECIALIST: 4/5")
        bad("practice-lines", "GENERIC: 2/5\nSPECIALIST: 4/6")
        ok("training-report-lines", "RULING: PASS\nREASON: It beat the generic role. [f2]")
        bad("training-report-lines", "RULING: PASS\nREASON: Because.")
        ok("lesson-lines", "LESSON: Check the date | SOURCE: docs | FETCHED: 2026-01-02")
        bad("lesson-lines", "LESSON: no source")
        ok("swarm-plan-json", "{\"steps\":[{\"id\":\"a\",\"name\":\"A\",\"objective\":\"o\",\"roleId\":\"r\"}]}")
        bad("swarm-plan-json", "{\"steps\":[{\"id\":\"a\",\"name\":\"A\",\"objective\":\"o\",\"roleId\":\"r\",\"dependsOn\":[\"z\"]}]}")
        bad("lesson-json", "not json")
        bad("revision-draft-json", "{\"target\":\"nonsense\"}")
        assertTrue(StepOutputValidators.isTighter(StepOutputValidators.MARKDOWN_SECTIONED, "sections-brief"))
        assertTrue(StepOutputValidators.isTighter(StepOutputValidators.TABLE_CSV_FILLED, "columns-risks"))
    }
}
