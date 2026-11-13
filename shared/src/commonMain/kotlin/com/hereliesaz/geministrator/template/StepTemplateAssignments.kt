package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.StepEffect
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

/**
 * What a step needs that no starting template gives yet: the template to draft, and why the existing ones do not fit.
 * Empty today: every producing step has a template. A new step that fits none adds an entry here, so it ships with a reason.
 */
enum class StepGap(val needs: String, val reason: String);

object StepTemplateAssignments {
    private const val WRITE = BuiltInTemplates.WRITE_DOCUMENT_ID
    private const val CHECK = BuiltInTemplates.CHECK_WORK_ID
    private const val TABLE = BuiltInTemplates.TEXT_TO_TABLE_ID
    private const val CHANGE_CODE = "change-code"
    private const val RENDER_IMAGE = "render-image"
    private const val RENDER_VIDEO = "render-video"
    private const val COMPOSE_MEDIA = "compose-media"
    private const val EDIT_AUDIO = "edit-audio"
    private const val MAKE_SLIDES = "make-slides"
    private const val LOCALIZE = "localize-content"
    private const val FIXED = "fixed-format-result"
    private const val CAPPED = "capped-text-result"

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
        "suite-feature-delivery/implement" to CHANGE_CODE,
        "suite-bug-fix/fix" to CHANGE_CODE,
        "suite-bug-fix/regression-test" to CHANGE_CODE,
        "suite-safe-refactor/characterize" to CHANGE_CODE,
        "suite-safe-refactor/refactor" to CHANGE_CODE,
        "suite-test-coverage/write-tests" to CHANGE_CODE,
        "suite-dependency-update/update" to CHANGE_CODE,
        "suite-docs-sync/update-docs" to CHANGE_CODE,
        "suite-incident-hotfix/hotfix" to CHANGE_CODE,
        "short-film/storyboard" to FIXED,
        "short-film/style-sheet" to CAPPED,
        "short-film/frames" to RENDER_IMAGE,
        "short-film/frame-review" to FIXED,
        "short-film/assemble" to RENDER_VIDEO,
        "starter/implementation" to CHANGE_CODE,
    )

    /** `<swarm id>/<step id>` to the gap, for the built-in swarms and the Azphalt packages in docs/azphalt-packages. None today. */
    val gaps: Map<String, StepGap> = emptyMap()

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
        // A starting point enforces nothing, so it declares no effect: the approval floor of an effectful template
        // arrives when a step is made from the template, never from the contract of a step that only began there.
        effects = if (template.effectful) setOf(StepEffect.None) else template.effects,
        startingPoint = true,
    )

    /** The `work` input as words, never a binding: a step's wait on another step stays in its own dependencies. */
    private fun workLiteral(task: TaskDefinition): String =
        if (task.dependsOn.isEmpty()) "The work this step's objective names." else "What the earlier steps handed back."
}
