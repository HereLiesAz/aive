package com.hereliesaz.geministrator.persistence

object WorkflowPersistenceFactory {
    /**
     * The app's own workflow store, one per process: SQLite on Android and desktop, Settings on the
     * web. Every caller that reads or writes the user's workflows must use this instance.
     */
    val durable: DurableWorkflowPersistence by lazy { openDurableWorkflowPersistence() }

    fun createDurable(): WorkflowPersistence = durable
}

internal expect fun openDurableWorkflowPersistence(): DurableWorkflowPersistence
