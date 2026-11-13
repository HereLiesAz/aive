package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.azphalt.SettingsAuthoredWorkflowStore
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.template.BuiltInTemplates
import com.hereliesaz.geministrator.template.InMemoryTemplateStore
import com.hereliesaz.geministrator.template.RevisionAim
import com.hereliesaz.geministrator.template.RevisionIntent
import com.hereliesaz.geministrator.template.RevisionMode
import com.hereliesaz.geministrator.template.TemplateCatalog
import com.hereliesaz.geministrator.template.TemplateKind
import com.hereliesaz.geministrator.template.TemplateRevisionDraft
import com.hereliesaz.geministrator.template.TemplateStatus
import com.hereliesaz.geministrator.workflow.improvement.ImprovementApplier
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import com.hereliesaz.geministrator.workflow.improvement.SettingsImprovementStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A Nanny's template proposal through the Improvement path: adoption, the locked fields, Revert, and the lineage. */
class ImprovementTemplateTest {
    private val persistence = InMemoryWorkflowPersistence()
    private val authored = SettingsAuthoredWorkflowStore(InMemorySettings())
    private val host = WorkflowLibraryHost(persistence = persistence, storeService = null, authoredStore = authored, launchWorkflow = { _, _, _, _ -> }, onPackagesChanged = {})
    private val store = SettingsImprovementStore(InMemorySettings())
    private val templates = InMemoryTemplateStore()
    private val adoption = ImprovementAdoption(store = store, host = host, saveRole = { _, _ -> }, now = { 5_000L }, templates = templates)

    private val intent = RevisionIntent(RevisionAim.FewerRejectedOutputs, "Fewer rejected documents.", "Three outputs lacked headings.", "Rejections per run below 1 over 3 runs.")

    private fun draft(
        target: String,
        wording: String? = "Write the document the brief asks for, with two levels of headings.",
        mode: RevisionMode = RevisionMode.Memetic,
        steps: Int = 5,
        build: (TemplateRevisionDraft) -> TemplateRevisionDraft = { it },
    ) = build(TemplateRevisionDraft(target = target, wording = wording, mode = mode, intent = intent, evidenceSteps = steps))

    private fun proposal(id: String, target: String, draft: TemplateRevisionDraft) = ImprovementProposal(
        id = id, cycleRunId = "c", kind = ImprovementKind.TaskTemplate, target = target, summary = "Summary $id",
        newText = Json.encodeToString(TemplateRevisionDraft.serializer(), draft), expectedEffect = "e", undo = "u",
        meta = false, createdAtEpochMillis = 1L, source = ImprovementSource.Nanny, evidence = "5 steps",
    )

    private suspend fun waiting(vararg proposals: ImprovementProposal) = store.merge { it.copy(proposals = proposals.toList()) }
    private suspend fun statusOf(id: String) = store.state().proposals.first { it.id == id }
    private suspend fun catalog() = TemplateCatalog(BuiltInTemplates.bases(host.installedRoles()), templates.revisions())

    @Test
    fun aQuickChangeIsAdoptedAsTheNextVersionAndRevertedByArchivingIt() = runTest {
        val target = "step:write-document@1"
        waiting(proposal("t1", target, draft(target)))

        assertEquals(AdoptionResult.Done, adoption.adopt("t1"))
        assertEquals(ProposalStatus.Adopted, statusOf("t1").status)
        val current = catalog().current(TemplateKind.Step, "write-document")!!
        assertEquals(2, current.version)
        assertTrue(current.body.wording.contains("two levels of headings"))
        assertEquals(1, templates.revisions().single().generation)
        assertEquals(AdoptionResult.AlreadyDecided, adoption.adopt("t1"))

        assertEquals(AdoptionResult.Done, adoption.revert("t1"))
        assertEquals(1, catalog().current(TemplateKind.Step, "write-document")!!.version)
        assertEquals(TemplateStatus.Reverted, templates.revisions().single().status, "archived, not deleted")
        assertEquals(ProposalStatus.Reverted, statusOf("t1").status)
    }

