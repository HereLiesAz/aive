package com.hereliesaz.geministrator.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The palette's arrangement: the catalog's groups, the internal nine hidden by default, nothing dropped. */
class TemplateGroupsTest {
    private val everything = BuiltInTemplates.palette

    @Test
    fun thereAreThirteenGroupsAndEveryMemberIsInOne() {
        assertEquals(13, TemplateGroups.groups.size)
        val members = TemplateGroups.groups.flatMap { it.members }
        assertEquals(members.size, members.toSet().size)
        assertTrue("send-email" in members && "swarm-plan" in members)
    }

    @Test
    fun theInternalNineAreTheTwoLastGroupsExactly() {
        assertEquals(9, TemplateGroups.internal.size)
        val last = TemplateGroups.groups.takeLast(2).flatMap { it.members }.toSet()
        assertEquals(TemplateGroups.internal, last)
        TemplateGroups.internal.forEach { assertTrue(TemplateGroups.isInternal(it)) }
        assertTrue(!TemplateGroups.isInternal("write-document"))
    }

    @Test
    fun groupAssignmentFollowsTheCatalog() {
        assertEquals("documents", TemplateGroups.groupOf("write-document")?.id)
        assertEquals("connected-tools", TemplateGroups.groupOf("change-connected-source")?.id)
        assertEquals("writing-for-people", TemplateGroups.groupOf("send-email")?.id)
        assertEquals("software-changes", TemplateGroups.groupOf("change-code")?.id)
        assertNull(TemplateGroups.groupOf("no-such-template"))
    }

    @Test
    fun everyTemplateInTheCodeHasAGroupAndAppearsOnce() {
        val shown = TemplateGroups.arrange(everything).flatMap { it.shown + it.folded }
        assertEquals(everything.map { it.id }.sorted(), shown.map { it.id }.sorted())
        assertEquals(shown.size, shown.map { it.id }.toSet().size)
    }

    @Test
    fun eachGroupShowsItsCommonOnesFirstAndFoldsTheRest() {
        TemplateGroups.arrange(everything).forEach { view ->
            assertTrue(view.shown.size <= view.group.common, view.group.id)
            assertTrue(view.shown.isNotEmpty(), view.group.id)
            if (view.folded.isNotEmpty()) assertEquals(view.group.common, view.shown.size, view.group.id)
        }
        val documents = TemplateGroups.arrange(everything).first { it.group.id == "documents" }
        assertEquals(listOf("write-document", "docs-draft"), documents.shown.map { it.id }.take(2))
    }

    @Test
    fun groupsKeepTheCatalogOrder() {
        val order = TemplateGroups.arrange(everything).map { it.group.id }
        val catalog = TemplateGroups.groups.map { it.id }
        assertEquals(order.filter { it in catalog }, catalog.filter { it in order })
    }

    @Test
    fun theInternalNineAreHiddenByDefaultAndBackWhenAsked() {
        val internal = TemplateGroups.internal.map { id ->
            BuiltInTemplates.WriteDocument.copy(id = id)
        }
        // The nine real templates in the code are exactly the internal ids; the stand-ins below replace them in the mix.
        assertEquals(TemplateGroups.internal, TemplateGroups.internalOf(everything).map { it.id }.toSet())
        val mixed = TemplateGroups.visible(everything) + internal
        assertTrue(TemplateGroups.arrange(mixed).flatMap { it.shown + it.folded }.none { TemplateGroups.isInternal(it.id) })
        assertTrue(TemplateGroups.arrange(mixed).none { it.group.id == "swarm-plans-and-teaching" })
        val all = TemplateGroups.arrange(mixed, includeInternal = true).flatMap { it.shown + it.folded }
        assertEquals(mixed.size, all.size)
        assertEquals(internal.size, TemplateGroups.internalOf(mixed).size)
        assertNotNull(TemplateGroups.arrange(mixed, true).firstOrNull { it.group.id == "training-and-improvement" })
    }

    @Test
    fun aTemplateNoGroupNamesIsNotDropped() {
        val stray = BuiltInTemplates.WriteDocument.copy(id = "stray-preset")
        val views = TemplateGroups.arrange(listOf(BuiltInTemplates.WriteDocument, stray))
        assertEquals(TemplateGroups.OTHER_ID, views.last().group.id)
        assertEquals(listOf("stray-preset"), views.last().shown.map { it.id })
    }
}
