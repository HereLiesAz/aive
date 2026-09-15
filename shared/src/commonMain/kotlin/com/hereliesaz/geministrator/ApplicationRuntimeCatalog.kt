package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowDefinition

suspend fun ApplicationRuntime.loadWorkflowDefinitions(): List<WorkflowDefinition> =
    persistence.definitions.all().sortedBy { it.name.lowercase() }
