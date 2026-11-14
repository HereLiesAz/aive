package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.OutputSpec
import com.hereliesaz.geministrator.domain.PortSpec
import com.hereliesaz.geministrator.domain.PortType
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.workflow.StepOutputValidators

/*
 * Gated sending (docs/Step-Templates.md). Sending an email or a chat message leaves the device, so these steps declare
 * the effect and always ask the keeper first, through the plan gate every effectful step has, and try once. The
 * approval is PER RECIPIENT: one step carries exactly one recipient, a list of recipients becomes one step each, and
 * each step is held until the keeper approves it, with the draft in its objective, which is what the approval shows.
 * The queen never makes one (they are effectful). Nothing here sends: the one connected tool the keeper picks does,
 * and only after its step's approval.
 */
object StepPresetsSending {
    const val RECIPIENTS_PORT: String = "recipients"

    private val result = OutputSpec("result", ArtifactKind.Verification, "text/plain", "send/v1", StepOutputValidators.CHANGE_RESULT)

    private fun sending(id: String, titleKey: String, inputs: List<PortSpec>, wording: String) = StepTemplate(
        id = id, version = 1, titleKey = titleKey, roleHint = "worker",
        inputs = inputs,
        outputs = listOf(result),
        effects = setOf(StepEffect.LeavesDevice, StepEffect.MutatesConnectedSource),
        planGate = true,
        toolPick = ToolPick.OneChange,
        body = TemplateBody(
            wording = wording,
            validators = mapOf(result.name to result.validatorId),
            budgetTokens = 10_000L,
            maxAttempts = 1,
        ),
    )

    private const val COMMON_RULES =
        " Send it exactly as drafted: change, add and remove no words, and add no other recipient, copy or attachment. " +
            "Send it once: if the target already shows it sent to this recipient, send no second one. Hand back two lines, " +
            "`TARGET: <the recipient>` and `CHANGED: sent`. If you cannot, say why and send nothing."

    val SendEmail = sending(
        "send-email", "step_template_send_email",
        listOf(PortSpec(RECIPIENTS_PORT, PortType.Text, true), PortSpec("draft", PortType.Any, true)),
        "Send the draft given as an email to the one recipient given, with the one mail tool you are offered." + COMMON_RULES,
    )

    val SendChatMessage = sending(
        "send-chat-message", "step_template_send_chat_message",
        listOf(PortSpec(RECIPIENTS_PORT, PortType.Text, true), PortSpec("draft", PortType.Any, true)),
        "Send the draft given as a chat message to the one recipient (a person or a channel) given, with the one chat tool you are offered." + COMMON_RULES,
    )

    val SendReply = sending(
        "send-reply", "step_template_send_reply",
        listOf(
            PortSpec(RECIPIENTS_PORT, PortType.Text, true),
            PortSpec("draft", PortType.Any, true),
            PortSpec("target", PortType.Text, true),
        ),
        "Send the draft given as a reply to the message the target names, to the one recipient given, with the one tool you are offered." + COMMON_RULES,
    )

    val all: List<StepTemplate> = listOf(SendEmail, SendChatMessage, SendReply)
}

/** One recipient's approval request: who would get the draft, and exactly what they would get. */
data class SendApproval(val recipient: String, val draft: String) {
    /** The recipient as approvals are matched: trimmed and lower-cased. */
    val key: String get() = SendingApprovals.keyOf(recipient)
}

/** The pure rules of per-recipient approval. */
object SendingApprovals {
    private val templateIds: Set<String> = StepPresetsSending.all.map { it.id }.toSet()

    fun isSending(templateId: String): Boolean = templateId in templateIds

    fun keyOf(recipient: String): String = recipient.trim().lowercase()

