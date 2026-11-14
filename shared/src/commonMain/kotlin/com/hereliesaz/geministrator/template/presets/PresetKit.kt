package com.hereliesaz.geministrator.template.presets

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.OutputSpec
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.template.StepTemplate

/** Makes a starting template from a built-in one by changing data only (the same way the presets of StepPresets are made). */
internal object PresetKit {
    fun out(name: String, kind: ArtifactKind, media: String, schema: String, validator: String) =
        OutputSpec(name, kind, media, schema, validator)

    /** [id]'s title key is `step_template_p_<id>` with hyphens as underscores (shared/.../values/strings_templates.xml). */
    fun make(
        base: StepTemplate,
        id: String,
        role: String,
        inputs: List<PortSpec>,
        output: OutputSpec,
        wording: String,
        budgetTokens: Long,
    ): StepTemplate = base.copy(
        id = id,
        titleKey = "step_template_p_" + id.replace('-', '_'),
        roleHint = role,
        inputs = inputs,
        outputs = listOf(output),
        body = base.body.copy(
            wording = wording,
            validators = mapOf(output.name to output.validatorId),
            budgetTokens = budgetTokens,
        ),
    )
}
