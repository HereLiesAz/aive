package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object SoftwareChangesPresets {
    val TestPlan: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "test-plan", "worker",
        listOf(PortSpec("spec", PortType.Any, true)),
        PresetKit.out("plan", ArtifactKind.TestPlan, "text/markdown", "test-plan/v1", "sections-test-plan"),
        "Say what will be tested for the spec given, how, and what would count as passing. Give it these headings, in this order: `## Scope`, `## Cases`, `## Pass criteria`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val TestCases: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "test-cases", "worker",
        listOf(PortSpec("spec", PortType.Any, true)),
        PresetKit.out("cases", ArtifactKind.BehavioralTest, "text/csv", "test-cases/v1", "columns-test-cases"),
        "List test cases for the spec given: an id, the steps and the expected result. One row per case. Hand back comma-separated text: a header row with exactly these columns: id, steps, expected, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val TestsOnBranch: StepTemplate = PresetKit.make(
        BuiltInTemplates.PrepareFilesForRepository, "tests-on-branch", "engineer",
        listOf(PortSpec("spec", PortType.Any, true), PortSpec("files", PortType.Text, false)),
        PresetKit.out("tests", ArtifactKind.TestCode, "text/plain", "tests-on-branch/v1", StepOutputValidators.CHANGE_ON_BRANCH),
        "Write the tests the spec describes on a dedicated branch (the files input may name where). Never weaken or delete an existing test. Do not push and do not merge. Hand back two lines, `BRANCH: <the branch>` and `FILES: <the files written, comma-separated>`.",
        60_000L,
    )

    val all: List<StepTemplate> = listOf(TestPlan, TestCases, TestsOnBranch)
}
