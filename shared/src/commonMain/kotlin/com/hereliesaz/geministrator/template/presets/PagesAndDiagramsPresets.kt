package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object PagesAndDiagramsPresets {
    val LandingPage: StepTemplate = PresetKit.make(
        BuiltInTemplates.MakePage, "landing-page", "worker",
        listOf(PortSpec("brief", PortType.Text, true), PortSpec("content", PortType.Any, false)),
        PresetKit.out("page", ArtifactKind.Design, "text/html", "landing-page/v1", StepOutputValidators.HTML_CONTAINED),
        "Make one page that says what the thing is and what to do next, for the brief given. Make it one self-contained HTML document: inline styles only, no external scripts, stylesheets, fonts or images, and no address that points outside the page. Give it a language, a title and one h1. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        40_000L,
    )

    val DashboardPage: StepTemplate = PresetKit.make(
        BuiltInTemplates.MakePage, "dashboard-page", "worker",
        listOf(PortSpec("data", PortType.Table, true), PortSpec("brief", PortType.Text, false)),
        PresetKit.out("page", ArtifactKind.Design, "text/html", "dashboard-page/v1", StepOutputValidators.HTML_CONTAINED),
        "Show the figures given on one static page, with a table, and name where each figure came from. Invent no figure. Make it one self-contained HTML document: inline styles only, no external scripts, stylesheets, fonts or images, and no address that points outside the page. Give it a language, a title and one h1. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        40_000L,
    )

    val DiagramFlowchart: StepTemplate = PresetKit.make(
        BuiltInTemplates.MakeDiagram, "diagram-flowchart", "worker",
        listOf(PortSpec("description", PortType.Text, true)),
        PresetKit.out("diagram", ArtifactKind.Design, "text/vnd.mermaid", "diagram-flowchart/v1", StepOutputValidators.MERMAID_FLOWCHART),
        "Draw the flow or structure the description gives as a Mermaid flowchart, beginning with a line such as `flowchart TD`. Hand back the Mermaid text only. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(LandingPage, DashboardPage, DiagramFlowchart)
}
