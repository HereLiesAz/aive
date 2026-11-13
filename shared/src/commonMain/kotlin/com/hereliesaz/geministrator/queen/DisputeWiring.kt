package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.workflow.HourlySpend

/** The Source the keeper pinned on the queen's role (the orchestrator), or null when none. A pinned Source never switches silently. */
fun orchestratorPin(roles: Collection<RoleDefinition>): String? =
    roles.firstOrNull { it.id == BuiltInRoles.Orchestrator.id }?.preferredProviderId?.value

private const val VOICE_ROLE_TEXT_MAX = 600

/** The prompt a creature's voice sends: its own role text first, then the queen's question (data to judge). */
fun voicePrompt(roleName: String, roleText: String, prompt: String): String = buildString {
    append("You answer as the ").append(roleName).append(". Your role (stay within it): ")
    append(roleText.replace('\n', ' ').trim().take(VOICE_ROLE_TEXT_MAX))
    append("\n\n").append(prompt)
}

/** [base] speaking as one creature: the same model, with that creature's role text in front. */
fun voiceModel(base: QueenModel, roleName: String, roleText: String): QueenModel =
    QueenModel { prompt -> base.answer(voicePrompt(roleName, roleText, prompt)) }

/**
 * The voices of the creatures that may be in a dispute today (the Nanny and the Antagonist), by role id, each answering through
 * [base] with its own role text (the keeper's edited copy when there is one). Their answers are read by [parseApproachAnswer]:
 * one that is neither agree nor disagree, or that cites an unlisted fact, is not taken as agreement.
 */
fun disputeVoices(base: QueenModel, roles: Collection<RoleDefinition>): Map<String, QueenModel> {
    fun voice(default: RoleDefinition): Pair<String, QueenModel> {
        val role = roles.firstOrNull { it.id == default.id } ?: default
        return default.id.value to voiceModel(base, role.name, role.instructions)
    }
    return mapOf(voice(HallMonitorRole.definition), voice(BuiltInRoles.Antagonist))
}

/** True when the keeper's hourly spend cap leaves room for one more call: no cap, or known spend under it with nothing unpriced. */
fun hourlyCapAllows(hourlyCapUsd: Double?, spent: HourlySpend): Boolean =
    hourlyCapUsd == null || (spent.knownUsd < hourlyCapUsd && !spent.unpricedUsage)

/** [base] that refuses (throws, so the dispute goes to the keeper) when [allows] says the keeper's caps do not. */
fun cappedModel(base: QueenModel, allows: suspend () -> Boolean): QueenModel = object : QueenModel {
    override val uses: List<ModelUse> get() = base.uses

    override suspend fun answer(prompt: String): String {
        if (!allows()) error("The keeper's spend cap leaves no room for the queen to answer")
        return base.answer(prompt)
    }
}

/** Builds the models for one dispute from the current roles (read fresh, so a pin the keeper just set is honoured). Null: she is away. */
fun interface DisputeModelSource {
    suspend fun models(roles: List<RoleDefinition>): com.hereliesaz.geministrator.DisputeModels?
}
