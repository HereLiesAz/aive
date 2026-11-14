package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object ChecksAndEditsPresets {
    val Critique: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "critique", "reviewer",
        listOf(PortSpec("work", PortType.Any, true), PortSpec("focus", PortType.Text, false)),
        PresetKit.out("critique", ArtifactKind.Review, "text/markdown", "critique/v1", "sections-critique"),
        "Find what is wrong with the work, each finding with its evidence from the work, and say what is sound. Judge only; change nothing. Give it these headings, in this order: `## Findings`, `## Sound`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Proofread: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "proofread", "worker",
        listOf(PortSpec("draft", PortType.Markdown, true), PortSpec("style", PortType.Text, false)),
        PresetKit.out("edited", ArtifactKind.Research, "text/markdown", "proofread/v1", "sections-proofread"),
        "Correct the draft's spelling, grammar and punctuation (and follow the style named, if any). Hand back the edited text, then list each change you made. Give it these headings, in this order: `## Edited text`, `## Changes`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val AccessibilityCheck: StepTemplate = PresetKit.make(
        BuiltInTemplates.CheckWork, "accessibility-check", "reviewer",
        listOf(PortSpec("page", PortType.Html, true)),
        PresetKit.out("verdict", ArtifactKind.Verification, "text/plain", "accessibility-check/v1", StepOutputValidators.VERDICT),
        "List the barriers a person could meet on the page (missing text alternatives, headings, labels, contrast, keyboard use). Begin with `VERDICT: approve` or `VERDICT: reject`, then one line per barrier. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ClarifyingQuestions: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "clarifying-questions", "worker",
        listOf(PortSpec("brief", PortType.Text, true)),
        PresetKit.out("questions", ArtifactKind.Requirement, "text/markdown", "clarifying-questions/v1", "questions-short"),
        "List what is missing from the brief as short numbered questions, at most seven. Ask nothing the brief already answers. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(Critique, Proofread, AccessibilityCheck, ClarifyingQuestions)
}
