package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object SwarmPlansAndTeachingPresets {
    val SwarmPlan: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "swarm-plan", "worker",
        listOf(PortSpec("goal", PortType.Text, true), PortSpec("requirements", PortType.Text, true), PortSpec("roles", PortType.Any, true)),
        PresetKit.out("plan", ArtifactKind.TaskPlan, "application/json", "swarm-plan/v1", "swarm-plan-json"),
        "Write the plan for the goal as one JSON object `{\"steps\":[{\"id\",\"name\",\"objective\",\"roleId\",\"dependsOn\",\"requiresHumanApproval\"}]}`: ordered steps, each with a role from the list given, what it depends on and whether the keeper approves it first. Meet every requirement given. Hand back the JSON and nothing else. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val TutorialLesson: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "tutorial-lesson", "worker",
        listOf(PortSpec("target", PortType.Text, true), PortSpec("facts", PortType.Any, true)),
        PresetKit.out("lesson", ArtifactKind.Design, "application/json", "tutorial-lesson/v1", "lesson-json"),
        "Write one short lesson about the one control the target names, as one JSON object in the lesson format, with one checkpoint. Use only words and controls the facts given show. Hand back the JSON and nothing else. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val TutorialCheckIn: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "tutorial-check-in", "worker",
        listOf(PortSpec("lesson", PortType.Json, true)),
        PresetKit.out("checkin", ArtifactKind.Design, "application/json", "tutorial-check-in/v1", "lesson-json"),
        "Write a short question that confirms the keeper saw what the lesson taught, as one JSON object in the lesson format. It is not a quiz. Hand back the JSON and nothing else. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val QueenRewordedStep: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "queen-reworded-step", "worker",
        listOf(PortSpec("step", PortType.Any, true), PortSpec("reason", PortType.Text, true)),
        PresetKit.out("wording", ArtifactKind.Requirement, "text/plain", "queen-reworded-step/v1", "reworded-step"),
        "Write a new job wording for the step given that answers the reason given, as plain text. Keep its output and its limits as they are. Keep it under 4000 characters. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(SwarmPlan, TutorialLesson, TutorialCheckIn, QueenRewordedStep)
}
