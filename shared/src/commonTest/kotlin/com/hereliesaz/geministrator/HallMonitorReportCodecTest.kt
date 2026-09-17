package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorEvidence
import com.hereliesaz.geministrator.domain.HallMonitorFinding
import com.hereliesaz.geministrator.domain.HallMonitorReport
import com.hereliesaz.geministrator.domain.HallMonitorScope
import com.hereliesaz.geministrator.domain.HallMonitorSolution
import com.hereliesaz.geministrator.domain.HallMonitorTestDesign
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class HallMonitorReportCodecTest {
    @Test
    fun acceptsRawAndLegacyFencedJson() {
        val report = sampleReport()
        val encoded = Json.encodeToString(HallMonitorReport.serializer(), report)

        assertEquals(report, decodeHallMonitorReportPayload(encoded))
        assertEquals(report, decodeHallMonitorReportPayload("```json\n$encoded\n```"))
    }

    private fun sampleReport(): HallMonitorReport {
        val evidence = HallMonitorEvidence("claim", "source", "window", "population", "value")
        val test = HallMonitorTestDesign(
            name = "test",
            hypothesis = "hypothesis",
            fixture = "fixture",
            controls = listOf("control"),
            measurements = listOf("measurement"),
            expectedFailureModes = listOf("failure"),
        )
        return HallMonitorReport(
            reportId = "report",
            title = "Report",
            measuredFromEpochMillis = 1L,
            measuredToEpochMillis = 2L,
            findings = listOf(
                HallMonitorFinding(
                    id = "finding",
                    scope = HallMonitorScope.Swarm,
                    subject = "subject",
                    observation = "observation",
                    evidence = listOf(evidence),
                    counterEvidence = listOf(evidence.copy(claim = "counter")),
                    falsificationCriteria = listOf("criterion"),
                    solutions = listOf(
                        HallMonitorSolution("one", HallMonitorAction.Benchmark, "target", "why", listOf("tradeoff"), listOf("validate")),
                        HallMonitorSolution("two", HallMonitorAction.Retain, "target", "why", listOf("tradeoff"), listOf("validate")),
                    ),
                ),
            ),
            orchestrationTests = listOf(test),
            memoryTests = listOf(test),
            summary = "summary",
        )
    }
}
