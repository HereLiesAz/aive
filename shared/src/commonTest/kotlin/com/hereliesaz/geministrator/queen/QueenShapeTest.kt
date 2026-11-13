package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueenShapeTest {
    private val definition = qDefinition(qTask("a"), qTask("b").copy(dependsOn = listOf(TaskDefinitionId("a"))))
    private val run = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Retrying, attempt = 4))
    private val roles = BuiltInRoles.all

    private fun found() = shapeCandidates(definition, run, queenSignals(definition, run, roles), roles)

    @Test
    fun aLateRepeatedFailureOffersACheckASplitAndAnApprovalButNeverARemoval() {
        val actions = found().map { it.action }
        assertTrue(QueenAction.AddCheck in actions)
        assertTrue(QueenAction.SplitStep in actions)
        assertTrue(QueenAction.AddApproval in actions)
        assertTrue(actions.all { it.keepsForNextTime })
    }

    @Test
    fun aShapeChangeIsNeverAppliedLiveOnlyKept() {
        assertFalse(QueenAction.AddCheck.appliesHere)
        val row = queenNewProposals(definition, run, found(), emptyList(), QueenLevel.Quiet, 0L).single()
        assertFalse(queenAcceptRefused(row, definition, run, roles), "Keeping is allowed while the swarm runs")
        val kept = keptForNextTime(row, 5L)
        assertEquals(ImprovementSource.QueenKept, kept.source)
        assertEquals(ProposalStatus.Proposed, kept.status)
        assertEquals("kept:" + row.id, kept.id)
        assertEquals("", kept.newText)
    }

    @Test
    fun aHealthyRunOffersNoShapeChange() {
        val healthy = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running))
        assertTrue(shapeCandidates(definition, healthy, queenSignals(definition, healthy, roles), roles).isEmpty())
    }
}
