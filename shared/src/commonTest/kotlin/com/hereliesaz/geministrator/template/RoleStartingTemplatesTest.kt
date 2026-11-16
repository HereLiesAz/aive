package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.domain.BudgetClass
import com.hereliesaz.geministrator.domain.RoleStartingTemplates
import com.hereliesaz.geministrator.domain.effectiveAllowedTools
import com.hereliesaz.geministrator.domain.effectiveBudgetClass
import com.hereliesaz.geministrator.domain.aiveBuiltInRoles
import com.hereliesaz.geministrator.queen.splitToolCeiling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 37 starting role templates are all there, are real roles in the roster, and carry no tool ceiling by default. */
class RoleStartingTemplatesTest {
    private val expectedIds = listOf(
        "source-finder", "fact-checker", "connected-reader", "analyst", "summariser", "librarian", "archivist", "writer",
        "editor", "technical-writer", "comms-drafter", "email-drafter", "translator", "scriptwriter", "storyboarder",
        "data-wrangler", "spreadsheet-builder", "data-checker", "page-designer", "diagrammer", "accessibility-checker",
        "bug-reporter", "release-notes-writer", "risk-assessor", "scheduler", "clarifier", "policy-drafter",
        "lesson-writer", "connected-editor", "researcher", "planner", "reviewer", "antagonist", "code-reviewer",
        "code-implementer", "test-writer", "trainer",
    )

    private val byRoleId get() = aiveBuiltInRoles.associateBy { it.id.value }

    @Test
    fun allThirtySevenIdsArePresentAndUnique() {
        val ids = RoleStartingTemplates.catalog.map { it.catalogId }
        assertEquals(37, ids.size)
        assertEquals(expectedIds, ids)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(37, RoleStartingTemplates.catalog.map { it.roleId }.toSet().size)
        assertEquals(37, RoleStartingTemplates.catalog.map { it.name }.toSet().size)
    }

    @Test
    fun theTwentyNineNewOnesAndTheEightBuiltInMirrorsAreSplitAsTheOwnerAnswered() {
        assertEquals(29, RoleStartingTemplates.catalog.count { !it.builtIn })
        assertEquals(8, RoleStartingTemplates.catalog.count { it.builtIn })
        assertEquals(29, RoleStartingTemplates.roles.size)
        assertEquals(
            RoleStartingTemplates.catalog.filter { !it.builtIn }.map { it.roleId },
            RoleStartingTemplates.roles.map { it.id.value },
        )
    }

    @Test
    fun everyOneIsARoleInTheRosterWithInstructionsAndABudgetClass() {
        val roles = byRoleId
        assertEquals(aiveBuiltInRoles.size, roles.size, "role ids in the roster are unique")
        for (entry in RoleStartingTemplates.catalog) {
            val role = assertNotNull(roles[entry.roleId], "no role ${entry.roleId} in the roster")
            assertTrue(role.instructions.isNotBlank(), "${entry.roleId} has no instructions")
            assertTrue(role.description.isNotBlank(), "${entry.roleId} has no description")
            assertTrue(role.name.isNotBlank(), "${entry.roleId} has no name")
            assertEquals(entry.budgetClass, role.effectiveBudgetClass(), "${entry.roleId} class")
            assertEquals(entry.budgetClass, RoleStartingTemplates.classOf(entry.roleId))
            assertTrue(role.enabled, "${entry.roleId} is listed")
        }
    }

    @Test
    fun theNewRolesAreGenericAndCarryNoToolCeilingAndNoNumber() {
        for (role in RoleStartingTemplates.roles) {
            assertEquals(emptyList(), role.toolCeiling, role.id.value)
            assertNull(role.tokenBudget, role.id.value)
            assertNotNull(role.budgetClass, role.id.value)
            assertTrue(role.authorities.isEmpty(), "${role.id.value} holds no authority")
        }
    }

