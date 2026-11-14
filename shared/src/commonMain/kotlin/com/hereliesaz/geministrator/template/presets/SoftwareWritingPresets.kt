package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object SoftwareWritingPresets {
    val Spec: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "spec", "worker",
        listOf(PortSpec("goal", PortType.Text, true), PortSpec("material", PortType.Any, false)),
        PresetKit.out("spec", ArtifactKind.Specification, "text/markdown", "spec/v1", "sections-spec"),
        "Say what the thing must do for the goal given, as statements anyone could check, and what is out of scope. Give it these headings, in this order: `## Purpose`, `## Requirements`, `## Out of scope`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        40_000L,
    )

    val ApiDoc: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "api-doc", "worker",
        listOf(PortSpec("source", PortType.Any, true)),
        PresetKit.out("doc", ArtifactKind.Specification, "text/markdown", "api-doc/v1", "sections-api-doc"),
        "Describe the interface in the source given: its inputs, outputs, limits and errors. Describe only what the source shows. Give it these headings, in this order: `## Inputs`, `## Outputs`, `## Errors`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Adr: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "adr", "worker",
        listOf(PortSpec("decision", PortType.Text, true), PortSpec("context", PortType.Any, false)),
        PresetKit.out("record", ArtifactKind.Architecture, "text/markdown", "adr/v1", "sections-adr"),
        "Record the one decision given: the context it was made in, the decision, and its consequences, good and bad. Give it these headings, in this order: `## Context`, `## Decision`, `## Consequences`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ChangelogEntry: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "changelog-entry", "worker",
        listOf(PortSpec("changes", PortType.Any, true)),
        PresetKit.out("entry", ArtifactKind.Release, "text/markdown", "changelog-entry/v1", "changelog-lines"),
        "Describe the changes given in a line or two each, as lines that begin with Added, Fixed or Changed. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ReleaseNotes: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "release-notes", "worker",
        listOf(PortSpec("changes", PortType.Any, true), PortSpec("reader", PortType.Text, false)),
        PresetKit.out("notes", ArtifactKind.Release, "text/markdown", "release-notes/v1", "sections-release-notes"),
        "Describe what is new, what is fixed and what the reader must do, for the reader named, from the changes given. Give it these headings, in this order: `## New`, `## Fixed`, `## Action needed`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val PrDescription: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "pr-description", "worker",
        listOf(PortSpec("change", PortType.Any, true), PortSpec("reason", PortType.Text, false)),
        PresetKit.out("description", ArtifactKind.PullRequest, "text/markdown", "pr-description/v1", "sections-pr-description"),
        "Describe the code change for the people who will review it: what it does, why, and how to check it. This is a description only; make no pull request. Give it these headings, in this order: `## What`, `## Why`, `## How to check`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val BugReport: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "bug-report", "worker",
        listOf(PortSpec("symptom", PortType.Text, true), PortSpec("material", PortType.Any, false)),
        PresetKit.out("report", ArtifactKind.FailureAnalysis, "text/markdown", "bug-report/v1", "sections-bug-report"),
        "State the expected and the actual behaviour of the symptom, the steps to reproduce it and the evidence you have. Guess no cause you cannot show. Give it these headings, in this order: `## Expected`, `## Actual`, `## Steps`, `## Evidence`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ReproSteps: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "repro-steps", "worker",
        listOf(PortSpec("symptom", PortType.Text, true), PortSpec("material", PortType.Any, false)),
        PresetKit.out("steps", ArtifactKind.FailureAnalysis, "text/markdown", "repro-steps/v1", "repro-numbered"),
        "Write numbered steps that bring the problem about, in a clean state, each one something a person can do. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(Spec, ApiDoc, Adr, ChangelogEntry, ReleaseNotes, PrDescription, BugReport, ReproSteps)
}
