package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionApproval
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalLedger
import com.hereliesaz.geministrator.workflow.improvement.ImprovementApplier
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ImprovementStore
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import com.hereliesaz.geministrator.workflow.improvement.SelfImprovementLoop
import com.hereliesaz.geministrator.workflow.schedule.WorkflowScheduleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a decision made on the Improvement screen came to. The screen words it; nothing here does. */
internal sealed interface AdoptionResult {
    /** Applied and recorded. */
    data object Done : AdoptionResult

    /** A kind the app does not apply by itself: recorded as noted, for the keeper to do by hand. */
    data object Noted : AdoptionResult

    /** The role changed since the proposal was drafted; it was set aside and nothing was applied. */
    data object Stale : AdoptionResult

    /** It is no longer waiting (already decided, or no longer in the ledger); nothing was applied. */
    data object AlreadyDecided : AdoptionResult

    /** It cannot be applied as written; nothing was applied. */
    data class Cannot(val why: String) : AdoptionResult

    /** Applying it would leave the self-improvement loop breaking its own rules ([LoopIntegrity]); nothing was applied. */
    data class BreaksLoop(val violations: List<String>) : AdoptionResult

    /** The text is not what it was when adopted, or the hive is gone, so it cannot be put back. */
    data object RevertConflict : AdoptionResult

    /** The save failed; nothing was recorded as adopted. */
    data class SaveFailed(val reason: String) : AdoptionResult

    /** It was applied, but the ledger no longer held it as waiting, so the change is not on record. */
    data object NotRecorded : AdoptionResult
}

/**
 * Adopts, reverts and dismisses what the Improvement screen lists (docs/architecture/SELF_IMPROVEMENT.md §3a). One
 * decision runs at a time: a second one on the same proposal (a double tap) waits, then finds it already decided and
 * changes nothing. Roles and hives are read when the decision runs, not when the screen was drawn, so an edit made in
 * between is never written over; and a proposal is recorded as decided only after the change was saved.
 */
