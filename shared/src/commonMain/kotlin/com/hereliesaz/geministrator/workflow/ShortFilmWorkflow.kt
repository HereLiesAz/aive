package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId

/** The external service that draws one frame per shot (providers/llm `FilmFramesExecutorIntegration`). */
const val FILM_FRAMES_SERVICE: String = "film-frames"

/** The external service that assembles the frames into an .mp4 with ffmpeg (desktop `FilmAssembleExecutorIntegration`). */
const val FILM_ASSEMBLE_SERVICE: String = "film-assemble"

/**
 * The short-film hive (docs/Short-Film.md): an objective becomes a storyboard, a style sheet, one still
 * frame per shot from the image model, a review that can send single shots back, and an .mp4 that
 * ffmpeg assembles from them, ending at your approval.
 *
 * It needs the desktop app: the frames step needs an OpenAI connection and the assemble step runs
 * ffmpeg on this computer. Where an executor is not registered (Android, web) the engine blocks the
 * step with "not available in this runtime" instead of waiting for it. It spends money (one image
 * per shot), so it is on demand only: it has no suggested schedule and is not part of the coding suite.
 */
object ShortFilmHive {
    const val ID: String = "short-film"

    /** The names a keeper sees. */
    const val NAME: String = "Short film"

    const val DESCRIPTION: String =
        "An objective becomes a short film made of still frames: concept, storyboard, one style sheet, " +
            "one image per shot, a frame review that can send single shots back, an .mp4 assembled with " +
            "ffmpeg, and your approval. Needs the desktop app, an OpenAI connection and ffmpeg installed; " +
            "it spends one image per shot and runs only when you start it."

    private val concept: RoleDefinition = BuiltInRoles.ProductManager
    private val designer: RoleDefinition = BuiltInRoles.UxDesigner
    private val reviewer: RoleDefinition = BuiltInRoles.AdversarialReviewer
    private val release: RoleDefinition = BuiltInRoles.ReleaseEngineer

    /** The task ids in order; the hive's tasks are exactly these. */
    val taskOrder: List<String> = listOf(
        FILM_TASK_CONCEPT, FILM_TASK_STORYBOARD, FILM_TASK_STYLE_SHEET, FILM_TASK_FRAMES,
        FILM_TASK_FRAME_REVIEW, FILM_TASK_ASSEMBLE, FILM_TASK_APPROVE,
    )

