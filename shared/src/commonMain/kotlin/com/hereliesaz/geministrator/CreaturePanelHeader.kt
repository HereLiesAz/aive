package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId

/** A creature's place in its plan: its position in plan order (from 1) and how many creatures the plan has. */
internal data class PlanPlace(val position: Int, val of: Int)

/** Where [taskId] sits in [tasks], the plan's order; null when the plan does not hold it. */
internal fun planPlace(tasks: List<TaskDefinition>, taskId: TaskDefinitionId): PlanPlace? {
    val index = tasks.indexOfFirst { it.id == taskId }
    return if (index < 0) null else PlanPlace(position = index + 1, of = tasks.size)
}

/** A provider's name as the keeper knows it, or its id when the catalog has no entry for it. */
internal fun providerDisplayName(providerId: String): String =
    ProviderCatalog.entry(providerId)?.displayName ?: providerId

/**
 * The creature panel's header line, under its name: its state, its provider when it has one, and
 * its place in the plan, joined by " · ". Missing parts are left out, never filled in.
 */
internal fun creatureHeaderLine(state: String, provider: String?, place: String?): String =
    listOfNotNull(state, provider?.takeIf(String::isNotBlank), place).joinToString(" · ")

/** Which Source answered a step's work and which ones it moved on from; names as the keeper knows them. */
internal data class AnsweredBy(val source: String, val failed: List<String>)

/**
 * Who answered a step, from what the step's run recorded: the Source it is assigned to and the Sources it
 * already failed over from ([failedProviderIds], the Source itself never counted among them, each named once,
 * in the order they failed). Null when no Source has answered yet.
 */
internal fun answeredBy(providerId: String?, failedProviderIds: List<String>): AnsweredBy? {
    val id = providerId?.takeIf(String::isNotBlank) ?: return null
    val failed = failedProviderIds.filter { it.isNotBlank() && it != id }.distinct().map(::providerDisplayName)
    return AnsweredBy(providerDisplayName(id), failed)
}
