package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object PlansAndRecordsPresets {
    val Plan: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "plan", "worker",
        listOf(PortSpec("goal", PortType.Text, true), PortSpec("limits", PortType.Text, false)),
        PresetKit.out("plan", ArtifactKind.TaskPlan, "text/markdown", "plan/v1", StepOutputValidators.PLAN_STEPS),
        "Break the goal into numbered steps. Under each step give a line `PRODUCES: <what it makes>` and, if it must wait, a line `AFTER: <step numbers>`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Timeline: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "timeline", "worker",
        listOf(PortSpec("items", PortType.Any, true), PortSpec("limits", PortType.Text, false)),
        PresetKit.out("timeline", ArtifactKind.TaskPlan, "text/csv", "timeline/v1", "columns-timeline"),
        "Put the items in order with a start, an end and what each waits for. Use dates only if the items or limits give them. Hand back comma-separated text: a header row with exactly these columns: item, start, end, waits for, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val MeetingNotes: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "meeting-notes", "worker",
        listOf(PortSpec("notes", PortType.Text, true), PortSpec("attendees", PortType.Text, false)),
        PresetKit.out("notes", ArtifactKind.Research, "text/markdown", "meeting-notes/v1", "sections-meeting-notes"),
        "Record the decisions, the actions (each with who and by when, if stated) and the open questions from the notes given. Give it these headings, in this order: `## Decisions`, `## Actions`, `## Open questions`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val DecisionMemo: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "decision-memo", "worker",
        listOf(PortSpec("question", PortType.Text, true), PortSpec("material", PortType.Any, false)),
        PresetKit.out("memo", ArtifactKind.Research, "text/markdown", "decision-memo/v1", "sections-decision-memo"),
        "State the choice to be made, the options with what is for and against each, a recommendation and its reasons, and the risks. Give it these headings, in this order: `## Decision`, `## Options`, `## Recommendation`, `## Risks`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val RiskRegister: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "risk-register", "worker",
        listOf(PortSpec("plan", PortType.Any, true)),
        PresetKit.out("risks", ArtifactKind.Research, "text/csv", "risk-register/v1", "columns-risks"),
        "List what could go wrong in the plan, how it would show, how bad it would be and what reduces it. Hand back comma-separated text: a header row with exactly these columns: risk, signal, impact, reduction, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(Plan, Timeline, MeetingNotes, DecisionMemo, RiskRegister)
}
