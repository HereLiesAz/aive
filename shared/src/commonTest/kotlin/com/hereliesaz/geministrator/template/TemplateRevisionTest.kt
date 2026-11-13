package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.StepEffect
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.workflow.StepOutputValidators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateRevisionTest {
    private val change = BuiltInTemplates.ChangeConnectedSource
    private val doc = BuiltInTemplates.WriteDocument
    private val intent = RevisionIntent(RevisionAim.FewerRejectedOutputs, "Fewer rejected documents.", "Three outputs lacked headings.", "Rejections per run below 1 over 3 runs.")

    private fun draft(base: TemplateBase, wording: String? = null, build: (TemplateRevisionDraft) -> TemplateRevisionDraft = { it }) =
        build(TemplateRevisionDraft(target = TemplateTarget(base.kind, base.id, base.version).key, wording = wording, intent = intent, evidenceSteps = 5))

    private fun accepted(c: TemplateRevisionRules.Check) = assertIs<TemplateRevisionRules.Check.Accepted>(c)
    private fun refused(c: TemplateRevisionRules.Check) = assertIs<TemplateRevisionRules.Check.Refused>(c)

    @Test
    fun aWordingChangeIsAccepted() {
        val base = doc.base()
        val ok = accepted(TemplateRevisionRules.check(base, draft(base, "Write the document. Use two levels of headings.")))
        assertEquals(setOf(TunableField.Wording), ok.changed)
    }

    @Test
    fun aMoreSpecificWordingIsNotRefusedForBeingSpecific() {
        val base = BuiltInRoles.Planner.let(RoleTemplates::base)
        val ok = accepted(TemplateRevisionRules.check(base, draft(base, "Plan lessons for a beginner course in welding, in five short steps.")))
        assertTrue(TunableField.Wording in ok.changed)
    }

    @Test
    fun lockedFieldsCannotBeChangedByAProposal() {
        val base = change.base()
        for (field in listOf("effects", "approval", "outputs", "inputs", "role", "tools", "id")) {
            val claimed = base.locked + (field to "changed")
            val result = refused(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(locked = claimed) }))
            assertTrue(result.reasons.any { it.contains("'$field'") }, field)
        }
        val removed = base.locked - "approval"
        refused(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(locked = removed) }))
        accepted(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(locked = base.locked) }))
    }

    @Test
    fun aRoleKeepsItsIdentityCapabilitiesAndAuthorities() {
        val base = RoleTemplates.base(BuiltInRoles.ImplementationEngineer)
        val widened = base.locked + ("capabilities" to base.locked.getValue("capabilities") + "," + AgentCapability.ShellExecution.name)
        refused(TemplateRevisionRules.check(base, draft(base, "Better words.") { it.copy(locked = widened) }))
        val other = base.locked + ("id" to "someone-else")
        refused(TemplateRevisionRules.check(base, draft(base, "Better words.") { it.copy(locked = other) }))
    }

    @Test
    fun toolsMayOnlyBeNarrowed() {
        val base = TemplateBase(TemplateKind.Role, "r", 1, TemplateBody("w", allowedTools = listOf("a", "b")), mapOf("kind" to "role"))
        val narrowed = accepted(TemplateRevisionRules.check(base, draft(base) { it.copy(allowedTools = listOf("a")) }))
        assertEquals(setOf(TunableField.ToolsNarrowed), narrowed.changed)
        refused(TemplateRevisionRules.check(base, draft(base) { it.copy(allowedTools = listOf("a", "c")) }))
        // A step whose tool the keeper picks has no tool a proposal could add.
        refused(TemplateRevisionRules.check(change.base(), draft(change.base()) { it.copy(allowedTools = listOf("delete_everything")) }))
    }

    @Test
    fun aValidatorMayTightenAndLooseningNeedsConfirmation() {
        val base = doc.base()
        val tightened = accepted(TemplateRevisionRules.check(base, draft(base) { it.copy(validators = mapOf("document" to StepOutputValidators.MARKDOWN_SECTIONED)) }))
        assertTrue(tightened.loosens.isEmpty())
        val tighterBase = base.copy(body = base.body.copy(validators = mapOf("document" to StepOutputValidators.MARKDOWN_SECTIONED)))
        val loosened = accepted(TemplateRevisionRules.check(tighterBase, draft(tighterBase) { it.copy(validators = mapOf("document" to StepOutputValidators.MARKDOWN_DOCUMENT)) }))
        assertEquals(1, loosened.loosens.size)
        refused(TemplateRevisionRules.check(base, draft(base) { it.copy(validators = mapOf("document" to "run-this-script")) }))
        refused(TemplateRevisionRules.check(base, draft(base) { it.copy(validators = mapOf("document" to StepOutputValidators.HTML_CONTAINED)) }))
        refused(TemplateRevisionRules.check(base, draft(base) { it.copy(validators = mapOf("nothing" to StepOutputValidators.MARKDOWN_SECTIONED)) }))
    }

    @Test
    fun allowanceAndAttemptsOnlyTighten() {
        val base = doc.base().copy(body = doc.body.copy(budgetTokens = 1000, maxAttempts = 2))
        accepted(TemplateRevisionRules.check(base, draft(base) { it.copy(budgetTokens = 500) }))
        assertEquals(listOf("the allowance"), accepted(TemplateRevisionRules.check(base, draft(base) { it.copy(budgetTokens = 2000) })).loosens)
        accepted(TemplateRevisionRules.check(base, draft(base) { it.copy(maxAttempts = 1) }))
        refused(TemplateRevisionRules.check(base, draft(base) { it.copy(maxAttempts = 3) }))
        refused(TemplateRevisionRules.check(base, draft(base) { it.copy(budgetTokens = 0) }))
    }

    @Test
    fun aRevisionMustSayWhatItAimsAtAndHaveEnoughEvidence() {
        val base = doc.base()
        refused(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(intent = null) }))
        refused(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(intent = intent.copy(measure = " ")) }))
        refused(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(evidenceSteps = MEMETIC_MIN_STEPS - 1) }))
        refused(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(mode = RevisionMode.Genetic, evidenceSteps = GENETIC_MIN_STEPS - 1) }))
        accepted(TemplateRevisionRules.check(base, draft(base, "New wording.") { it.copy(mode = RevisionMode.Genetic, evidenceSteps = GENETIC_MIN_STEPS) }))
    }

    @Test
    fun wordingMustBePlainAndBounded() {
        val base = doc.base()
        refused(TemplateRevisionRules.check(base, draft(base, " ")))
        refused(TemplateRevisionRules.check(base, draft(base, "a".repeat(TemplateRevisionRules.MAX_WORDING_CHARS + 1))))
        refused(TemplateRevisionRules.check(base, draft(base, "bad\u0000text")))
        refused(TemplateRevisionRules.check(base, draft(base, base.body.wording)))
    }

    @Test
    fun newGenerationsAreProposedRarelyAndQuickChangesOften() {
        assertTrue(proposalAllowed(RevisionMode.Memetic, MEMETIC_MIN_STEPS, swarmEnded = false))
        assertTrue(!proposalAllowed(RevisionMode.Genetic, GENETIC_MIN_STEPS, swarmEnded = false))
        assertTrue(proposalAllowed(RevisionMode.Genetic, GENETIC_MIN_STEPS, swarmEnded = true))
        assertTrue(!proposalAllowed(RevisionMode.Memetic, 0, swarmEnded = true))
    }

    private fun revision(
        version: Int,
        parent: Int,
        generation: Int,
        at: Long,
        base: TemplateBase = doc.base(),
        status: TemplateStatus = TemplateStatus.Approved,
        wording: String = "v$version",
        locked: Map<String, String> = base.locked,
    ) = TemplateRevision(
        kind = base.kind, templateId = base.id, version = version, parentVersion = parent, generation = generation,
        mutation = setOf(TunableField.Wording), body = base.body.copy(wording = wording), locked = locked,
        before = "b", after = wording, fitness = "f", proposalId = "p$version", status = status, adoptedAtEpochMillis = at,
    )

    private fun catalog(vararg revisions: TemplateRevision) = TemplateCatalog(BuiltInTemplates.bases(), revisions.toList())

    @Test
    fun currentIsTheLatestAdoptedValidRevisionAndFallsBackToTheBuiltIn() {
        assertEquals(1, catalog().current(TemplateKind.Step, "write-document")!!.version)
        val c = catalog(revision(2, 1, 1, at = 10), revision(3, 2, 2, at = 20))
        assertEquals(3, c.current(TemplateKind.Step, "write-document")!!.version)
        assertEquals("v3", c.current(TemplateKind.Step, "write-document")!!.body.wording)
        assertNull(c.current(TemplateKind.Step, "nothing-like-it"))
    }

    @Test
    fun aPinnedVersionStaysWhatItWasWhileNewerOnesAreAdopted() {
        val c = catalog(revision(2, 1, 1, at = 10), revision(3, 2, 2, at = 20))
        assertEquals("v2", c.at(TemplateKind.Step, "write-document", 2)!!.body.wording)
        assertEquals(1, c.at(TemplateKind.Step, "write-document", 1)!!.version)
    }

    @Test
    fun siblingsCoexistAndTheLatestAdoptionIsSelected() {
        val c = catalog(revision(2, 1, 1, at = 10), revision(3, 1, 1, at = 20))
        assertEquals(3, c.current(TemplateKind.Step, "write-document")!!.version)
        assertEquals(2, c.at(TemplateKind.Step, "write-document", 2)!!.version)
        assertEquals(4, c.nextVersion(TemplateKind.Step, "write-document"))
    }

    @Test
    fun aRevisionWhoseLockedFieldsDifferFromTheBuiltInIsNeverResolved() {
        val forged = revision(2, 1, 1, at = 10, locked = doc.base().locked + ("effects" to "MutatesConnectedSource"))
        assertEquals(1, catalog(forged).current(TemplateKind.Step, "write-document")!!.version)
    }

    @Test
    fun retiringABranchArchivesItAndKeepsIt() {
        val all = listOf(revision(2, 1, 1, at = 10), revision(3, 2, 2, at = 20), revision(4, 1, 1, at = 5))
        val retired = TemplateCatalog.retire(all, TemplateKind.Step, "write-document", 2)
        assertEquals(listOf(TemplateStatus.Reverted, TemplateStatus.Reverted, TemplateStatus.Approved), retired.map { it.status })
        assertEquals(4, TemplateCatalog(BuiltInTemplates.bases(), retired).current(TemplateKind.Step, "write-document")!!.version)
        assertEquals(3, TemplateCatalog(BuiltInTemplates.bases(), retired).lineage(TemplateKind.Step, "write-document").size)
    }

    @Test
    fun aCandidateGenerationIsNotResolvedUntilPromotedWithEnoughEvidence() {
        val candidate = revision(2, 1, 1, at = 10, status = TemplateStatus.Candidate)
        assertEquals(1, catalog(candidate).current(TemplateKind.Step, "write-document")!!.version)
        assertNull(TemplateCatalog.promote(listOf(candidate), TemplateKind.Step, "write-document", 2, GENETIC_MIN_STEPS - 1))
        val promoted = TemplateCatalog.promote(listOf(candidate), TemplateKind.Step, "write-document", 2, GENETIC_MIN_STEPS)!!
        assertEquals(2, catalog(*promoted.toTypedArray()).current(TemplateKind.Step, "write-document")!!.version)
    }

    @Test
    fun aRoleRevisionChangesInstructionsOnlyAndTheBuiltInStaysTheFallback() {
        val planner = BuiltInRoles.Planner
        val base = RoleTemplates.base(planner)
        val rev = revision(2, 1, 1, at = 1, base = base, wording = "Plan in at most five steps.")
        val c = TemplateCatalog(BuiltInTemplates.bases(), listOf(rev))
        val resolved = RoleTemplates.resolved(planner, c)
        assertEquals("Plan in at most five steps.", resolved.instructions)
        assertEquals(planner.capabilitiesRequired, resolved.capabilitiesRequired)
        assertEquals(planner.authorities, resolved.authorities)
        assertEquals(planner, RoleTemplates.resolved(planner, TemplateCatalog(BuiltInTemplates.bases())))
    }

    @Test
    fun instantiationPinsTheVersionAndBuildsAnEffectfulStepWithItsGate() {
        val base = change.base().copy(version = 3)
        val task = StepInstantiation.task(change, base, TaskDefinitionId("c"), "Change", mapOf("change" to "x", "target" to "y", "content" to "@src.document"), tool = "update_row")
        assertEquals("change-connected-source@3", task.contract!!.template.key)
        assertEquals(listOf("update_row"), task.contract!!.allowedTools)
        assertEquals(1, task.retryPolicy.maxAttempts)
        assertEquals(setOf(TaskDefinitionId("src")), task.dependsOn)
        assertEquals(setOf(StepEffect.MutatesConnectedSource), task.contract!!.effects)
        assertEquals(BuiltInRoles.Worker.id, task.roleId)
    }

    @Test
    fun missingRequiredInputsAreNamed() {
        val task = StepInstantiation.task(change, change.base(), TaskDefinitionId("c"), "Change", mapOf("change" to "x"))
        assertEquals(listOf("target", "content"), StepInstantiation.missingInputs(task.contract!!))
    }

    @Test
    fun theQueenMayMakeOnlyStepsThatChangeNothing() {
        assertTrue(BuiltInTemplates.queenMayMake.none { it.effectful })
        assertTrue(BuiltInTemplates.ChangeConnectedSource !in BuiltInTemplates.queenMayMake)
        assertEquals(6, BuiltInTemplates.all.size)
    }
}