    fun definition(): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId(ID),
        name = NAME,
        description = DESCRIPTION,
        tasks = listOf(
            agentStep(
                FILM_TASK_CONCEPT, "Find the film", concept, RoleAuthority.ProposePlan,
                """
                Turn the run's objective into a film concept: a logline, the tone, the viewer, the look and the
                story in a few beats. Aim for 6 to 24 shots unless the objective says otherwise, and never more
                than $FILM_MAX_SHOTS shots or $FILM_MAX_TOTAL_SECONDS seconds. This film is made of still frames,
                so say what can be shown in a picture, not what needs motion or sound. Do not write shot prompts yet.
                """,
                criteria = listOf("The story can be told in still frames", "The length is inside the limits"),
            ),
            agentStep(
                FILM_TASK_STORYBOARD, "Write the storyboard", designer, RoleAuthority.ProposePlan,
                """
                Write the shot list as ONE JSON artifact and nothing else:
                {"shots":[{"id":"s01","prompt":"...","durationSeconds":4,"narration":"..."}]}
                Each shot is one still picture. "id": letters, digits, '-' or '_', at most $FILM_MAX_SHOT_ID_CHARS characters, unique.
                "prompt": what the picture shows (subject, setting, framing, light), at most $FILM_MAX_PROMPT_CHARS characters,
                with no text in the picture and no real, named person. "durationSeconds": $FILM_MIN_SHOT_SECONDS to $FILM_MAX_SHOT_SECONDS.
                "narration": optional, never drawn. At most $FILM_MAX_SHOTS shots and $FILM_MAX_TOTAL_SECONDS seconds in all.
                Do not repeat the style in each prompt: the next step writes it once.
                """,
                after = listOf(FILM_TASK_CONCEPT),
                criteria = listOf("The JSON parses", "Limits are respected"),
            ),
            agentStep(
                FILM_TASK_STYLE_SHEET, "Write the style sheet", designer, RoleAuthority.ProposePlan,
                """
                Write ONE style block, at most $FILM_MAX_STYLE_CHARS characters of plain text, that is added to every shot's
                prompt: the medium, palette, light, lens, mood and how recurring characters and places always look.
                Describe no particular shot. Every frame is drawn from this text, so make it specific.
                """,
                after = listOf(FILM_TASK_CONCEPT, FILM_TASK_STORYBOARD),
            ),
            externalStep(
                FILM_TASK_FRAMES, "Draw the frames", FILM_FRAMES_SERVICE, "frames",
                """
                Draw one still frame per shot of the storyboard, each with the style sheet applied, using the
                image model. At most $FILM_FRAME_PARALLELISM at a time; a shot is retried at most $FILM_SHOT_MAX_RETRIES times;
                the run stops at its image budget ($FILM_IMAGE_BUDGET_DEFAULT images unless $FILM_IMAGE_BUDGET_ENV says otherwise).
                Frames already drawn for this storyboard are kept: running this step again draws only the missing
                shots and the shots the frame review sent back (a line "regenerate-shots: s03, s07" in this
                step's objective sends more). Needs the desktop app and an OpenAI connection.
                """,
                after = listOf(FILM_TASK_STORYBOARD, FILM_TASK_STYLE_SHEET),
                criteria = listOf("Every shot has a frame", "The image budget was not exceeded"),
            ),
            agentStep(
                FILM_TASK_FRAME_REVIEW, "Review the frames", reviewer, RoleAuthority.RejectPlan,
                """
                Challenge the frames against their shot prompts and the style sheet. The frame artifacts give each
                shot's file and prompt. Name only shots with a concrete defect (wrong subject, broken anatomy,
                text or watermark in the picture, a style that does not match the others). State the result as one JSON object:
                {"regenerate-shots":["s03","s07"],"notes":"why"}. An empty list means every frame stands. Never name a
                shot to improve it; every shot you send back costs an image. State only what you saw, not what the prompt promised.
                """,
                after = listOf(FILM_TASK_STORYBOARD, FILM_TASK_STYLE_SHEET, FILM_TASK_FRAMES),
            ),
            externalStep(
                FILM_TASK_ASSEMBLE, "Assemble the film", FILM_ASSEMBLE_SERVICE, "assemble",
                """
                Assemble the frames into an .mp4 with ffmpeg: each frame held for its duration with a slow zoom,
                a crossfade between shots, H.264, no sound. It refuses to run while the frame review has sent shots
                back (it says which; run the frames step again and only those are drawn). Needs the desktop app
                and ffmpeg on this computer.
                """,
                after = listOf(FILM_TASK_STORYBOARD, FILM_TASK_FRAMES, FILM_TASK_FRAME_REVIEW),
                criteria = listOf("An .mp4 exists whose length is the sum of the shot durations"),
            ),
            TaskDefinition(
                id = TaskDefinitionId(FILM_TASK_APPROVE),
                name = "Approve the film",
                objective = "A person watches the film and decides whether it stands. To send shots back, run the frames step again from here.",
                roleId = release.id,
                requiredRoleAuthority = RoleAuthority.ApproveRelease,
                dependsOn = setOf(TaskDefinitionId(FILM_TASK_ASSEMBLE)),
                executor = TaskExecutor.HumanApproval("Approve the film"),
            ),
        ),
        integrationPolicy = IntegrationPolicy.Manual,
        testDesignPolicy = TestDesignPolicy.None,
    )

    /** The hive as an entry for the library host to install; never scheduled. */
    fun entry(): CreativeSuiteEntry = CreativeSuiteEntry(definition())

    private fun agentStep(
        id: String,
        name: String,
        role: RoleDefinition,
        authority: RoleAuthority,
        objective: String,
        after: List<String> = emptyList(),
        criteria: List<String> = emptyList(),
    ) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = name,
        objective = objective.trimIndent().trim(),
        roleId = role.id,
        requiredRoleAuthority = authority,
        dependsOn = after.map(::TaskDefinitionId).toSet(),
        acceptanceCriteria = criteria.map(::AcceptanceCriterion),
        escalationPolicy = EscalationPolicy.RequireHumanDecision,
        executor = TaskExecutor.RoleAgent(role.id),
    )

    /**
     * An executor step. It never retries by itself (each retry could spend images), and a failure asks the
     * keeper, who can raise the budget or install ffmpeg and run it again.
     */
    private fun externalStep(
        id: String,
        name: String,
        service: String,
        operation: String,
        objective: String,
        after: List<String>,
        criteria: List<String> = emptyList(),
    ) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = name,
        objective = objective.trimIndent().trim(),
        roleId = null,
        dependsOn = after.map(::TaskDefinitionId).toSet(),
        acceptanceCriteria = criteria.map(::AcceptanceCriterion),
        retryPolicy = RetryPolicy(maxAttempts = 1),
        escalationPolicy = EscalationPolicy.RequireHumanDecision,
        executor = TaskExecutor.ExternalService(service = service, operation = operation),
    )
}

/** A hive of the creative suite. Unlike the coding suite's entries, none is ever scheduled: they spend money. */
data class CreativeSuiteEntry(val definition: WorkflowDefinition)

/** The creative hives that come with the app. They need the desktop app (docs/Short-Film.md). */
object CreativeWorkflowSuite {
    fun all(): List<CreativeSuiteEntry> = listOf(ShortFilmHive.entry())

    fun definitions(): List<WorkflowDefinition> = all().map { it.definition }
}