    /**
     * The recipients written in [raw]: split on commas, semicolons and line breaks, trimmed, blanks dropped, a repeat
     * (any letter case) kept once, in the order written. A binding to another step (`@step.port`) is not a literal
     * list and gives none.
     */
    fun recipientsOf(raw: String): List<String> {
        if (StepInstantiation.boundTask(raw.trim()) != null) return emptyList()
        val seen = linkedSetOf<String>()
        val out = mutableListOf<String>()
        raw.split(',', ';', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.forEach { if (seen.add(keyOf(it))) out += it }
        return out
    }

    /** One approval request per recipient; none at all when the draft is blank. */
    fun approvalsFor(recipients: String, draft: String): List<SendApproval> =
        if (draft.isBlank()) emptyList() else recipientsOf(recipients).map { SendApproval(it, draft) }

    /**
     * What may be sent: only the requests whose recipient the keeper approved. A grant for someone who was not asked
     * about adds nothing, and with no grants nothing is sent.
     */
    fun sendable(requests: List<SendApproval>, granted: Set<String>): List<SendApproval> {
        val keys = granted.mapTo(mutableSetOf()) { keyOf(it) }
        return requests.filter { it.key in keys }
    }

    /** True when a step with these inputs carries exactly one literal recipient and a draft: the only form that runs. */
    fun readyToAsk(inputs: Map<String, String>): Boolean =
        recipientsOf(inputs[StepPresetsSending.RECIPIENTS_PORT].orEmpty()).size == 1 && !inputs["draft"].isNullOrBlank()

    /** True when [contract] is a sending step whose recipients are not exactly one literal address. */
    fun needsOneRecipient(contract: TaskContract): Boolean =
        isSending(contract.template.id) &&
            recipientsOf(contract.inputs[StepPresetsSending.RECIPIENTS_PORT].orEmpty()).size != 1

    /**
     * [tasks] with the sending step [taskId] split into one step per recipient: the first keeps its id and the rest get
     * the next free `id-2`, `id-3`, each with the same draft and its own recipient, and every step that waited on the
     * original waits on all of them. Unchanged when the step is not a sending step, names at most one recipient, or still lacks a shared input or its tool.
     */
    fun splitPerRecipient(tasks: List<TaskDefinition>, taskId: TaskDefinitionId): List<TaskDefinition> {
        val task = tasks.firstOrNull { it.id == taskId } ?: return tasks
        val contract = task.contract ?: return tasks
        if (!isSending(contract.template.id)) return tasks
        val recipients = recipientsOf(contract.inputs[StepPresetsSending.RECIPIENTS_PORT].orEmpty())
        if (recipients.size <= 1) return tasks
        // Split only once the shared configuration (draft, target, tool) is complete: the form is filled in the
        // order recipients, draft, tool, and siblings made earlier would keep the blanks of that moment.
        val template = BuiltInTemplates.byId[contract.template.id]
        if (template != null) {
            val incomplete = template.inputs.any {
                it.required && it.name != StepPresetsSending.RECIPIENTS_PORT && contract.inputs[it.name].isNullOrBlank()
            }
            if (incomplete) return tasks
            if (template.toolPick != ToolPick.None && contract.allowedTools.isEmpty()) return tasks
        }
        val taken = tasks.mapTo(mutableSetOf()) { it.id.value }
        val wording = StepInstantiation.wordingOf(task.objective)
        val made = recipients.mapIndexed { index, recipient ->
            val id = if (index == 0) taskId else TaskDefinitionId(
                generateSequence(index + 1) { it + 1 }.map { "${taskId.value}-$it" }.first { it !in taken }.also { taken += it },
            )
            val inputs = contract.inputs + (StepPresetsSending.RECIPIENTS_PORT to recipient)
            task.copy(
                id = id,
                name = "${task.name}: $recipient",
                objective = StepInstantiation.objectiveWith(wording, inputs),
                contract = contract.copy(inputs = inputs),
            )
        }
        val newIds = made.drop(1).map { it.id }.toSet()
        return tasks.flatMap { t ->
            when {
                t.id == taskId -> made
                taskId in t.dependsOn -> listOf(t.copy(dependsOn = t.dependsOn + newIds))
                else -> listOf(t)
            }
        }
    }
}
