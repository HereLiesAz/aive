package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object DocumentsPresets {
    val Brief: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "brief", "worker",
        listOf(PortSpec("goal", PortType.Text, true), PortSpec("audience", PortType.Text, false), PortSpec("limits", PortType.Text, false)),
        PresetKit.out("brief", ArtifactKind.Requirement, "text/markdown", "brief/v1", "sections-brief"),
        "Write the brief for the goal given: what is to be done, for whom, within what limits, and how anyone will know it is done. Give it these headings, in this order: `## Goal`, `## Audience`, `## Limits`, `## Done when`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Report: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "report", "worker",
        listOf(PortSpec("brief", PortType.Text, true), PortSpec("findings", PortType.Research, false), PortSpec("sources", PortType.Research, false)),
        PresetKit.out("report", ArtifactKind.Research, "text/markdown", "report/v1", "sections-report"),
        "Write a report of the findings from the material given, with the evidence under each finding. Give it these headings, in this order: `## Summary`, `## Findings`, `## Limits`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        40_000L,
    )

    val Summary: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "summary", "worker",
        listOf(PortSpec("material", PortType.Any, true), PortSpec("reader", PortType.Text, false)),
        PresetKit.out("summary", ArtifactKind.Research, "text/markdown", "summary/v1", "summary-short"),
        "Summarise the material for the reader named, keeping what that reader needs and leaving the rest out. Keep it under about 6000 characters. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val OnePageExplainer: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "one-page-explainer", "worker",
        listOf(PortSpec("topic", PortType.Text, true), PortSpec("reader", PortType.Text, false), PortSpec("material", PortType.Research, false)),
        PresetKit.out("explainer", ArtifactKind.Research, "text/markdown", "one-page-explainer/v1", "explainer-short"),
        "Explain the topic to a reader who does not know it, on one page, using the material if there is any. Give it a heading, and keep it to one page (about 4500 characters at most). Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Faq: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "faq", "worker",
        listOf(PortSpec("material", PortType.Any, true), PortSpec("audience", PortType.Text, false)),
        PresetKit.out("faq", ArtifactKind.Research, "text/markdown", "faq/v1", "faq-questions"),
        "List the questions a reader of the material is likely to ask, each as a heading ending in a question mark, with a short answer taken from the material. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Glossary: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "glossary", "worker",
        listOf(PortSpec("material", PortType.Any, true)),
        PresetKit.out("glossary", ArtifactKind.Research, "text/csv", "glossary/v1", "columns-glossary"),
        "Define the terms the material uses, one row each. Hand back comma-separated text: a header row with exactly these columns: term, definition, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Translation: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "translation", "worker",
        listOf(PortSpec("text", PortType.Markdown, true), PortSpec("language", PortType.Text, true)),
        PresetKit.out("translation", ArtifactKind.Research, "text/markdown", "translation/v1", StepOutputValidators.MARKDOWN_DOCUMENT),
        "Translate the text into the language named. Keep its headings, lists, markup, numbers and order exactly as they are. Hand it back as Markdown. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        40_000L,
    )

    val all: List<StepTemplate> = listOf(Brief, Report, Summary, OnePageExplainer, Faq, Glossary, Translation)
}
