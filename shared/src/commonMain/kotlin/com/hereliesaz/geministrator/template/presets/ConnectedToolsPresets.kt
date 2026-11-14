package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.StepTemplate
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/* Starting templates of one group of the catalog (docs/Step-Templates.md, "The starting templates"). Data only, made by [PresetKit]. */
internal object ConnectedToolsPresets {
    val UpdateConnectedDocument: StepTemplate = PresetKit.make(
        BuiltInTemplates.ChangeConnectedSource, "update-connected-document", "worker",
        listOf(PortSpec("change", PortType.Text, true), PortSpec("document", PortType.Text, true), PortSpec("content", PortType.Markdown, true)),
        PresetKit.out("result", ArtifactKind.Verification, "text/plain", "update-connected-document/v1", StepOutputValidators.CHANGE_RESULT),
        "Change the one named part of the document the target names, using the content given, with the one tool you are offered. Read it first; if it is already changed, change nothing more. Make the change once. Hand back two lines, `TARGET: <what you changed>` and `CHANGED: <how it changed>`. If you cannot, say why and change nothing.",
        20_000L,
    )

    val AppendRowsToConnectedSheet: StepTemplate = PresetKit.make(
        BuiltInTemplates.ChangeConnectedSource, "append-rows-to-connected-sheet", "worker",
        listOf(PortSpec("rows", PortType.Table, true), PortSpec("sheet", PortType.Text, true)),
        PresetKit.out("result", ArtifactKind.Verification, "text/plain", "append-rows-to-connected-sheet/v1", StepOutputValidators.CHANGE_RESULT),
        "Add the rows given to the end of the sheet the target names, with the one tool you are offered. Read the end of the sheet first, and if the rows are already there, add none. Add them once, and change no existing row. Hand back two lines, `TARGET: <what you changed>` and `CHANGED: <how it changed>`. If you cannot, say why and change nothing.",
        20_000L,
    )

    val all: List<StepTemplate> = listOf(UpdateConnectedDocument, AppendRowsToConnectedSheet)
}
