package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.persistence.ProjectRepository
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.persistence.WorkflowRunRepository

/**
 * An in-memory store whose run and project reads and writes can be made to suspend or throw at a chosen
 * moment, to test what the runtime does when its store is slow, fails, or its caller is cancelled half way.
 */
class FaultInjectingPersistence(
    val inner: InMemoryWorkflowPersistence = InMemoryWorkflowPersistence(),
) : WorkflowPersistence by inner {
    /** Runs before every run write; may suspend or throw. */
    var beforeRunPut: suspend (WorkflowRun) -> Unit = {}

    /** Runs before every run read by id; may suspend or throw. */
    var beforeRunGet: suspend (WorkflowRunId) -> Unit = {}

    /** Runs before every project read (one or all); may suspend or throw. */
    var beforeProjectRead: suspend () -> Unit = {}

    override val runs: WorkflowRunRepository = object : WorkflowRunRepository {
        override suspend fun put(run: WorkflowRun) {
            beforeRunPut(run)
            inner.runs.put(run)
        }

        override suspend fun get(id: WorkflowRunId): WorkflowRun? {
            beforeRunGet(id)
            return inner.runs.get(id)
        }

        override suspend fun byProject(projectId: ProjectId): List<WorkflowRun> = inner.runs.byProject(projectId)
    }

    override val projects: ProjectRepository = object : ProjectRepository {
        override suspend fun put(project: Project) = inner.projects.put(project)

        override suspend fun get(id: ProjectId): Project? {
            beforeProjectRead()
            return inner.projects.get(id)
        }

        override suspend fun all(): List<Project> {
            beforeProjectRead()
            return inner.projects.all()
        }
    }
}
