package com.hereliesaz.geministrator.template

/*
 * Where each step of the artifact set (the quickstart intents, types, actions and capabilities, 36 steps in all) stands
 * in Aive (docs/Step-Templates.md). A step of that set is a starting point for something a hive may need created; it is
 * not a checklist. Data only: the closed catalog of starting points, and the steps that cannot run here with the reason.
 */
enum class ArtifactStepStatus {
    /** An existing template, or the plain agent task, already does it. */
    Done,

    /** A template or preset of this catalog does it in an honest form. */
    Built,

    /** It needs something Aive does not have (a hosted artifact, a hosted page's capability, a device feature). */
    CannotRun,
}

data class ArtifactStep(
    val path: String,
    val status: ArtifactStepStatus,
    /** The templates that stand in for it (ids in [BuiltInTemplates.byId]); empty for [ArtifactStepStatus.CannotRun] and for a step that needs none. */
    val templates: List<String> = emptyList(),
    /** For [ArtifactStepStatus.CannotRun]: the exact reason. For others: a short note on what the honest form leaves out. */
    val note: String = "",
)

object ArtifactStepCoverage {
    private const val HOSTED = "It is a feature of a claude.ai hosted artifact that Aive has no equivalent of."
    private const val PAGE = "It is a capability of a hosted page; Aive makes no hosted page."

    val steps: List<ArtifactStep> = listOf(
        ArtifactStep("intents/document", ArtifactStepStatus.Done, listOf("write-document")),
        ArtifactStep("intents/slides", ArtifactStepStatus.Built, listOf("deck-outline"), "An outline only: there is no slide renderer."),
        ArtifactStep("intents/design", ArtifactStepStatus.Built, listOf("design-page")),
        ArtifactStep("intents/other", ArtifactStepStatus.Built, listOf("explainer-page", "write-document")),
        ArtifactStep("types/data", ArtifactStepStatus.Built, listOf("data-page", "text-to-table"), "A page with the numbers and their sources; no live queries unless a read step is given a tool."),
        ArtifactStep("types/design-system", ArtifactStepStatus.Built, listOf("design-tokens", "write-document"), "Tokens as JSON and a README as a document; no hosted, browsable system."),
        ArtifactStep("types/design", ArtifactStepStatus.Built, listOf("design-page")),
        ArtifactStep("types/docs", ArtifactStepStatus.Built, listOf("docs-draft", "change-connected-source"), "A draft; pushing it into a document tool is a change step with that tool's update call."),
        ArtifactStep("types/sheets", ArtifactStepStatus.Built, listOf("sheet-table", "change-connected-source"), "Comma-separated text; no .xlsx writer."),
        ArtifactStep("types/slides", ArtifactStepStatus.Built, listOf("deck-outline", "change-connected-source"), "An outline, or a change step with a slides tool."),
        ArtifactStep("types/whiteboard", ArtifactStepStatus.Built, listOf("make-diagram"), "A Mermaid flowchart; no live cursors."),
        ArtifactStep("actions/quickstart", ArtifactStepStatus.CannotRun, note = "It is the router of the Artifact tool. The catalog and the queen's pick are Aive's equivalent."),
        ArtifactStep("actions/publish", ArtifactStepStatus.Built, listOf("prepare-files-for-repository"), "Files on a dedicated branch, then the existing gated repository steps. No private address, no versions."),
        ArtifactStep("actions/update", ArtifactStepStatus.Built, listOf("update-repository-files")),
        ArtifactStep("actions/read", ArtifactStepStatus.Built, listOf("read-one-item"), "Through a connected tool; an address on claude.ai cannot be read."),
        ArtifactStep("actions/list", ArtifactStepStatus.Built, listOf("list-connected-source")),
        ArtifactStep("actions/delete", ArtifactStepStatus.Built, listOf("remove-in-connected-source"), "Over a tool that asks on every call."),
        ArtifactStep("actions/open", ArtifactStepStatus.CannotRun, note = "It only shows a hosted artifact to the person. The Story already shows what a step made."),
        ArtifactStep("actions/pin-unpin", ArtifactStepStatus.CannotRun, note = "It pins to the claude.ai sidebar."),
        ArtifactStep("actions/upload-assets", ArtifactStepStatus.Built, listOf("add-assets-to-repository"), "Files on a repository branch; there is no hosted asset store."),
        ArtifactStep("actions/artifact-data", ArtifactStepStatus.Built, listOf("query-connected-rows", "change-connected-rows"), "Against a database or sheet tool the keeper connected."),
        ArtifactStep("actions/artifact-comments", ArtifactStepStatus.CannotRun, note = "It reads and writes claude.ai comment threads. A change step with a Slack or GitHub tool can post a comment elsewhere."),
        ArtifactStep("capabilities/artifact", ArtifactStepStatus.CannotRun, note = "A hosted page republishing itself. $HOSTED"),
        ArtifactStep("capabilities/assets", ArtifactStepStatus.CannotRun, note = "A hosted asset store. $HOSTED"),
        ArtifactStep("capabilities/comments", ArtifactStepStatus.CannotRun, note = "A hosted comment store. $HOSTED"),
        ArtifactStep("capabilities/db", ArtifactStepStatus.Built, listOf("query-connected-rows", "change-connected-rows"), "Against a connected tool, not a database hosted with a page."),
        ArtifactStep("capabilities/dictation", ArtifactStepStatus.CannotRun, note = "There is no speech-recognition code in Aive; it is a device feature, not a task for a creature."),
        ArtifactStep("capabilities/downloads", ArtifactStepStatus.Built, listOf("prepare-download-files"), "Files on a branch; saving to a local folder is the keeper's own action."),
        ArtifactStep("capabilities/files", ArtifactStepStatus.Built, listOf("read-repository-files")),
        ArtifactStep("capabilities/mcp", ArtifactStepStatus.Done, listOf("read-connected-source", "change-connected-source"), "The closed tool set of a step is this capability's manifest."),
        ArtifactStep("capabilities/permissions", ArtifactStepStatus.Done, note = "Not a step: the approval gate, the consent gate and the tool-call policy do it."),
        ArtifactStep("capabilities/room", ArtifactStepStatus.CannotRun, note = "Live presence on a hosted page. $PAGE"),
        ArtifactStep("capabilities/sample", ArtifactStepStatus.Done, note = "It is an agent already: an ordinary creature's task."),
        ArtifactStep("capabilities/self", ArtifactStepStatus.CannotRun, note = "A deprecated alias of capabilities/artifact. $HOSTED"),
        ArtifactStep("capabilities/tts", ArtifactStepStatus.CannotRun, note = "There is no speech code in Aive; it is a device feature."),
        ArtifactStep("capabilities/user", ArtifactStepStatus.CannotRun, note = "The identity of the viewer of a hosted page. $PAGE"),
    )

    val cannotRun: List<ArtifactStep> get() = steps.filter { it.status == ArtifactStepStatus.CannotRun }

    /** The ids of every template that stands in for a step: the starting points the catalog offers. */
    val startingPoints: Set<String> get() = steps.flatMap { it.templates }.toSet()
}
