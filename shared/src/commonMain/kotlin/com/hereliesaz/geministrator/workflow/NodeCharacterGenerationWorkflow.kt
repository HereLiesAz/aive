package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ConcurrencyPolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId

const val NODE_CHARACTER_IMAGE_PIPELINE_SERVICE: String = "node-character-image-pipeline"

/**
 * Governed companion workflow used to create Node Creature assets for roles referenced by a newly
 * authored workflow.
 *
 * The image model is deliberately not asked to infer anatomy or manage the process. Text roles
 * first produce the role brief, visual design contract, and flat-puppet anatomy contract. The
 * image executor then performs the bounded generate -> inspect -> correct loop using those
 * artifacts as literal instructions.
 */
object NodeCharacterGenerationWorkflowFactory {
    val PipelineOrchestrator = RoleDefinition(
        id = RoleDefinitionId("node-character-pipeline-orchestrator"),
        name = "Node Character Pipeline Orchestrator",
        description = "Extracts a role's identity and owns the node-character generation contract.",
        instructions = """
            Work on exactly one target role at a time. Read the source workflow task objectives and
            the target RoleDefinition. Produce a compact role identity brief: purpose, behavior,
            visual metaphors that are actually justified by the role, and explicit things that must
            not be confused with other existing roles. Do not design rig anatomy and do not generate
            an image. Never collapse a role into a generic mascot archetype.
        """.trimIndent(),
        authorities = setOf(RoleAuthority.ProposePlan),
    )

    val CharacterDesigner = RoleDefinition(
        id = RoleDefinitionId("node-character-designer"),
        name = "Node Character Designer",
        description = "Defines one unique Aive Node Creature from an approved role identity brief.",
        instructions = """
            Create a concise visual brief for one Aive Node Creature. Preserve the established
            faceted/polygonal mascot family while giving this role a genuinely distinct central body,
            appendage language, palette, terminals, and at most a few role-specific props. Specify
            only visible design facts. Do not produce anatomy diagrams, alternate views, generic
            recolors, or a universal sphere with a different hat.
        """.trimIndent(),
        authorities = setOf(RoleAuthority.ProposePlan),
    )

    val RiggingAnatomist = RoleDefinition(
        id = RoleDefinitionId("node-character-rigging-anatomist"),
        name = "Node Character Rigging Anatomist",
        description = "Turns the visual brief into a strict flat 2D puppet-parts contract.",
        instructions = """
            Define the exact flat 2D rig layers required for this one character. Preserve its unique
            body/core. State the actual eye count. Eye sockets may remain visible in the body; eye
            whites, pupils, upper lids, and lower lids are independent layers. Split every movable
            upper appendage into base/mid/tip only where articulation requires it. Split lower
            gestation limbs into base/mid/bud-tip layers. Props are independent only when they move.
            Detached appendage ends are flat overlapping cutout ends: never sockets, holes, collars,
            plugs, pegs, hollow tubes, or 3D exploded-model hardware. No cast shadow or background.
        """.trimIndent(),
        authorities = setOf(RoleAuthority.ProposePlan),
    )

    val ImageOperator = RoleDefinition(
        id = RoleDefinitionId("node-character-image-operator"),
        name = "Node Character Image Operator",
        description = "Executes the literal single-character render and rig-sheet generation contract.",
        instructions = """
            Render exactly one character. Treat upstream visual and rigging contracts as literal.
            First create the assembled source character; then create its transparent, directly
            sliceable flat 2D puppet-rig sheet using that character image as the visual source.
            Preserve source-relative scale and faceted texture. Never combine multiple characters.
            Never add labels, presentation panels, assembled examples on the rig sheet, cast shadows,
            backgrounds, duplicate reusable pieces, or appendage-end sockets.
        """.trimIndent(),
        authorities = setOf(RoleAuthority.Implement),
    )

    val QualityInspector = RoleDefinition(
        id = RoleDefinitionId("node-character-quality-inspector"),
        name = "Node Character Quality Inspector",
        description = "Independently audits the generated character and rig contract evidence.",
        instructions = """
            Review the image pipeline's verification report and generation metadata against the
            upstream role brief and rigging contract. Reject missing body/core assets, generic body
            substitution, baked-in pupils, missing upper/lower eyelids, duplicate reusable pieces,
            wrong source-relative scale, 3D exploded-model construction, appendage-end sockets,
            shadows/backgrounds, or cross-character contamination. Do not invent defects: cite the
            exact reported evidence for every rejection.
        """.trimIndent(),
        authorities = setOf(RoleAuthority.Verify),
    )