    @Test
    fun aNewGenerationIsKeptBesideItsParentUntilItIsSelected() = runTest {
        val target = "step:write-document@1"
        waiting(proposal("g1", target, draft(target, mode = RevisionMode.Genetic, steps = 12)))
        assertEquals(AdoptionResult.Done, adoption.adopt("g1"))
        val revision = templates.revisions().single()
        assertEquals(TemplateStatus.Candidate, revision.status)
        assertEquals(2, revision.generation)
        assertEquals(1, catalog().current(TemplateKind.Step, "write-document")!!.version, "the parent line keeps running")
    }

    @Test
    fun aProposalThatTouchesALockedFieldIsRefusedAndNothingIsRecorded() = runTest {
        val target = "step:change-connected-source@1"
        val locked = BuiltInTemplates.ChangeConnectedSource.locked + ("approval" to "none")
        waiting(proposal("l1", target, draft(target) { it.copy(locked = locked) }))
        val result = assertIs<AdoptionResult.Cannot>(adoption.adopt("l1"))
        assertTrue(result.why.contains("approval"))
        assertTrue(templates.revisions().isEmpty())
        assertEquals(ProposalStatus.Proposed, statusOf("l1").status)
    }

    @Test
    fun aRoleRevisionMayBeMoreSpecificButNeverChangeWhatTheRoleMayDo() = runTest {
        val role = BuiltInRoles.Planner
        persistence.roles.put(role)
        val target = "role:${role.id.value}@1"
        waiting(
            proposal("r1", target, draft(target, wording = "Plan a beginner welding course in five short steps.")),
            proposal("r2", target, draft(target, wording = "Plan.") { it.copy(locked = mapOf("capabilities" to "ShellExecution")) }),
        )
        assertEquals(AdoptionResult.Done, adoption.adopt("r1"))
        assertIs<AdoptionResult.Cannot>(adoption.adopt("r2"))
        val resolved = com.hereliesaz.geministrator.template.RoleTemplates.resolved(role, catalog())
        assertEquals("Plan a beginner welding course in five short steps.", resolved.instructions)
        assertEquals(role.capabilitiesRequired, resolved.capabilitiesRequired)
    }

    @Test
    fun aRevisionWithoutEnoughEvidenceOrAnAimIsRefused() = runTest {
        val target = "step:write-document@1"
        waiting(
            proposal("e1", target, draft(target, steps = 1)),
            proposal("e2", target, draft(target) { it.copy(intent = null) }),
        )
        assertIs<AdoptionResult.Cannot>(adoption.adopt("e1"))
        assertIs<AdoptionResult.Cannot>(adoption.adopt("e2"))
        assertTrue(templates.revisions().isEmpty())
    }

    @Test
    fun aVersionRetiredSinceDraftingIsStaleAndAnUnreadableProposalCannotBeApplied() = runTest {
        val target = "step:write-document@1"
        val applier = ImprovementApplier.plan(proposal("s1", "step:write-document@7", draft("step:write-document@7")), host.installedRoles(), emptyList())
        assertIs<ImprovementApplier.Plan.TemplateRevision>(applier)
        assertTrue(applier.stale)
        val garbage = proposal("x1", target, draft(target)).copy(newText = "not json")
        assertIs<ImprovementApplier.Plan.Cannot>(ImprovementApplier.plan(garbage, host.installedRoles(), emptyList()))
    }

    @Test
    fun siblingsBranchFromOneParentAndBothAreKept() = runTest {
        val target = "step:write-document@1"
        waiting(
            proposal("a", target, draft(target, wording = "Variant A of the wording.")),
            proposal("b", target, draft(target, wording = "Variant B of the wording.")),
        )
        assertEquals(AdoptionResult.Done, adoption.adopt("a"))
        assertEquals(AdoptionResult.Done, adoption.adopt("b"))
        val lineage = catalog().lineage(TemplateKind.Step, "write-document")
        assertEquals(listOf(1, 1), lineage.map { it.parentVersion })
        assertEquals(listOf(2, 3), lineage.map { it.version })
        assertEquals(3, catalog().current(TemplateKind.Step, "write-document")!!.version)
    }
}
