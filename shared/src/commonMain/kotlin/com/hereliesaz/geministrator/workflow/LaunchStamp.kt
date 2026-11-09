package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.persistence.WorkflowPersistence

/**
 * The stamp a new launch's ids are made of (`run-<stamp>`, `workflow-<stamp>`, `project-<stamp>`): the clock's
 * millisecond [now], with a counter added when a run, hive or project already holds an id made from it. Two
 * launches in the same millisecond, or a clock that stepped back, must not make the same id, because the
 * stores replace a record that has the id they are given: the second launch would overwrite the first.
 * [newProject] is false when the launch uses a project that exists.
 */
internal suspend fun WorkflowPersistence.uniqueLaunchStamp(now: Long, newProject: Boolean = true): String {
    var counter = 1
    while (true) {
        val stamp = if (counter == 1) "$now" else "$now-$counter"
        val taken = runs.get(WorkflowRunId("run-$stamp")) != null ||
            definitions.get(WorkflowDefinitionId("workflow-$stamp")) != null ||
            (newProject && projects.get(ProjectId("project-$stamp")) != null)
        if (!taken) return stamp
        counter += 1
    }
}