    val AssetRegistrar = RoleDefinition(
        id = RoleDefinitionId("node-character-asset-registrar"),
        name = "Node Character Asset Registrar",
        description = "Registers only verified source and rig assets for the target role.",
        instructions = """
            Register only assets that have completed image-pipeline verification and independent
            quality review. Preserve the target role id, source workflow id, source-character media
            artifact, rig-sheet media artifact, generation prompt artifact, and verification
            artifact. Never overwrite a different role's character record.
        """.trimIndent(),
        authorities = setOf(RoleAuthority.ApproveIntegration),
    )

    val roles: List<RoleDefinition> = listOf(
        PipelineOrchestrator,
        CharacterDesigner,
        RiggingAnatomist,
        ImageOperator,
        QualityInspector,
        AssetRegistrar,
    )

    val roleIds: Set<RoleDefinitionId> = roles.mapTo(linkedSetOf(), RoleDefinition::id)

    fun referencedRoleIds(source: WorkflowDefinition): Set<RoleDefinitionId> = buildSet {
        source.tasks.forEach { task ->
            task.roleId?.let(::add)
            (task.executor as? TaskExecutor.RoleAgent)?.roleId?.let(::add)
        }
    }.filterNotTo(linkedSetOf()) { it in roleIds }

    fun create(
        source: WorkflowDefinition,
        roleCatalog: Collection<RoleDefinition>,
        targetRoleIds: Set<RoleDefinitionId> = referencedRoleIds(source),
    ): WorkflowDefinition {
        val rolesById = roleCatalog.associateBy(RoleDefinition::id)
        val targetRoles = targetRoleIds
            .filterNot { it in roleIds }
            .map { roleId ->
                requireNotNull(rolesById[roleId]) {
                    "Cannot generate a node character for unknown role ${roleId.value}"
                }
            }
            .distinctBy(RoleDefinition::id)
            .sortedBy { it.id.value }

        require(targetRoles.isNotEmpty()) {
            "Workflow ${source.id.value} has no roles requiring node-character generation"
        }

        return WorkflowDefinition(
            id = WorkflowDefinitionId("node-characters-for-${safeId(source.id.value)}"),
            name = "Node characters · ${source.name}",
            description = "Automatically generates and registers one verified 2D-riggable Node Creature for each new role used by '${source.name}'.",
            tasks = targetRoles.flatMap { target -> tasksFor(source, target) },
            integrationPolicy = IntegrationPolicy.Manual,
            concurrencyPolicy = ConcurrencyPolicy(maxConcurrentTasks = 4),
            testDesignPolicy = TestDesignPolicy.None,
        )
    }

