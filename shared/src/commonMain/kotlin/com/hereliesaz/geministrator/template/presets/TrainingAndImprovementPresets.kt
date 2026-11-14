package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object TrainingAndImprovementPresets {
    val PracticeResult: StepTemplate = PresetKit.make(
        BuiltInTemplates.CheckWork, "practice-result", "reviewer",
        listOf(PortSpec("tasks", PortType.Any, true), PortSpec("answers", PortType.Any, true)),
        PresetKit.out("scores", ArtifactKind.Verification, "text/plain", "practice-result/v1", "practice-lines"),
        "Score a generic role and a draft specialist on the same practice tasks. Reply `GENERIC: <met>/<of>` and `SPECIALIST: <met>/<of>`, over the same number of tasks, then your findings one per line. Judge only; change nothing. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val TrainingReport: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "training-report", "worker",
        listOf(PortSpec("results", PortType.Any, true), PortSpec("facts", PortType.Any, true)),
        PresetKit.out("report", ArtifactKind.Verification, "text/plain", "training-report/v1", "training-report-lines"),
        "Write the Nanny's training report from the results and facts given: `RULING: PASS`, `RULING: REFINE` or `RULING: STOP`, then `REASON: <one sentence citing a listed fact in square brackets, like [f2]>`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val SpecialistLessons: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "specialist-lessons", "worker",
        listOf(PortSpec("material", PortType.Research, true), PortSpec("role", PortType.Text, true)),
        PresetKit.out("lessons", ArtifactKind.Research, "text/plain", "specialist-lessons/v1", "lesson-lines"),
        "Draft short lessons for a specialist variant of the role from the material given, each as `LESSON: <text> | SOURCE: <label> | FETCHED: <yyyy-mm-dd>`. A lesson with no source is not a lesson. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val NannyFinding: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "nanny-finding", "worker",
        listOf(PortSpec("evidence", PortType.Any, true)),
        PresetKit.out("finding", ArtifactKind.HallMonitorReport, "application/json", "nanny-finding/v1", "nanny-report-json"),
        "Write one finding of the Nanny's report from the evidence given: what was seen, about which step, and what could be done, as the report's JSON. Hand back the JSON and nothing else. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val RevisionProposal: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "revision-proposal", "worker",
        listOf(PortSpec("current", PortType.Any, true), PortSpec("evidence", PortType.Any, true), PortSpec("aim", PortType.Text, true)),
        PresetKit.out("revision", ArtifactKind.Specification, "application/json", "revision-proposal/v1", "revision-draft-json"),
        "Propose one change to the template or role given, as one JSON object with `target` (`step:<id>@<version>` or `role:<id>@<version>`), `intent` (aim, sentence, hypothesis, measure), the changed fields and `evidenceSteps`. Change only wording, criteria, narrowed tools or tightened limits. Hand back the JSON and nothing else. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(PracticeResult, TrainingReport, SpecialistLessons, NannyFinding, RevisionProposal)
}
