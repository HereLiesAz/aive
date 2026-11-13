package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.domain.WorkflowDefinition

/*
 * Which starting template each producing step of the built-in swarms begins from (docs/Azphalt-Templates.md). A step that
 * existed before it named a template carries a contract marked [TaskContract.startingPoint]: it names the template, its typed
 * output and the step's own inputs, and changes nothing about how the step runs. The Nanny reads the template from the
 * contract and can improve it over generations. A step that fits no starting template is listed in [gaps] with what
 * is missing, so a new built-in cannot ship without a template or a reasoned entry (StepTemplateCoverageTest).
 */

/** What a step needs that no starting template gives yet: the template to draft, and why the existing ones do not fit. */
enum class StepGap(val needs: String, val reason: String) {
    ChangeCode("change-code", "Writes files on a repository branch, an effect the six starting templates do not cover."),
    RenderImage("render-image", "Makes pictures through an image service; no starting template makes media."),
    RenderVideo("render-video", "Makes or assembles video; no starting template makes media."),
    ComposeMedia("compose-media", "Builds geometry or composites layers; no starting template transforms media."),
    EditAudio("edit-audio", "Edits a recording; no starting template handles audio."),
    MakeSlides("make-slides", "Makes a slide deck; make-page makes one page, not a deck."),
    Localize("localize-content", "Translates content in place and verifies its strings; write-document does not carry that check."),
    FixedFormat("fixed-format-result", "Hands back a result in a fixed or size-bounded format that code reads (JSON, a line grammar, a capped block)."),
}

object StepTemplateAssignments {
    private const val WRITE = BuiltInTemplates.WRITE_DOCUMENT_ID
    private const val CHECK = BuiltInTemplates.CHECK_WORK_ID
    private const val TABLE = BuiltInTemplates.TEXT_TO_TABLE_ID

    /** `<swarm id>/<step id>` of a step in the built-in swarms (the coding suite, the short film, the starter) to its starting template id. */
    val byStep: Map<String, String> = mapOf(
        "suite-feature-delivery/requirements" to WRITE,
        "suite-feature-delivery/design" to WRITE,
        "suite-feature-delivery/challenge" to CHECK,
        "suite-feature-delivery/verify" to CHECK,
        "suite-feature-delivery/code-review" to CHECK,
        "suite-bug-fix/reproduce" to WRITE,
        "suite-bug-fix/diagnose" to WRITE,
        "suite-bug-fix/verify" to CHECK,
        "suite-bug-fix/code-review" to CHECK,
        "suite-safe-refactor/plan" to WRITE,
        "suite-safe-refactor/verify" to CHECK,
        "suite-safe-refactor/code-review" to CHECK,
        "suite-test-coverage/survey" to WRITE,
        "suite-test-coverage/verify" to CHECK,
        "suite-test-coverage/code-review" to CHECK,
        "suite-dependency-update/audit" to WRITE,
        "suite-dependency-update/verify" to CHECK,
        "suite-dependency-update/code-review" to CHECK,
        "suite-docs-sync/find-drift" to WRITE,
        "suite-docs-sync/code-review" to CHECK,
        "suite-release-prep/changelog" to WRITE,
        "suite-release-prep/checks" to CHECK,
        "suite-release-prep/code-review" to CHECK,
        "suite-incident-hotfix/diagnose" to WRITE,
        "suite-incident-hotfix/verify" to CHECK,
        "suite-incident-hotfix/rollback-plan" to WRITE,
        "suite-code-review/correctness" to CHECK,
        "suite-code-review/risk" to CHECK,
        "suite-code-review/tests" to CHECK,
        "suite-code-review/summary" to WRITE,
        "suite-security-audit/threat-model" to WRITE,
        "suite-security-audit/hunt" to CHECK,
        "suite-security-audit/triage" to CHECK,
        "suite-security-audit/report" to WRITE,
        "suite-codebase-map/survey" to WRITE,
        "suite-codebase-map/architecture" to WRITE,
        "suite-codebase-map/guide" to WRITE,
        "suite-nightly-dependency-audit/audit" to WRITE,
        "suite-nightly-dependency-audit/report" to WRITE,
        "suite-daily-ci-triage/collect" to WRITE,
        "suite-daily-ci-triage/diagnose" to WRITE,
        "suite-daily-ci-triage/report" to WRITE,
        "suite-weekly-test-health/assess" to WRITE,
        "suite-weekly-test-health/report" to WRITE,
        "suite-weekly-security-scan/scan" to CHECK,
        "suite-weekly-security-scan/report" to WRITE,
        "suite-weekly-docs-drift/compare" to WRITE,
        "suite-weekly-docs-drift/report" to WRITE,
        "suite-weekly-tech-debt/survey" to WRITE,
        "suite-weekly-tech-debt/report" to WRITE,
        "short-film/concept" to WRITE,
        "starter/verification" to CHECK,
        "starter/review" to CHECK,
    )

