package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.OutputSpec
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TemplateRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepOutputValidatorTest {
    private fun artifact(kind: ArtifactKind, text: String?, mediaType: String? = null) = ArtifactRef(
        id = ArtifactId("a"),
        kind = kind,
        taskRunId = TaskRunId("r"),
        label = "x",
        textContent = text ?: "",
        mediaType = mediaType,
        createdAtEpochMillis = 0,
    )

    private fun check(id: String, text: String, kind: ArtifactKind = ArtifactKind.Research) =
        StepOutputValidators.check(id, artifact(kind, text))

    @Test
    fun markdownNeedsAHeading() {
        assertNull(check(StepOutputValidators.MARKDOWN_DOCUMENT, "# Title\n\nBody"))
        assertNotNull(check(StepOutputValidators.MARKDOWN_DOCUMENT, "Just words"))
        assertNotNull(check(StepOutputValidators.MARKDOWN_DOCUMENT, "   "))
        assertNotNull(check(StepOutputValidators.MARKDOWN_DOCUMENT, "#nospace"))
        assertNotNull(check(StepOutputValidators.MARKDOWN_SECTIONED, "# One\ntext"))
        assertNull(check(StepOutputValidators.MARKDOWN_SECTIONED, "# One\n## Two"))
    }

    @Test
    fun markdownHasALengthCap() {
        val long = "# T\n" + "a".repeat(StepOutputValidators.MAX_DOCUMENT_CHARS)
        assertNotNull(check(StepOutputValidators.MARKDOWN_DOCUMENT, long))
    }

    @Test
    fun htmlRejectsExternalScriptsAndStylesheets() {
        assertNull(check(StepOutputValidators.HTML_PAGE, "<html><body><p>hi</p></body></html>"))
        assertNotNull(check(StepOutputValidators.HTML_PAGE, "plain words"))
        assertNotNull(check(StepOutputValidators.HTML_PAGE, "<html><body><script src=\"https://x.test/a.js\"></script></body></html>"))
        assertNotNull(check(StepOutputValidators.HTML_PAGE, "<html><head><link rel=\"stylesheet\" href=\"a.css\"></head><body></body></html>"))
        assertNull(check(StepOutputValidators.HTML_PAGE, "<html><body><a href=\"https://x.test\">x</a></body></html>"))
        assertNotNull(check(StepOutputValidators.HTML_CONTAINED, "<html><body><a href=\"https://x.test\">x</a></body></html>"))
    }

    @Test
    fun tableNeedsAHeaderAndRows() {
        assertNull(check(StepOutputValidators.TABLE_CSV, "a,b\n1,2\n3,4"))
        assertNotNull(check(StepOutputValidators.TABLE_CSV, "a,b"))
        assertNotNull(check(StepOutputValidators.TABLE_CSV, "a,\"b\n1,2"))
        assertNotNull(check(StepOutputValidators.TABLE_CSV_FILLED, "a,\n1,2"))
        assertNull(check(StepOutputValidators.TABLE_CSV, "a,\n1,2"))
    }

    @Test
    fun verdictNeedsAVerdictLine() {
        assertNull(check(StepOutputValidators.VERDICT, "VERDICT: approve\nfine", ArtifactKind.Verification))
        assertNotNull(check(StepOutputValidators.VERDICT, "looks fine", ArtifactKind.Verification))
    }

    @Test
    fun changeResultStatesTargetAndChange() {
        assertNull(check(StepOutputValidators.CHANGE_RESULT, "TARGET: row 4\nCHANGED: status set to done", ArtifactKind.Verification))
        assertNotNull(check(StepOutputValidators.CHANGE_RESULT, "Done.", ArtifactKind.Verification))
        assertNotNull(check(StepOutputValidators.CHANGE_RESULT, "TARGET: row 4", ArtifactKind.Verification))
    }

    @Test
    fun anUnknownValidatorIsAViolation() {
        assertNotNull(check("run-this-script", "# T"))
        assertFalse(StepOutputValidators.isKnown("run-this-script"))
    }

    @Test
    fun tighteningIsWithinAFamilyAndUpward() {
        assertTrue(StepOutputValidators.isTighter(StepOutputValidators.MARKDOWN_DOCUMENT, StepOutputValidators.MARKDOWN_SECTIONED))
        assertFalse(StepOutputValidators.isTighter(StepOutputValidators.MARKDOWN_SECTIONED, StepOutputValidators.MARKDOWN_DOCUMENT))
        assertFalse(StepOutputValidators.isTighter(StepOutputValidators.MARKDOWN_DOCUMENT, StepOutputValidators.HTML_CONTAINED))
        assertTrue(StepOutputValidators.isSameOrTighter(StepOutputValidators.TABLE_CSV, StepOutputValidators.TABLE_CSV))
    }

    private val spec = OutputSpec("document", ArtifactKind.Research, "text/markdown", "markdown/v1", StepOutputValidators.MARKDOWN_DOCUMENT)
    private val contract = TaskContract(TemplateRef("write-document", 1), outputs = listOf(spec))

    @Test
    fun aMissingOutputFailsTheContract() {
        assertEquals(1, checkStepOutputs(contract, emptyList()).size)
        assertEquals(1, checkStepOutputs(contract, listOf(artifact(ArtifactKind.Design, "# T"))).size)
    }

    @Test
    fun aMismatchedMediaTypeFailsAndAMissingOneDoesNot() {
        assertEquals(1, checkStepOutputs(contract, listOf(artifact(ArtifactKind.Research, "# T", "text/html"))).size)
        assertTrue(checkStepOutputs(contract, listOf(artifact(ArtifactKind.Research, "# T", "text/markdown; charset=utf-8"))).isEmpty())
        assertTrue(checkStepOutputs(contract, listOf(artifact(ArtifactKind.Research, "# T"))).isEmpty())
    }

    @Test
    fun aContractWithNoOutputsAlwaysPasses() {
        assertTrue(checkStepOutputs(TaskContract(TemplateRef("x", 1)), emptyList()).isEmpty())
    }
}
