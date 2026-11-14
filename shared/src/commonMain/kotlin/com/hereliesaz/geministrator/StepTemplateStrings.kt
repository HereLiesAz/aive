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
import com.hereliesaz.geministrator.resources.step_template_deck_outline
import com.hereliesaz.geministrator.resources.step_template_docs_draft
import com.hereliesaz.geministrator.resources.step_template_design_tokens
import com.hereliesaz.geministrator.resources.step_template_design_page
import com.hereliesaz.geministrator.resources.step_template_explainer_page
import com.hereliesaz.geministrator.resources.step_template_data_page
import com.hereliesaz.geministrator.resources.step_template_sheet_table
import com.hereliesaz.geministrator.resources.step_template_read_one_item
import com.hereliesaz.geministrator.resources.step_template_list_connected_source
import com.hereliesaz.geministrator.resources.step_template_query_connected_rows
import com.hereliesaz.geministrator.resources.step_template_read_repository_files
import com.hereliesaz.geministrator.resources.step_template_change_connected_rows
import com.hereliesaz.geministrator.resources.step_template_remove_in_connected_source
import com.hereliesaz.geministrator.resources.step_template_update_repository_files
import com.hereliesaz.geministrator.resources.step_template_add_assets_to_repository
import com.hereliesaz.geministrator.resources.step_template_prepare_download_files
import com.hereliesaz.geministrator.resources.step_template_change_code
import com.hereliesaz.geministrator.resources.step_template_render_image
import com.hereliesaz.geministrator.resources.step_template_render_video
import com.hereliesaz.geministrator.resources.step_template_compose_media
import com.hereliesaz.geministrator.resources.step_template_edit_audio
import com.hereliesaz.geministrator.resources.step_template_make_slides
import com.hereliesaz.geministrator.resources.step_template_localize_content
import com.hereliesaz.geministrator.resources.step_template_fixed_format_result
import com.hereliesaz.geministrator.resources.step_template_capped_text_result
import com.hereliesaz.geministrator.resources.step_template_send_email
import com.hereliesaz.geministrator.resources.step_template_send_chat_message
import com.hereliesaz.geministrator.resources.step_template_send_reply
import com.hereliesaz.geministrator.resources.template_group_documents
import com.hereliesaz.geministrator.resources.template_group_plans_and_records
import com.hereliesaz.geministrator.resources.template_group_research_and_evidence
import com.hereliesaz.geministrator.resources.template_group_checks_and_edits
import com.hereliesaz.geministrator.resources.template_group_data_and_tables
import com.hereliesaz.geministrator.resources.template_group_pages_and_diagrams
import com.hereliesaz.geministrator.resources.template_group_writing_for_people
import com.hereliesaz.geministrator.resources.template_group_story_and_media_text
import com.hereliesaz.geministrator.resources.template_group_software_writing
import com.hereliesaz.geministrator.resources.template_group_software_changes
import com.hereliesaz.geministrator.resources.template_group_connected_tools
import com.hereliesaz.geministrator.resources.template_group_swarm_plans_and_teaching
import com.hereliesaz.geministrator.resources.template_group_training_and_improvement
import com.hereliesaz.geministrator.resources.template_group_other
import com.hereliesaz.geministrator.resources.template_group_more
import com.hereliesaz.geministrator.resources.template_group_fewer
import com.hereliesaz.geministrator.resources.template_group_internal
import com.hereliesaz.geministrator.resources.step_port_recipients
import com.hereliesaz.geministrator.resources.step_port_draft
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
    when (template.id) {
        "deck-outline" -> Res.string.step_template_deck_outline
        "docs-draft" -> Res.string.step_template_docs_draft
        "design-tokens" -> Res.string.step_template_design_tokens
        "design-page" -> Res.string.step_template_design_page
        "explainer-page" -> Res.string.step_template_explainer_page
        "data-page" -> Res.string.step_template_data_page
        "sheet-table" -> Res.string.step_template_sheet_table
        "read-one-item" -> Res.string.step_template_read_one_item
        "list-connected-source" -> Res.string.step_template_list_connected_source
        "query-connected-rows" -> Res.string.step_template_query_connected_rows
        "read-repository-files" -> Res.string.step_template_read_repository_files
        "change-connected-rows" -> Res.string.step_template_change_connected_rows
        "remove-in-connected-source" -> Res.string.step_template_remove_in_connected_source
        "update-repository-files" -> Res.string.step_template_update_repository_files
        "add-assets-to-repository" -> Res.string.step_template_add_assets_to_repository
        "prepare-download-files" -> Res.string.step_template_prepare_download_files
        "change-code" -> Res.string.step_template_change_code
        "render-image" -> Res.string.step_template_render_image
        "render-video" -> Res.string.step_template_render_video
        "compose-media" -> Res.string.step_template_compose_media
        "edit-audio" -> Res.string.step_template_edit_audio
        "make-slides" -> Res.string.step_template_make_slides
        "localize-content" -> Res.string.step_template_localize_content
        "fixed-format-result" -> Res.string.step_template_fixed_format_result
        "capped-text-result" -> Res.string.step_template_capped_text_result
        "send-email" -> Res.string.step_template_send_email
        "send-chat-message" -> Res.string.step_template_send_chat_message
        "send-reply" -> Res.string.step_template_send_reply
        else -> stepTemplateBaseTitle(template)
    },
)

private fun stepTemplateBaseTitle(template: StepTemplate) =
    when (template.titleKey) {
        "step_template_write_document" -> Res.string.step_template_write_document
        "step_template_make_page" -> Res.string.step_template_make_page
        "step_template_text_to_table" -> Res.string.step_template_text_to_table
        "step_template_check_work" -> Res.string.step_template_check_work
        "step_template_read_connected" -> Res.string.step_template_read_connected
        "step_template_make_diagram" -> Res.string.step_template_make_diagram
        "step_template_prepare_files" -> Res.string.step_template_prepare_files
        else -> presetTitleResource(template.titleKey) ?: Res.string.step_template_change_connected
    }

/** The title of a palette group (`template_group_<id>`). */
@Composable
internal fun templateGroupTitle(group: com.hereliesaz.geministrator.template.TemplateGroup): String = stringResource(
    when (group.id) {
        "documents" -> Res.string.template_group_documents
        "plans-and-records" -> Res.string.template_group_plans_and_records
        "research-and-evidence" -> Res.string.template_group_research_and_evidence
        "checks-and-edits" -> Res.string.template_group_checks_and_edits
        "data-and-tables" -> Res.string.template_group_data_and_tables
        "pages-and-diagrams" -> Res.string.template_group_pages_and_diagrams
        "writing-for-people" -> Res.string.template_group_writing_for_people
        "story-and-media-text" -> Res.string.template_group_story_and_media_text
        "software-writing" -> Res.string.template_group_software_writing
        "software-changes" -> Res.string.template_group_software_changes
        "connected-tools" -> Res.string.template_group_connected_tools
        "swarm-plans-and-teaching" -> Res.string.template_group_swarm_plans_and_teaching
        "training-and-improvement" -> Res.string.template_group_training_and_improvement
        else -> Res.string.template_group_other
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
        "recipients" -> Res.string.step_port_recipients
        "draft" -> Res.string.step_port_draft
        else -> presetPortResource(port) ?: Res.string.step_port_target
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
