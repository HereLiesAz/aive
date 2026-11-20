package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ChainStatus
import com.hereliesaz.geministrator.events.EventChain
import com.hereliesaz.geministrator.events.TaskStarted
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The run's event journal is hash-chained: each event's hash covers the one before it, so an edit, a deletion or a
 * reordering after the fact changes the tip the store recorded when the events were written.
 */
class EventChainTest {
    private val runId = WorkflowRunId("run")

    private fun started(task: String, at: Long) = TaskStarted(runId, TaskDefinitionId(task), 1, at)

    private fun tempDatabase(): File = Files.createTempDirectory("aive-chain").toFile().resolve("workflows.db")

    private fun sql(file: File, statement: String) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    @Test
    fun theChainIsOrderSensitiveAndEveryLinkChangesTheTip() {
        val a = EventChain.next(EventChain.GENESIS, "one")
        val b = EventChain.next(a, "two")
        assertNotEquals(a, b)
        assertNotEquals(b, EventChain.next(EventChain.next(EventChain.GENESIS, "two"), "one"))
        assertEquals(EventChain.fold(listOf("one", "two")).tip, b)
        assertEquals(2, EventChain.fold(listOf("one", "two")).count)
        assertEquals(64, b.length)
    }

    @Test
    fun appendedEventsVerifyIntactAndSurviveReopening(): Unit = runBlocking {
        val file = tempDatabase()
        val first = desktopSqlWorkflowPersistence(file)
        (1..5).forEach { first.events.append(started("t$it", it.toLong())) }

        val status = first.eventChainStatus(runId)
        assertEquals(ChainStatus.Intact(5), status)
        assertEquals(ChainStatus.Intact(5), desktopSqlWorkflowPersistence(file).eventChainStatus(runId))
    }

    @Test
    fun anEditedEventIsDetected(): Unit = runBlocking {
        val file = tempDatabase()
        val persistence = desktopSqlWorkflowPersistence(file)
        (1..3).forEach { persistence.events.append(started("t$it", it.toLong())) }
        assertEquals(ChainStatus.Intact(3), persistence.eventChainStatus(runId))

        sql(file, "UPDATE event SET payload = replace(payload, 't2', 'tX') WHERE seq = 2")

        assertIs<ChainStatus.Altered>(desktopSqlWorkflowPersistence(file).eventChainStatus(runId))
    }

    @Test
    fun aTimeColumnEditedAwayFromItsPayloadIsDetected(): Unit = runBlocking {
        val file = tempDatabase()
        val persistence = desktopSqlWorkflowPersistence(file)
        (1..3).forEach { persistence.events.append(started("t$it", it.toLong())) }
        assertEquals(ChainStatus.Intact(3), persistence.eventChainStatus(runId))

        // The Log orders by this column, which the payload hash does not cover.
        sql(file, "UPDATE event SET occurred_at = 99 WHERE seq = 1")

        assertIs<ChainStatus.Altered>(desktopSqlWorkflowPersistence(file).eventChainStatus(runId))
    }

    @Test
    fun aDeletedEventIsDetected(): Unit = runBlocking {
        val file = tempDatabase()
        val persistence = desktopSqlWorkflowPersistence(file)
        (1..3).forEach { persistence.events.append(started("t$it", it.toLong())) }

        sql(file, "DELETE FROM event WHERE seq = 3")

        val status = desktopSqlWorkflowPersistence(file).eventChainStatus(runId)
        val altered = assertIs<ChainStatus.Altered>(status)
        assertEquals(3, altered.recorded.count)
        assertEquals(2, altered.actual.count)
    }

    @Test
    fun eventsWrittenBeforeTheChainExistedAreNotReportedBroken(): Unit = runBlocking {
        val file = tempDatabase()
        val persistence = desktopSqlWorkflowPersistence(file)
        persistence.events.append(started("old", 1L))
        // A store from before this change has events and no chain record for the run.
        sql(file, "DELETE FROM meta WHERE key LIKE 'chain:%'")

        val reopened = desktopSqlWorkflowPersistence(file)
        reopened.events.append(started("new", 2L))

        assertEquals(ChainStatus.NoChain, reopened.eventChainStatus(runId))
    }

    @Test
    fun aRunWithNoEventsHasNoChainAndAnotherRunsChainIsIndependent(): Unit = runBlocking {
        val persistence = desktopSqlWorkflowPersistence(tempDatabase())
        persistence.events.append(started("a", 1L))
        val other = WorkflowRunId("other")
        persistence.events.append(TaskStarted(other, TaskDefinitionId("b"), 1, 2L))

        assertEquals(ChainStatus.NoChain, persistence.eventChainStatus(WorkflowRunId("never")))
        assertEquals(ChainStatus.Intact(1), persistence.eventChainStatus(runId))
        assertEquals(ChainStatus.Intact(1), persistence.eventChainStatus(other))
        assertTrue(ProjectId("p").value.isNotEmpty())
    }
}
