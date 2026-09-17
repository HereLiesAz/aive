package com.hereliesaz.geministrator.domain

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HallMonitorReportTest {
    @Test
    fun findingRequiresCounterEvidenceAndMultipleSolutions() {
        val evidence = HallMonitorEvidence(
            claim = "latency rose while verified progress flattened",
            source = "provider usage events",
            measurementWindow = "runs 10-30",
            population = "implementation tasks",
            value = "+35% latency",
        )
        val solution = HallMonitorSolution(
            title = "Benchmark alternate model",
            action = HallMonitorAction.Benchmark,
            target = "implementation model",
            rationale = "Separate model limits from workflow/prompt effects.",
            tradeoffs = listOf("Adds benchmark cost"),
            validationTests = listOf("Replay held-out tasks with equal context"),
        )

        val noCounterEvidence = assertFailsWith<IllegalArgumentException> {
            HallMonitorFinding(
                id = "plateau",
                scope = HallMonitorScope.Model,
                subject = "implementation model",
                observation = "Progress plateau",
                evidence = listOf(evidence),
                counterEvidence = emptyList(),
                falsificationCriteria = listOf("Held-out tasks regain verified progress"),
                solutions = listOf(solution, solution.copy(title = "Rewrite routing", action = HallMonitorAction.Reroute)),
            )
        }
        assertTrue(noCounterEvidence.message.orEmpty().contains("counter-evidence"))

        val oneSolution = assertFailsWith<IllegalArgumentException> {
            HallMonitorFinding(
                id = "plateau",
                scope = HallMonitorScope.Model,
                subject = "implementation model",
                observation = "Progress plateau",
                evidence = listOf(evidence),
                counterEvidence = listOf(evidence.copy(claim = "Some tasks still improve")),
                falsificationCriteria = listOf("Held-out tasks regain verified progress"),
                solutions = listOf(solution),
            )
        }
        assertTrue(oneSolution.message.orEmpty().contains("at least two"))
    }

    @Test
    fun reportRequiresTestsForOrchestrationAndMemoryLayers() {
        val finding = validFinding()
        val test = HallMonitorTestDesign(
            name = "control",
            hypothesis = "simpler routing preserves verified quality",
            fixture = "held-out workflow corpus",
            controls = listOf("current routing"),
            measurements = listOf("verified completion per token"),
            expectedFailureModes = listOf("quality regression"),
        )

        assertFailsWith<IllegalArgumentException> {
            HallMonitorReport(
                reportId = "r1",
                title = "Efficiency review",
                measuredFromEpochMillis = 1L,
                measuredToEpochMillis = 2L,
                findings = listOf(finding),
                orchestrationTests = listOf(test),
                memoryTests = emptyList(),
                summary = "One model may be plateauing.",
            )
        }
    }

    private fun validFinding(): HallMonitorFinding {
        val evidence = HallMonitorEvidence(
            claim = "verified progress flattened",
            source = "workflow events",
            measurementWindow = "runs 1-20",
            population = "all implementation tasks",
        )
        fun solution(title: String, action: HallMonitorAction) = HallMonitorSolution(
            title = title,
            action = action,
            target = "implementation path",
            rationale = "Test a distinct intervention.",
            tradeoffs = listOf("Consumes evaluation time"),
            validationTests = listOf("Run held-out workflow benchmark"),
        )
        return HallMonitorFinding(
            id = "f1",
            scope = HallMonitorScope.Swarm,
            subject = "implementation path",
            observation = "Additional tokens no longer improve verified output.",
            evidence = listOf(evidence),
            counterEvidence = listOf(evidence.copy(claim = "A minority of long tasks still improve")),
            falsificationCriteria = listOf("Verified progress resumes at equal spend"),
            solutions = listOf(
                solution("Replace specialist", HallMonitorAction.Replace),
                solution("Restructure workflow", HallMonitorAction.Restructure),
            ),
        )
    }
}
