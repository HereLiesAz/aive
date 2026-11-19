package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.releasedDependencies
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration

/** The external service id of the "Post to my chat" step. */
const val CHAT_POST_SERVICE: String = "chat-post"

/**
 * "Post to my chat" (docs/Chat-Bots.md): a creature that sends a bounded, scrubbed text, taken from the steps it waits for,
 * to the keeper's paired chat and to no other. It is made as two creatures: a human-approval gate ("Allow posting to my
 * chat?", the keeper's consent for this run, asked in the app like any outward step) and the post itself, which waits for it.
 * The graph validator refuses a post with no unconditional approval before it, and the executor refuses to send unless
 * that approval was granted in this run.
 */
object ChatPostStep {
    /** The most text one post carries; the platform's own limit applies after it. */
    const val MAX_CHARS: Int = 3_000
    private const val READ_LIMIT = 20_000

    fun approvalTask(id: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = "Allow posting to my chat?",
        objective = "A person decides whether the next step may send text from this run to the paired chat. Say no and nothing is sent.",
        roleId = null,
        executor = TaskExecutor.HumanApproval("Post to my chat?"),
    )

    fun postTask(id: TaskDefinitionId, approvalId: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = "Post to my chat",
        objective = "Send the text of the steps this one waits for to the paired chat. Wire the step that writes the text to this one.",
        roleId = null,
        dependsOn = setOf(approvalId),
        retryPolicy = RetryPolicy(maxAttempts = 1),
        executor = TaskExecutor.ExternalService(service = CHAT_POST_SERVICE, operation = "post"),
    )

    /** True when [task] is a post step. */
    fun isPost(task: TaskDefinition): Boolean = (task.executor as? TaskExecutor.ExternalService)?.service == CHAT_POST_SERVICE

    /** The ids of the tasks [task] waits for, directly or through others. */
    private fun ancestors(definition: WorkflowDefinition, task: TaskDefinition): Set<TaskDefinitionId> {
        val byId = definition.tasks.associateBy { it.id }
        val seen = LinkedHashSet<TaskDefinitionId>()
        val stack = ArrayList<TaskDefinitionId>(task.dependsOn)
        while (stack.isNotEmpty()) {
            val next = stack.removeAt(stack.size - 1)
            if (seen.add(next)) byId[next]?.let { stack.addAll(it.dependsOn) }
        }
        return seen
    }

    /** The unconditional human-approval gates before [task]. */
    internal fun gatesBefore(definition: WorkflowDefinition, task: TaskDefinition): List<TaskDefinition> {
        val byId = definition.tasks.associateBy { it.id }
        return ancestors(definition, task).mapNotNull { byId[it] }
            .filter { it.executor is TaskExecutor.HumanApproval && it.condition is TaskCondition.Always }
    }

    /** The text a post step sends: what the steps it waits for produced, in order, never its own words. Empty when there is none. */
    internal fun textFor(context: TaskExecutorContext): String {
        val byId = context.definition.tasks.associateBy { it.id }
        val parts = context.definition.releasedDependencies(context.task).mapNotNull { id ->
            val dep = byId[id]
            if (dep == null || dep.executor is TaskExecutor.HumanApproval) return@mapNotNull null
            context.run.taskRuns[id]?.artifacts.orEmpty().mapNotNull { it.textContent }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
        return parts.joinToString("\n\n").take(READ_LIMIT)
    }
}

/**
 * Runs the post step. [post] sends to the keeper's paired chat (see [ChatBotManager.postToKeeper]) and returns how many
 * platforms took it. At most once: a restart in the middle is reported, never silently repeated.
 */
class ChatPostExecutorIntegration(
    private val post: suspend (String) -> Int,
) : TaskExecutorIntegration {
    override val orchestrationToolId: String = CHAT_POST_SERVICE
    override val retryDispatchFailures: Boolean = false

    override fun supports(executor: TaskExecutor): Boolean =
        executor is TaskExecutor.ExternalService && executor.service == CHAT_POST_SERVICE

    private fun failed(why: String) = TaskExecutorExecution(status = TaskRunStatus.Failed, progressMessage = why)

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val granted = ChatPostStep.gatesBefore(context.definition, context.task)
            .any { gate -> context.run.taskRuns[gate.id]?.status == TaskRunStatus.Completed }
        if (!granted) return failed("Not sent: you have not allowed posting to your chat in this run.")
        val text = ChatText.safe(ChatPostStep.textFor(context), ChatPostStep.MAX_CHARS)
        if (text.isEmpty()) return failed("Nothing to post: no step it waits for produced text.")
        val delivered = post(text)
        if (delivered == 0) return failed("No paired chat took it. Switch a chat bot on and pair it in the Keeper's room.")
        return TaskExecutorExecution(
            status = TaskRunStatus.Completed,
            artifacts = listOf(
                ArtifactRef(
                    id = ArtifactId("${context.taskRun.id.value}:chat-post:${context.taskRun.attempt}"),
                    kind = ArtifactKind.Verification,
                    taskRunId = context.taskRun.id,
                    label = "Posted to my chat",
                    // What was sent is not kept here; only the fact and its size.
                    textContent = "Posted ${text.length} characters to $delivered chat(s).",
                    mediaType = "text/plain",
                    metadata = mapOf("source" to CHAT_POST_SERVICE),
                    createdAtEpochMillis = context.nowEpochMillis,
                ),
            ),
        )
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        failed("The app restarted before this step finished, so it may or may not have posted. It is not sent again by itself.")
}