    @Test
    fun everyOneHasARoleTemplateRecordAtGenerationOne() {
        val catalog = TemplateCatalog(BuiltInTemplates.bases())
        for (entry in RoleStartingTemplates.catalog) {
            val record = assertNotNull(catalog.current(TemplateKind.Role, entry.roleId), entry.roleId)
            assertEquals(1, record.version)
            assertEquals(byRoleId.getValue(entry.roleId).instructions, record.body.wording)
            assertTrue(record.body.allowedTools.isEmpty(), "no ceiling by default: ${entry.roleId}")
        }
    }

    // --- the tool ceiling seam: proposed by a revision, applied only once the keeper selects it ---

    private val writer = RoleStartingTemplates.roles.first { it.id.value == "writer" }
    private val intent = RevisionIntent(RevisionAim.LowerCost, "Fewer wasted calls.", "Eleven steps used tools it never needed.", "Tool calls per step below 1 over 10 steps.")

    private fun ceilingDraft(mode: RevisionMode, evidence: Int, tools: List<String>) = TemplateRevisionDraft(
        target = TemplateTarget(TemplateKind.Role, writer.id.value, 1).key,
        allowedTools = tools, mode = mode, intent = intent, evidenceSteps = evidence,
    )

    @Test
    fun aRoleWithNoCeilingMayBeProposedOneOnlyAsANewGenerationWithEvidence() {
        val base = RoleTemplates.base(writer)
        assertTrue(base.body.allowedTools.isEmpty())
        val quick = TemplateRevisionRules.check(base, ceilingDraft(RevisionMode.Memetic, 5, listOf("read_file")))
        assertIs<TemplateRevisionRules.Check.Refused>(quick)
        val thin = TemplateRevisionRules.check(base, ceilingDraft(RevisionMode.Genetic, 9, listOf("read_file")))
        assertIs<TemplateRevisionRules.Check.Refused>(thin)
        val ok = assertIs<TemplateRevisionRules.Check.Accepted>(TemplateRevisionRules.check(base, ceilingDraft(RevisionMode.Genetic, 10, listOf("read_file", "search"))))
        assertEquals(setOf(TunableField.ToolsNarrowed), ok.changed)
        assertEquals(listOf("read_file", "search"), ok.body.allowedTools)
        assertTrue(ok.loosens.isEmpty())
    }

    @Test
    fun aCeilingNameThatIsBlankOrTooLongIsRefused() {
        val base = RoleTemplates.base(writer)
        assertIs<TemplateRevisionRules.Check.Refused>(TemplateRevisionRules.check(base, ceilingDraft(RevisionMode.Genetic, 10, listOf(" "))))
        assertIs<TemplateRevisionRules.Check.Refused>(TemplateRevisionRules.check(base, ceilingDraft(RevisionMode.Genetic, 10, listOf("x".repeat(TemplateRevisionRules.MAX_TOOL_NAME_CHARS + 1)))))
    }

    @Test
    fun onceARoleHasACeilingItOnlyNarrows() {
        val withCeiling = RoleTemplates.base(writer.copy(toolCeiling = listOf("a", "b")))
        assertIs<TemplateRevisionRules.Check.Refused>(TemplateRevisionRules.check(withCeiling, ceilingDraft(RevisionMode.Genetic, 10, listOf("a", "c"))))
        assertIs<TemplateRevisionRules.Check.Accepted>(TemplateRevisionRules.check(withCeiling, ceilingDraft(RevisionMode.Genetic, 10, listOf("a"))))
    }

    @Test
    fun aCeilingCannotBeClearedByAnEmptyRevision() {
        val withCeiling = RoleTemplates.base(writer.copy(toolCeiling = listOf("a", "b")))
        assertIs<TemplateRevisionRules.Check.Refused>(TemplateRevisionRules.check(withCeiling, ceilingDraft(RevisionMode.Genetic, 10, emptyList())))
    }

