package com.hereliesaz.geministrator.template

/*
 * How the Add creature palette arranges the step templates (docs/Step-Templates.md). The starting-templates catalog has
 * thirteen groups; each is a collapsible group in the palette that shows a few common templates and folds the rest under
 * a More. The nine templates of the Nanny, the queen and the training (swarm plan, tutorial lesson, tutorial check-in,
 * reworded step, practice result, training report, specialist lessons, Nanny finding, template or role revision) are
 * internal: the palette's default view and the planner's list leave them out, and the palette's own fold brings them
 * back. Pure data and pure functions, so the arrangement is tested without a screen.
 */

/** One group: [members] are template ids, the common ones first; the first [common] present ones are shown unfolded. */
data class TemplateGroup(
    val id: String,
    /** Names the string resource that gives the group's title (`template_group_<id>`, dashes as underscores). */
    val titleKey: String,
    val members: List<String>,
    val common: Int = TemplateGroups.COMMON_PER_GROUP,
)

/** A group as the palette shows it: [shown] unfolded, [folded] behind the group's More. Never empty in both. */
data class TemplateGroupView(
    val group: TemplateGroup,
    val shown: List<StepTemplate>,
    val folded: List<StepTemplate>,
) {
    val total: Int get() = shown.size + folded.size
}

object TemplateGroups {
    const val COMMON_PER_GROUP: Int = 3

    /** The group a template lands in when no group names it (a preset added later), so no template is ever dropped. */
    const val OTHER_ID: String = "other"

    /** The internal nine: hidden from the planner's list and the palette's default view, never deleted. */
    val internal: Set<String> = linkedSetOf(
        "swarm-plan", "tutorial-lesson", "tutorial-check-in", "queen-reworded-step",
        "practice-result", "training-report", "specialist-lessons", "nanny-finding", "revision-proposal",
    )

    private fun group(id: String, vararg members: String) =
        TemplateGroup(id, "template_group_" + id.replace('-', '_'), members.toList())

    /** The thirteen groups of the catalog, in the order the palette lists them. */
    val groups: List<TemplateGroup> = listOf(
        group(
            "documents",
            "write-document", "docs-draft", "brief",
            "report", "summary", "one-page-explainer", "faq", "glossary", "translation", "localize-content", "deck-outline",
        ),
        group("plans-and-records", "plan", "timeline", "meeting-notes", "decision-memo", "risk-register", "checklist"),
        group("research-and-evidence", "research-brief", "source-list", "claim-check", "comparison-table"),
        group("checks-and-edits", "check-work", "critique", "proofread", "accessibility-check", "clarifying-questions"),
        group(
            "data-and-tables",
            "text-to-table", "sheet-table", "csv-from-text",
            "data-cleanup", "extract-fields", "spreadsheet-with-formulas", "data-check-report", "fixed-format-result",
        ),
        group(
            "pages-and-diagrams",
            "make-page", "make-diagram", "design-page",
            "explainer-page", "data-page", "design-tokens", "landing-page", "dashboard-page", "diagram-flowchart",
        ),
        group(
            "writing-for-people",
            "email-draft", "send-email", "send-chat-message",
            "send-reply", "negotiation-reply-draft", "press-release", "social-post-set", "policy-draft", "capped-text-result",
        ),
        group("story-and-media-text", "short-film-outline", "script", "storyboard-text", "shot-list", "image-brief", "audio-brief", "video-brief"),
        group(
            "software-writing",
            "spec", "api-doc", "adr",
            "changelog-entry", "release-notes", "pr-description", "bug-report", "repro-steps",
        ),
        group(
            "software-changes",
            "change-code", "prepare-files-for-repository", "read-repository-files",
            "update-repository-files", "add-assets-to-repository", "prepare-download-files",
            "test-plan", "test-cases", "code-change-on-branch", "tests-on-branch", "patch-draft",
        ),
        group(
            "connected-tools",
            "read-connected-source", "list-connected-source", "change-connected-source",
            "read-one-item", "query-connected-rows", "change-connected-rows", "remove-in-connected-source",
            "list-from-connected-source", "update-connected-document", "append-rows-to-connected-sheet",
            "remove-from-connected-tool", "render-image", "render-video", "compose-media", "edit-audio", "make-slides",
        ),
        group("swarm-plans-and-teaching", "swarm-plan", "tutorial-lesson", "tutorial-check-in", "queen-reworded-step"),
        group("training-and-improvement", "practice-result", "training-report", "specialist-lessons", "nanny-finding", "revision-proposal"),
    )

    private val other = TemplateGroup(OTHER_ID, "template_group_other", emptyList())

    private val placement: Map<String, Pair<TemplateGroup, Int>> by lazy {
        buildMap {
            groups.forEach { g -> g.members.forEachIndexed { index, id -> if (id !in this) put(id, g to index) } }
        }
    }

    fun isInternal(templateId: String): Boolean = templateId in internal

    /** The group that names [templateId], or null when none does. */
    fun groupOf(templateId: String): TemplateGroup? = placement[templateId]?.first

    /** [templates] without the internal nine. */
    fun visible(templates: List<StepTemplate>): List<StepTemplate> = templates.filterNot { isInternal(it.id) }

    /** The internal ones among [templates]: what the palette's own fold brings back. */
    fun internalOf(templates: List<StepTemplate>): List<StepTemplate> = templates.filter { isInternal(it.id) }

    /**
     * [templates] as groups, in the catalog's group order, each with its common templates shown and the rest folded; a
     * template no group names goes to a last group of its own, in the order given. The internal nine appear only when
     * [includeInternal]. A group with nothing to show is left out. Every template given appears exactly once.
     */
    fun arrange(templates: List<StepTemplate>, includeInternal: Boolean = false): List<TemplateGroupView> {
        val wanted = if (includeInternal) templates else visible(templates)
        val byGroup = wanted.groupBy { placement[it.id]?.first?.id ?: OTHER_ID }
        val views = (groups + other).mapNotNull { g ->
            val present = byGroup[g.id].orEmpty().let { list ->
                if (g.id == OTHER_ID) list else list.sortedBy { placement.getValue(it.id).second }
            }
            if (present.isEmpty()) null else TemplateGroupView(g, present.take(g.common), present.drop(g.common))
        }
        return views
    }
}
