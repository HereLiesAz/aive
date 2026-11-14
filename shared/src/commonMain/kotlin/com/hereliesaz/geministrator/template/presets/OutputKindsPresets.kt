package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object OutputKindsPresets {
    val Checklist: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "checklist", "worker",
        listOf(PortSpec("goal", PortType.Text, true), PortSpec("material", PortType.Any, false)),
        PresetKit.out("result", ArtifactKind.TaskPlan, "text/markdown", "checklist/v1", StepOutputValidators.CHECKLIST),
        "Write a checklist for the goal given: short items, each on its own line as `- [ ] <thing to do or check>`, in the order they are done. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val PatchDraft: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "patch-draft", "worker",
        listOf(PortSpec("change", PortType.Text, true), PortSpec("files", PortType.Any, false)),
        PresetKit.out("result", ArtifactKind.Specification, "text/x-diff", "patch-draft/v1", StepOutputValidators.DIFF_UNIFIED),
        "Write the change described as a unified diff (`--- a/path`, `+++ b/path`, `@@` hunks). Only write it as text: apply it to nothing, push nothing. Hand back the diff and nothing else. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val ImageBrief: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "image-brief", "worker",
        listOf(PortSpec("idea", PortType.Text, true), PortSpec("style", PortType.Any, false)),
        PresetKit.out("result", ArtifactKind.Requirement, "text/markdown", "image-brief/v1", "media-brief"),
        "Write a brief for an image to be made: its subject, its style and its format (size, ratio, file kind). Describe it; make no image. Give it these headings, in this order: `## Subject`, `## Style`, `## Format`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val AudioBrief: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "audio-brief", "worker",
        listOf(PortSpec("idea", PortType.Text, true), PortSpec("style", PortType.Any, false)),
        PresetKit.out("result", ArtifactKind.Requirement, "text/markdown", "audio-brief/v1", "media-brief"),
        "Write a brief for a piece of audio to be made: its subject, its style (voice, mood, music) and its format (length, file kind). Describe it; make no audio. Give it these headings, in this order: `## Subject`, `## Style`, `## Format`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val VideoBrief: StepTemplate = PresetKit.make(
        BuiltInTemplates.WriteDocument, "video-brief", "worker",
        listOf(PortSpec("idea", PortType.Text, true), PortSpec("style", PortType.Any, false)),
        PresetKit.out("result", ArtifactKind.Requirement, "text/markdown", "video-brief/v1", "media-brief"),
        "Write a brief for a video to be made: its subject, its style (look, pace, sound) and its format (length, ratio, file kind). Describe it; make no video. Give it these headings, in this order: `## Subject`, `## Style`, `## Format`. Use only what the inputs give: invent no fact, figure or source, and do not claim to have sent, saved or published anything. Hand back the result and nothing else.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(Checklist, PatchDraft, ImageBrief, AudioBrief, VideoBrief)
}