    @Test
    fun aRaiseAboveTheClassAllowanceCountsAsLoosening() {
        val base = RoleTemplates.base(writer)
        val classBudget = assertNotNull(base.classBudgetTokens)
        val raise = TemplateRevisionDraft(
            target = TemplateTarget(TemplateKind.Role, writer.id.value, 1).key,
            budgetTokens = classBudget + 1, mode = RevisionMode.Genetic, intent = intent, evidenceSteps = 10,
        )
        assertEquals(listOf("the allowance"), assertIs<TemplateRevisionRules.Check.Accepted>(TemplateRevisionRules.check(base, raise)).loosens)
        val cut = raise.copy(budgetTokens = classBudget - 1)
        assertTrue(assertIs<TemplateRevisionRules.Check.Accepted>(TemplateRevisionRules.check(base, cut)).loosens.isEmpty())
    }

    private fun candidate(status: TemplateStatus) = RoleTemplates.base(writer).let { base ->
        TemplateRevision(
            kind = TemplateKind.Role, templateId = writer.id.value, version = 2, parentVersion = 1, generation = 2,
            mutation = setOf(TunableField.ToolsNarrowed), body = base.body.copy(allowedTools = listOf("read_file")), locked = base.locked,
            before = writer.instructions, after = writer.instructions, fitness = "f", proposalId = "p", status = status,
            adoptedAtEpochMillis = 10, mode = RevisionMode.Genetic, intent = intent,
        )
    }

    @Test
    fun aSelectedRevisionKeepsTheKeepersOwnInstructions() {
        val mine = writer.copy(keeperInstructions = "Short sentences, please.")
        val revisions = listOf(candidate(TemplateStatus.Candidate))
        val selected = assertNotNull(TemplateCatalog.select(revisions, TemplateKind.Role, writer.id.value, 2, evidenceSteps = 10, now = 99))
        val resolved = RoleTemplates.resolveAll(listOf(mine), selected).single()
        assertEquals(listOf("read_file"), resolved.toolCeiling, "the revision applied")
        assertEquals("Short sentences, please.", resolved.keeperInstructions)
    }

    @Test
    fun aCandidateCeilingIsNotAppliedUntilTheKeeperSelectsIt() {
        val revisions = listOf(candidate(TemplateStatus.Candidate))
        assertEquals(emptyList(), RoleTemplates.resolveAll(listOf(writer), revisions).single().toolCeiling)
        val selected = assertNotNull(TemplateCatalog.select(revisions, TemplateKind.Role, writer.id.value, 2, evidenceSteps = 10, now = 99))
        assertEquals(listOf("read_file"), RoleTemplates.resolveAll(listOf(writer), selected).single().toolCeiling)
        // Not enough evidence: it cannot be selected at all.
        assertNull(TemplateCatalog.select(revisions, TemplateKind.Role, writer.id.value, 2, evidenceSteps = 9, now = 99))
    }

    @Test
    fun aCeilingOnlyNarrowsAStepsTools() {
        assertEquals(listOf("x"), effectiveAllowedTools(listOf("x"), emptyList()))
        assertNull(effectiveAllowedTools(null, emptyList()))
        assertEquals(listOf("a"), effectiveAllowedTools(null, listOf("a")))
        assertEquals(listOf("a"), effectiveAllowedTools(listOf("a", "z"), listOf("a", "b")))
        assertEquals(emptyList(), effectiveAllowedTools(emptyList(), listOf("a")))
    }

    @Test
    fun theNannyCanNameACeilingInARoleSolution() {
        val (tools, wording) = splitToolCeiling("TOOLS: read_file, search, read_file\nWrite shorter notes.")
        assertEquals(listOf("read_file", "search"), tools)
        assertEquals("Write shorter notes.", wording)
        val (onlyTools, none) = splitToolCeiling("TOOLS: read_file")
        assertEquals(listOf("read_file"), onlyTools)
        assertNull(none)
        val (noTools, all) = splitToolCeiling("Write shorter notes.")
        assertEquals(emptyList(), noTools)
        assertEquals("Write shorter notes.", all)
    }

    @Test
    fun aBudgetClassIsNeverNullForAStartingTemplateAndEveryClassIsUsed() {
        assertEquals(BudgetClass.entries.toSet(), RoleStartingTemplates.catalog.map { it.budgetClass }.toSet())
    }
}
