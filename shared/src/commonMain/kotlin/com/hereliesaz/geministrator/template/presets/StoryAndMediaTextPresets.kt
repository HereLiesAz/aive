package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object StoryAndMediaTextPresets {
    val ShortFilmOutline: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "short-film-outline", "worker",
        listOf(PortSpec("idea", PortType.Text, true), PortSpec("length", PortType.Text, false)),
        PresetKit.out("outline", ArtifactKind.Requirement, "text/markdown", "short-film-outline/v1", "sections-film-outline"),
        "Outline a short film from the idea: the idea in a sentence, the tone, and the beats in order. Give it these headings, in this order: `## Idea`, `## Tone`, `## Beats`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val Script: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "script", "worker",
        listOf(PortSpec("outline", PortType.Markdown, true)),
        PresetKit.out("script", ArtifactKind.Research, "text/markdown", "script/v1", StepOutputValidators.SCRIPT_SCENES),
        "Write the scenes of the outline with what is seen and heard. Begin each scene with a heading such as `INT. KITCHEN - DAY`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        40_000L,
    )

    val StoryboardText: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "storyboard-text", "worker",
        listOf(PortSpec("script", PortType.Script, true)),
        PresetKit.out("storyboard", ArtifactKind.Design, "text/csv", "storyboard-text/v1", "columns-storyboard"),
        "Number the shots of the script and say what is in each frame, the camera, the seconds and the line spoken, if any. One row per shot. Hand back comma-separated text: a header row with exactly these columns: shot, in frame, camera, seconds, line, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ShotList: StepTemplate = PresetKit.make(
        BuiltInTemplates.TextToTable, "shot-list", "worker",
        listOf(PortSpec("storyboard", PortType.Table, true)),
        PresetKit.out("shots", ArtifactKind.Design, "text/csv", "shot-list/v1", "columns-shots"),
        "List the shots to make from the storyboard, in order, with how each is set up and its seconds. One row per shot. Hand back comma-separated text: a header row with exactly these columns: shot, setup, seconds, then one row per item. Quote any cell that holds a comma. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(ShortFilmOutline, Script, StoryboardText, ShotList)
}
