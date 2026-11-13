package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.staffingMissing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueenMissingNotesTest {
    private val definition = qDefinition(qTask("a"))
    private val run = qRun(qTaskRun("a", TaskRunStatus.Running))

    private fun voices(missing: List<Missing>, level: QueenLevel) =
        queenUpdate(definition, run, BuiltInRoles.all, level, null, null, missing).map { it.voice }.filter { it.relay == QueenRelay.Missing }

    @Test
    fun noSourceIsSaidOnceWithALinkToConnectOne() {
        val note = missingNotes("run-1", listOf(Missing(MissingKind.NoSource, null, MissingFix.ConnectSource)), QueenLevel.Quiet).single()
        assertEquals(QueenVoice.MissingNoSource, note.voice)
        assertEquals(QueenLink.Connections, note.link)
    }

    @Test
    fun aSwitchedOffRoleLinksToTheRosterAndNamesTheRole() {
        val note = missingNotes("run-1", listOf(Missing(MissingKind.NoPlanner, "Planner", MissingFix.EnableRole)), QueenLevel.Quiet).single()
        assertEquals(QueenVoice.MissingNoPlanner, note.voice)
        assertEquals(listOf("Planner"), note.args)
        assertEquals(QueenLink.Roster, note.link)
    }

    @Test
    fun theSecondOpinionAdviceIsOnlyForHelpful() {
        val same = listOf(Missing(MissingKind.SameSource, "a", MissingFix.ConnectSource))
        assertEquals(emptyList(), voices(same, QueenLevel.Quiet))
        assertEquals(listOf(QueenVoice.MissingSameSource), voices(same, QueenLevel.Helpful))
    }

    @Test
    fun whatBlocksHerIsSaidAtQuietAndNotesAreStable() {
        val none = listOf(Missing(MissingKind.NoSource, null, MissingFix.ConnectSource))
        assertEquals(listOf(QueenVoice.MissingNoSource), voices(none, QueenLevel.Quiet))
        assertEquals(
            queenUpdate(definition, run, BuiltInRoles.all, QueenLevel.Quiet, null, null, none),
            queenUpdate(definition, run, BuiltInRoles.all, QueenLevel.Quiet, null, null, none),
        )
    }

    @Test
    fun noMissingThingsAddsNothing() {
        assertEquals(queenUpdate(definition, run, BuiltInRoles.all, QueenLevel.Quiet), queenUpdate(definition, run, BuiltInRoles.all, QueenLevel.Quiet, null, null, emptyList()))
    }

    @Test
    fun staffingMissingPutsNoSourceFirstAndOmitsSameSourceWithoutStaff() {
        assertEquals(listOf(MissingKind.NoSource), staffingMissing(staffQueen(BuiltInRoles.all), emptyList()).map { it.kind })
        assertEquals(listOf(MissingKind.SameSource), staffingMissing(staffQueen(BuiltInRoles.all), listOf("a")).map { it.kind })
        assertTrue(staffingMissing(staffQueen(BuiltInRoles.all), listOf("a", "b")).isEmpty())
        val noPlanner = BuiltInRoles.all.map { if (it.id == BuiltInRoles.Planner.id) it.copy(enabled = false) else it }
        assertEquals(listOf(MissingKind.NoPlanner), staffingMissing(staffQueen(noPlanner), listOf("a")).map { it.kind })
    }
}