    private fun tasksFor(source: WorkflowDefinition, target: RoleDefinition): List<TaskDefinition> {
        val slug = safeId(target.id.value)
        val sourceUsage = source.tasks
            .filter { task ->
                task.roleId == target.id ||
                    (task.executor as? TaskExecutor.RoleAgent)?.roleId == target.id
            }
            .joinToString("\n") { task -> "- ${task.name}: ${task.objective}" }
            .ifBlank { "- Referenced by workflow ${source.name}" }

        val intake = TaskDefinitionId("${slug}-character-intake")
        val design = TaskDefinitionId("${slug}-character-design")
        val rig = TaskDefinitionId("${slug}-rig-contract")
        val generate = TaskDefinitionId("${slug}-generate-assets")
        val inspect = TaskDefinitionId("${slug}-inspect-assets")
        val register = TaskDefinitionId("${slug}-register-assets")

        return listOf(
            TaskDefinition(
                id = intake,
                name = "Character intake · ${target.name}",
                objective = """
                    Target role: ${target.name} (${target.id.value})
                    Role description: ${target.description}
                    Standing instructions: ${target.instructions}
                    Source workflow: ${source.name} (${source.id.value})
                    Uses in this workflow:
                    ${sourceUsage}

                    Produce the role identity brief for one new Node Creature. Existing Aive
                    characters are constraints against accidental duplication, not templates to
                    recolor.
                """.trimIndent(),
                roleId = PipelineOrchestrator.id,
                requiredRoleAuthority = RoleAuthority.ProposePlan,
                executor = TaskExecutor.RoleAgent(PipelineOrchestrator.id),
                acceptanceCriteria = listOf(
                    AcceptanceCriterion("Exactly one target role is described."),
                    AcceptanceCriterion("The brief distinguishes this role from adjacent roles without inventing unrelated props."),
                ),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
            TaskDefinition(
                id = design,
                name = "Visual brief · ${target.name}",
                objective = """
                    Produce the single-character visual brief from the approved role identity.
                    Preserve the Aive Node Creature faceted/polygonal family, but define a distinct
                    silhouette, central body, appendages, terminals, palette, and justified props.
                    The result must be concrete enough for an image generator to draw without doing
                    role-analysis itself.
                """.trimIndent(),
                roleId = CharacterDesigner.id,
                requiredRoleAuthority = RoleAuthority.ProposePlan,
                executor = TaskExecutor.RoleAgent(CharacterDesigner.id),
                dependsOn = setOf(intake),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
            TaskDefinition(
                id = rig,
                name = "2D rig contract · ${target.name}",
                objective = """
                    Convert the character visual brief into the exact 2D puppet-parts contract.
                    Keep eye sockets when visible; separate eye whites, pupils, upper lids and lower
                    lids; define flat overlap joints; split only genuinely articulated appendages;
                    define lower gestation limbs; preserve source-relative size; prohibit
                    appendage-end sockets and all 3D construction-diagram logic.
                """.trimIndent(),
                roleId = RiggingAnatomist.id,
                requiredRoleAuthority = RoleAuthority.ProposePlan,
                executor = TaskExecutor.RoleAgent(RiggingAnatomist.id),
                dependsOn = setOf(intake, design),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
            TaskDefinition(
                id = generate,
                name = "Generate character + rig · ${target.name}",
                objective = """
                    Generate exactly one assembled source character and then one transparent PNG
                    flat 2D puppet-rig parts sheet for ${target.name}. Use only the upstream role,
                    visual, and rigging contracts. The image pipeline must inspect each render and
                    correct violations before returning its final media artifacts.
                """.trimIndent(),
                roleId = ImageOperator.id,
                requiredRoleAuthority = RoleAuthority.Implement,
                executor = TaskExecutor.ExternalService(
                    service = NODE_CHARACTER_IMAGE_PIPELINE_SERVICE,
                    operation = "generate:${target.id.value}",
                ),
                dependsOn = setOf(intake, design, rig),
                acceptanceCriteria = listOf(
                    AcceptanceCriterion("One source-character PNG and one transparent rig-sheet PNG are returned."),
                    AcceptanceCriterion("The rig sheet contains no appendage-end sockets, cast shadow, background, labels, or unrelated characters."),
                    AcceptanceCriterion("Image-pipeline visual inspection reports PASS after any bounded corrections."),
                ),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
            TaskDefinition(
                id = inspect,
                name = "Audit character assets · ${target.name}",
                objective = """
                    Independently verify the generated media evidence for ${target.name}. Check the
                    pipeline's visual-inspection report against the role and rigging contracts.
                    Report concrete violations only; do not wave through a result merely because an
                    image exists.
                """.trimIndent(),
                roleId = QualityInspector.id,
                requiredRoleAuthority = RoleAuthority.Verify,
                executor = TaskExecutor.RoleAgent(QualityInspector.id),
                dependsOn = setOf(generate, rig),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
            TaskDefinition(
                id = register,
                name = "Register character assets · ${target.name}",
                objective = """
                    Register the verified Node Creature assets for role ${target.id.value}. Preserve
                    the source workflow id ${source.id.value}, media artifact ids, prompt evidence,
                    and verification evidence. Refuse registration if required generated artifacts
                    are absent.
                """.trimIndent(),
                roleId = AssetRegistrar.id,
                requiredRoleAuthority = RoleAuthority.ApproveIntegration,
                executor = TaskExecutor.ExternalService(
                    service = NODE_CHARACTER_IMAGE_PIPELINE_SERVICE,
                    operation = "register:${target.id.value}",
                ),
                dependsOn = setOf(generate, inspect),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
        )
    }

    private fun safeId(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "role" }
        .take(48)
}