internal class ImprovementAdoption(
    private val store: ImprovementStore,
    private val host: WorkflowLibraryHost?,
    /** Replaces the instructions of one installed role (the Mind's save path); throws when it could not be saved. */
    private val saveRole: suspend (roleId: String, instructions: String) -> Unit,
    private val roleLedger: () -> RoleInstructionProposalLedger = { RoleInstructionProposalsHost.ledger },
    private val now: () -> Long = ::currentEpochMillis,
    /** The keeper's adopted template revisions (the lineage of step templates and roles); null when none is kept. */
    private val templates: com.hereliesaz.geministrator.template.TemplateStore? = null,
) {
    private val decisions = Mutex()

    private suspend fun roles(): List<RoleDefinition> = host?.installedRoles().orEmpty()

    private suspend fun authoredHives(): List<WorkflowDefinition> =
        host?.entries()?.filter { it.origin == WorkflowLibraryOrigin.Authored }?.map { it.definition }.orEmpty()

    private suspend fun waiting(id: String, status: ProposalStatus): ImprovementProposal? =
        store.state().proposals.firstOrNull { it.id == id && it.status == status }

    private fun recorded(applied: AdoptionResult, decided: Boolean): AdoptionResult = if (decided) applied else AdoptionResult.NotRecorded

    private suspend fun save(action: suspend () -> Unit): AdoptionResult.SaveFailed? =
        try {
            action()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AdoptionResult.SaveFailed(failure.message ?: failure::class.simpleName.orEmpty())
        }

    suspend fun adopt(id: String): AdoptionResult = decisions.withLock {
        val proposal = waiting(id, ProposalStatus.Proposed) ?: return@withLock AdoptionResult.AlreadyDecided
        val roles = roles()
        val hives = authoredHives()
        val at = now()
        if (proposal.source == ImprovementSource.RoleHistory && proposal.sourceRef != null) {
            // Decided by the mechanism that made it: its own check that the role is still as it was drafted against.
            val ledger = roleLedger()
            val before = roles.firstOrNull { it.id.value == proposal.target }?.instructions
            return@withLock when (val result = ledger.evaluate(proposal.sourceRef!!, roles)) {
                is RoleInstructionApproval.Applied -> {
                    save { saveRole(proposal.target, result.role.instructions) }?.let { return@withLock it }
                    ledger.markApproved(proposal.sourceRef!!)
                    // The approval is now visible to the mirror too, which may record the same adoption first: either way it is on record.
                    val decided = store.decide(proposal.id) {
                        it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = at, appliedBefore = before, appliedAfter = result.role.instructions)
                    } || waiting(proposal.id, ProposalStatus.Adopted)?.appliedAfter == result.role.instructions
                    appLog("Improvement", "Adopted: ${proposal.summary}")
                    recorded(AdoptionResult.Done, decided)
                }
                RoleInstructionApproval.Stale -> {
                    store.decide(proposal.id) { it.copy(status = ProposalStatus.Superseded, decidedAtEpochMillis = at, note = "The role changed since it was drafted.") }
                    AdoptionResult.Stale
                }
                RoleInstructionApproval.NotFound -> AdoptionResult.Stale
            }
        }
        when (val plan = ImprovementApplier.plan(proposal, roles, hives, templates?.revisions().orEmpty(), at)) {
            is ImprovementApplier.Plan.TemplateRevision -> {
                val store = templates ?: return@withLock AdoptionResult.Cannot("No template store is available.")
                if (plan.stale) {
                    this.store.decide(proposal.id) { it.copy(status = ProposalStatus.Superseded, decidedAtEpochMillis = at, note = "The template changed since it was drafted.") }
                    return@withLock AdoptionResult.Stale
                }
                // The keeper's adoption is the selection: the revision is recorded only if its version is still free.
                val recordedRevision = try {
                    store.adopt(plan.revision)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return@withLock AdoptionResult.SaveFailed(failure.message ?: failure::class.simpleName.orEmpty())
                }
                if (!recordedRevision) return@withLock AdoptionResult.Stale
                val decided = this.store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = at, appliedBefore = plan.before, appliedAfter = plan.after) }
                appLog("Improvement", "Adopted: ${proposal.summary} (${plan.kind.label} ${plan.templateId})")
                recorded(AdoptionResult.Done, decided)
            }
            is ImprovementApplier.Plan.RoleInstructions -> {
                save { saveRole(plan.roleId, plan.after) }?.let { return@withLock it }
                val decided = store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = at, appliedBefore = plan.before, appliedAfter = plan.after) }
                appLog("Improvement", "Adopted: ${proposal.summary} (role ${plan.roleId})")
                recorded(AdoptionResult.Done, decided)
            }
            is ImprovementApplier.Plan.HiveStepObjective -> {
                val hive = hives.first { it.id.value == plan.hiveId }
                val library = host ?: return@withLock AdoptionResult.Cannot("No swarm library is available.")
                // The loop is held to its own rules: a change that would make it hold something up, change something or
                // stop examining itself is refused, with the reasons, before anything is saved.
                ImprovementApplier.loopViolations(hive, plan, roles).takeIf { it.isNotEmpty() }?.let { return@withLock AdoptionResult.BreaksLoop(it) }
                save { library.saveAuthored(ImprovementApplier.applyTo(hive, plan)) }?.let { return@withLock it }
                val decided = store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = at, appliedBefore = plan.before, appliedAfter = plan.after) }
                appLog("Improvement", "Adopted: ${proposal.summary} (${plan.hiveId}/${plan.taskId})")
                recorded(AdoptionResult.Done, decided)
            }
            is ImprovementApplier.Plan.Manual -> {
                val decided = store.decide(proposal.id) { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = at, note = plan.why) }
                appLog("Improvement", "Noted: ${proposal.summary}")
                recorded(AdoptionResult.Noted, decided)
            }
            is ImprovementApplier.Plan.Cannot -> AdoptionResult.Cannot(plan.why)
        }
    }

    suspend fun revert(id: String): AdoptionResult = decisions.withLock {
        val proposal = waiting(id, ProposalStatus.Adopted) ?: return@withLock AdoptionResult.AlreadyDecided
        val before = proposal.appliedBefore
        if (before == null) {
            return@withLock recorded(AdoptionResult.Done, store.decide(proposal.id, ProposalStatus.Adopted) { it.copy(status = ProposalStatus.Reverted, decidedAtEpochMillis = now()) })
        }
        val at = now()
        when (proposal.kind) {
            com.hereliesaz.geministrator.workflow.improvement.ImprovementKind.TaskTemplate -> {
                // Retiring archives the branch (it stays in the lineage); only the newest line can be put back.
                val store = templates ?: return@withLock AdoptionResult.RevertConflict
                val revision = store.revisions().firstOrNull { it.proposalId == proposal.id } ?: return@withLock AdoptionResult.RevertConflict
                val catalog = com.hereliesaz.geministrator.template.TemplateCatalog(
                    com.hereliesaz.geministrator.template.BuiltInTemplates.bases(roles()),
                    store.revisions(),
                )
                val stillCurrent = revision.status == com.hereliesaz.geministrator.template.TemplateStatus.Candidate ||
                    catalog.current(revision.kind, revision.templateId)?.version == revision.version
                if (!stillCurrent) return@withLock AdoptionResult.RevertConflict
                save {
                    store.merge { all ->
                        com.hereliesaz.geministrator.template.TemplateCatalog.retire(all, revision.kind, revision.templateId, revision.version)
                    }
                }?.let { return@withLock it }
            }
            com.hereliesaz.geministrator.workflow.improvement.ImprovementKind.RoleInstruction -> {
                val current = roles().firstOrNull { it.id.value == proposal.target }?.instructions
                if (!ImprovementApplier.canRevert(proposal, current)) return@withLock AdoptionResult.RevertConflict
                save { saveRole(proposal.target, before) }?.let { return@withLock it }
            }
            else -> {
                val hiveId = proposal.target.substringBefore('/')
                val taskId = proposal.target.substringAfter('/', "")
                val hive = authoredHives().firstOrNull { it.id.value == hiveId } ?: return@withLock AdoptionResult.RevertConflict
                val current = hive.tasks.firstOrNull { it.id.value == taskId }?.objective
                if (!ImprovementApplier.canRevert(proposal, current)) return@withLock AdoptionResult.RevertConflict
                val library = host ?: return@withLock AdoptionResult.RevertConflict
                save { library.saveAuthored(hive.copy(tasks = hive.tasks.map { if (it.id.value == taskId) it.copy(objective = before) else it })) }?.let { return@withLock it }
            }
        }
        val decided = store.decide(proposal.id, ProposalStatus.Adopted) { it.copy(status = ProposalStatus.Reverted, decidedAtEpochMillis = at) }
        appLog("Improvement", "Reverted: ${proposal.summary}")
        recorded(AdoptionResult.Done, decided)
    }

    suspend fun dismiss(id: String): AdoptionResult = decisions.withLock {
        val proposal = waiting(id, ProposalStatus.Proposed) ?: return@withLock AdoptionResult.AlreadyDecided
        if (!store.decide(proposal.id) { it.copy(status = ProposalStatus.Dismissed, decidedAtEpochMillis = now()) }) return@withLock AdoptionResult.AlreadyDecided
        // A proposal from run history is dismissed there too, so its lines are never offered again.
        if (proposal.source == ImprovementSource.RoleHistory) proposal.sourceRef?.let { roleLedger().dismiss(it) }
        AdoptionResult.Done
    }

    companion object {
        /**
         * Asks the ticker to start the loop's schedule once, at its next look. It is **not** turned on: a schedule that was
         * off stays off, and one that is on keeps its own cadence. False when there is no such schedule.
         */
        suspend fun runCycleNow(schedules: WorkflowScheduleStore): Boolean {
            val schedule = schedules.all().firstOrNull { it.id == SelfImprovementLoop.SCHEDULE_ID } ?: return false
            schedules.put(schedule.copy(runNowRequested = true))
            return true
        }
    }
}
