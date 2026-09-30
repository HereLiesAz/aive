package com.hereliesaz.conveyance.auditor

import com.hereliesaz.conveyance.AuditReport
import com.hereliesaz.conveyance.ElementId
import com.hereliesaz.conveyance.Grade
import com.hereliesaz.conveyance.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditReportTest {

    private fun verdict(grade: Grade) = Verdict(ElementId("e"), grade, "predicted", "actual", "")

    /**
     * The asymmetry is the whole point of the grading scheme. Someone who cannot tell what a
     * control does proceeds carefully. Someone confidently wrong proceeds, and the interface put
     * them there.
     */
    @Test
    fun `wrong is counted separately from no idea, because it is worse`() {
        val report = AuditReport(
            surface = "gallery",
            verdicts = listOf(
                verdict(Grade.Right),
                verdict(Grade.Right),
                verdict(Grade.NoIdea),
                verdict(Grade.Wrong),
            ),
            omissions = emptyList(),
        )

        assertEquals(2, report.right)
        assertEquals(1, report.noIdea)
        assertEquals(1, report.wrong)
        assertEquals(1, report.misleading, "Only wrong answers count as misleading.")
        assertEquals(0.5f, report.predictable)
    }

    /**
     * An empty verdict list means one of two very different things — a surface with nothing
     * interactive on it, or a judge that was asked and said nothing useful — and [AuditReport]
     * cannot tell which just by looking at the list. `predictable` reports that honestly, as
     * `null`, rather than picking one of the two meanings and reporting a fabricated perfect score.
     */
    @Test
    fun `nothing to grade is reported as nothing, not as a perfect score`() {
        val silent = AuditReport("empty", emptyList(), emptyList())
        assertEquals(null, silent.predictable, "There is no fraction to compute here.")
        assertEquals(0, silent.misleading)
    }

    @Test
    fun `omissions are carried separately from predictions`() {
        val report = AuditReport(
            surface = "gallery",
            verdicts = listOf(verdict(Grade.Right)),
            omissions = listOf("Deleting is permanent and nothing on screen says so."),
        )
        assertEquals(1f, report.predictable, "A perfectly predictable screen can still hide the stakes.")
        assertTrue(report.omissions.isNotEmpty())
    }
}
