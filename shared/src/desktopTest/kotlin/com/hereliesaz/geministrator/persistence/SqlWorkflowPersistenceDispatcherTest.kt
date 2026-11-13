package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking

class SqlWorkflowPersistenceDispatcherTest {
    private class Counting : CoroutineDispatcher() {
        val dispatched = AtomicInteger()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.incrementAndGet()
            block.run()
        }
    }

    private val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    @Test
    fun blockingSqliteCallsRunOnTheDispatcherItWasGiven() = runBlocking {
        val io = Counting()
        val persistence = desktopSqlWorkflowPersistence(file = null, dispatcher = io)
        persistence.projects.put(project)
        assertTrue(io.dispatched.get() > 0, "the write went through the injected dispatcher")
        val before = io.dispatched.get()
        assertEquals(project, persistence.projects.get(project.id))
        assertTrue(io.dispatched.get() > before, "so did the read")
    }

    @Test
    fun withoutADispatcherTheCallerKeepsTheWork() = runBlocking {
        val persistence = desktopSqlWorkflowPersistence(file = null)
        persistence.projects.put(project)
        assertEquals(project, persistence.projects.get(project.id))
    }
}
