package com.hereliesaz.geministrator.persistence

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.persistence.db.WorkflowDatabase
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Properties

/** Workflow state in ~/.aive/workflows.db. */
internal actual fun openDurableWorkflowPersistence(): DurableWorkflowPersistence {
    val directory = File(System.getProperty("user.home"), ".aive").apply { mkdirs() }
    return desktopSqlWorkflowPersistence(
        file = File(directory, "workflows.db"),
        dispatcher = Dispatchers.IO,
    )
}

/** Opens (creating or upgrading) a desktop workflow database; `null` [file] means in-memory. */
fun desktopSqlWorkflowPersistence(
    file: File?,
    dispatcher: CoroutineDispatcher? = null,
): SqlWorkflowPersistence {
    file?.parentFile?.mkdirs()
    val url = file?.let { "jdbc:sqlite:${it.absolutePath}" } ?: JdbcSqliteDriver.IN_MEMORY
    return SqlWorkflowPersistence(
        driver = JdbcSqliteDriver(url, Properties(), WorkflowDatabase.Schema.synchronous()),
        dispatcher = dispatcher,
    )
}
