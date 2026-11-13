package com.hereliesaz.geministrator

import androidx.compose.runtime.Composable
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.step_effect_changes
import com.hereliesaz.geministrator.resources.step_effect_leaves_device
import com.hereliesaz.geministrator.resources.step_effect_none
import com.hereliesaz.geministrator.resources.step_effect_reads
import com.hereliesaz.geministrator.resources.step_effect_removes
import com.hereliesaz.geministrator.resources.step_effect_writes_branch
import com.hereliesaz.geministrator.resources.step_port_brief
import com.hereliesaz.geministrator.resources.step_port_change
import com.hereliesaz.geministrator.resources.step_port_columns
import com.hereliesaz.geministrator.resources.step_port_content
import com.hereliesaz.geministrator.resources.step_port_question
import com.hereliesaz.geministrator.resources.step_port_requirements
import com.hereliesaz.geministrator.resources.step_port_sources
import com.hereliesaz.geministrator.resources.step_port_target
import com.hereliesaz.geministrator.resources.step_port_text
import com.hereliesaz.geministrator.resources.step_port_work
import com.hereliesaz.geministrator.resources.step_template_change_connected
import com.hereliesaz.geministrator.resources.step_template_check_work
import com.hereliesaz.geministrator.resources.step_template_make_diagram
import com.hereliesaz.geministrator.resources.step_template_make_page
import com.hereliesaz.geministrator.resources.step_template_prepare_files
import com.hereliesaz.geministrator.resources.step_template_read_connected
import com.hereliesaz.geministrator.resources.step_template_text_to_table
import com.hereliesaz.geministrator.resources.step_template_write_document
import com.hereliesaz.geministrator.template.StepTemplate
import org.jetbrains.compose.resources.stringResource

/** The words for step templates, their input ports and their declared effects (docs/Step-Templates.md). */
@Composable
internal fun stepTemplateTitle(template: StepTemplate): String = stringResource(
    when (template.titleKey) {
        "step_template_write_document" -> Res.string.step_template_write_document
        "step_template_make_page" -> Res.string.step_template_make_page
        "step_template_text_to_table" -> Res.string.step_template_text_to_table
        "step_template_check_work" -> Res.string.step_template_check_work
        "step_template_read_connected" -> Res.string.step_template_read_connected
        "step_template_make_diagram" -> Res.string.step_template_make_diagram
        "step_template_prepare_files" -> Res.string.step_template_prepare_files
        else -> Res.string.step_template_change_connected
    },
)

@Composable
internal fun stepPortLabel(port: String): String = stringResource(
    when (port) {
        "brief" -> Res.string.step_port_brief
        "sources" -> Res.string.step_port_sources
        "content" -> Res.string.step_port_content
        "text" -> Res.string.step_port_text
        "columns" -> Res.string.step_port_columns
        "work" -> Res.string.step_port_work
        "requirements" -> Res.string.step_port_requirements
        "question" -> Res.string.step_port_question
        "change" -> Res.string.step_port_change
        else -> Res.string.step_port_target
    },
)

@Composable
internal fun stepEffectLabel(effect: StepEffect): String = stringResource(
    when (effect) {
        StepEffect.None -> Res.string.step_effect_none
        StepEffect.ReadsConnectedSource -> Res.string.step_effect_reads
        StepEffect.WritesRepositoryBranch -> Res.string.step_effect_writes_branch
        StepEffect.MutatesConnectedSource -> Res.string.step_effect_changes
        StepEffect.DestructiveConnectedSource -> Res.string.step_effect_removes
        StepEffect.LeavesDevice -> Res.string.step_effect_leaves_device
    },
)
