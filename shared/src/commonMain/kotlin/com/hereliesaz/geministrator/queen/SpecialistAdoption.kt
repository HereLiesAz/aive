package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.InboxEntryKind

/**
 * A specialist role the queen would like to adopt: a variant of a generic role ([parentRoleId], never edited) carrying the
 * memory [lessons] it was derived from. There is no training swarm yet, so nothing builds these today; this is the gate it
 * will have to pass.
 */
data class SpecialistAdoption(val roleId: String, val parentRoleId: String, val lessons: List<String>)

/**
 * The keeper's recorded answer in Messages to the question "may she adopt this specialist?". [entryKind] is the kind of
 * Messages entry it was answered in (a question or an approval); [approvedAtEpochMillis] is when. [approved] false is a
 * recorded refusal. It is tied to exactly the [adoption] the keeper saw: any other request is not covered by it.
 */
internal data class SpecialistApproval(
    val adoption: SpecialistAdoption,
    val entryKind: InboxEntryKind,
    val approved: Boolean,
    val approvedAtEpochMillis: Long,
)

/** How an adoption ended. [Adopted] is the only way a specialist role comes to be adopted. */
sealed interface AdoptionResult {
    data class Adopted(val adoption: SpecialistAdoption) : AdoptionResult

    data class Refused(val why: AdoptionRefusal) : AdoptionResult
}

/** Why she may not adopt. */
enum class AdoptionRefusal { NoApproval, NotApproved, ApprovalIsForAnother, NotAMessagesEntry, WouldReplaceTheGeneric, NothingToCarry, ApplyFailed }

private val ADOPTION_ENTRY_KINDS = setOf(
    InboxEntryKind.Question,
    InboxEntryKind.TaskApproval,
    InboxEntryKind.PlanApproval,
    InboxEntryKind.NestedApproval,
)

/**
 * The gate (pure): null when she may adopt [adoption], else why not. She may only with a recorded keeper approval of exactly
 * this request, answered in a Messages question or approval, that says yes. The specialist must be a new role with a parent
 * (the generic role is never edited or replaced) and carry at least one lesson.
 */
internal fun adoptionRefusal(adoption: SpecialistAdoption, approval: SpecialistApproval?): AdoptionRefusal? {
    if (adoption.roleId.isBlank() || adoption.parentRoleId.isBlank() || adoption.roleId == adoption.parentRoleId) return AdoptionRefusal.WouldReplaceTheGeneric
    if (adoption.lessons.none { it.isNotBlank() }) return AdoptionRefusal.NothingToCarry
    val approved = approval != null && approval.adoption == adoption && approval.entryKind in ADOPTION_ENTRY_KINDS && approval.approved
    // Whether the keeper's yes is needed is asked of the one place that decides it (see needsKeeper); this only says which part is missing.
    if (needsKeeper(Situation.Adopts(approved)) == null) return null
    return when {
        approval == null -> AdoptionRefusal.NoApproval
        approval.adoption != adoption -> AdoptionRefusal.ApprovalIsForAnother
        approval.entryKind !in ADOPTION_ENTRY_KINDS -> AdoptionRefusal.NotAMessagesEntry
        else -> AdoptionRefusal.NotApproved
    }
}

/**
 * Adopts [adoption] through [apply] (the store of lineage and Breed, when it exists) only if [adoptionRefusal] allows it.
 * Refuses, and never calls [apply], without the keeper's recorded approval. An [apply] that fails or says no is a refusal too.
 * A cancelled call propagates.
 */
internal suspend fun adoptSpecialist(
    adoption: SpecialistAdoption,
    approval: SpecialistApproval?,
    apply: suspend (SpecialistAdoption) -> Boolean,
): AdoptionResult {
    adoptionRefusal(adoption, approval)?.let { return AdoptionResult.Refused(it) }
    val applied = try {
        apply(adoption)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    return if (applied) AdoptionResult.Adopted(adoption) else AdoptionResult.Refused(AdoptionRefusal.ApplyFailed)
}