    /** `<swarm id>/<step id>` to the gap, for the built-in swarms and the Azphalt packages in docs/azphalt-packages. */
    val gaps: Map<String, StepGap> = mapOf(
        "suite-feature-delivery/implement" to StepGap.ChangeCode,
        "suite-bug-fix/fix" to StepGap.ChangeCode,
        "suite-bug-fix/regression-test" to StepGap.ChangeCode,
        "suite-safe-refactor/characterize" to StepGap.ChangeCode,
        "suite-safe-refactor/refactor" to StepGap.ChangeCode,
        "suite-test-coverage/write-tests" to StepGap.ChangeCode,
        "suite-dependency-update/update" to StepGap.ChangeCode,
        "suite-docs-sync/update-docs" to StepGap.ChangeCode,
        "suite-incident-hotfix/hotfix" to StepGap.ChangeCode,
        "short-film/storyboard" to StepGap.FixedFormat,
        "short-film/style-sheet" to StepGap.FixedFormat,
        "short-film/frames" to StepGap.RenderImage,
        "short-film/frame-review" to StepGap.FixedFormat,
        "short-film/assemble" to StepGap.RenderVideo,
        "starter/implementation" to StepGap.ChangeCode,
        "character-sheet-generator/character-sheet-generator-task-2" to StepGap.RenderImage,
        "feature-delivery/feature-delivery-task-1" to StepGap.ChangeCode,
        "idea-to-storyboard-to-video/idea-to-storyboard-to-video-task-2" to StepGap.RenderImage,
        "idea-to-storyboard-to-video/idea-to-storyboard-to-video-task-3" to StepGap.RenderVideo,
        "localization-release/localization-release-task-1" to StepGap.Localize,
        "paperplanes-stratified-reconstruction/paperplanes-stratified-reconstruction-task-2" to StepGap.ComposeMedia,
        "paperplanes-stratified-reconstruction/paperplanes-stratified-reconstruction-task-3" to StepGap.ComposeMedia,
        "paperplanes-stratified-reconstruction/paperplanes-stratified-reconstruction-task-4" to StepGap.ComposeMedia,
        "podcast-production/podcast-production-task-1" to StepGap.EditAudio,
        "presentation-production/presentation-production-task-1" to StepGap.MakeSlides,
        "sketch-to-concept-art/sketch-to-concept-art-task-2" to StepGap.RenderImage,
    )

    /** The swarm key the starter swarm is looked up under; its id is chosen by the caller. */
    const val STARTER_KEY: String = "starter"

    /**
     * [definition] with a starting-point contract on each step [byStep] names for it, unless the step already has a
     * contract. Everything else about every step is unchanged. [key] is the swarm id the table is read by.
     */
    fun apply(definition: WorkflowDefinition, key: String = definition.id.value): WorkflowDefinition {
        val tasks = definition.tasks.map { task ->
            val template = byStep["$key/${task.id.value}"]?.let(BuiltInTemplates.byId::get)
            if (template == null || task.contract != null) task else task.copy(contract = startingContract(template, task))
        }
        return definition.copy(tasks = tasks)
    }

    /** The starting-point contract for [task] from [template]: its required inputs named plainly, its typed output, its effects. */
    fun startingContract(template: StepTemplate, task: TaskDefinition): TaskContract = TaskContract(
        template = TemplateRef(template.id, template.version),
        inputs = template.inputs.filter { it.required }.associate { port ->
            port.name to if (port.name == "work") workLiteral(task) else task.objective
        },
        outputs = template.outputs,
        effects = template.effects,
        startingPoint = true,
    )

    /** The `work` input as words, never a binding: a step's wait on another step stays in its own dependencies. */
    private fun workLiteral(task: TaskDefinition): String =
        if (task.dependsOn.isEmpty()) "The work this step's objective names." else "What the earlier steps handed back."
}
