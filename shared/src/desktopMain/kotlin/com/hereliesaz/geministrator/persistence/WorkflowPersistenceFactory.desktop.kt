package com.hereliesaz.geministrator.persistence

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.persistence.db.WorkflowDatabase
import java.io.File
import java.util.Properties

/** Workflow state in ~/.aive/workflows.db; the earlier Java Preferences store moves in on first use. */
internal actual fun openDurableWorkflowPersistence(): DurableWorkflowPersistence {
    val directory = File(System.getProperty("user.home"), ".aive").apply { mkdirs() }
    return desktopSqlWorkflowPersistence(
        file = File(directory, "workflows.db"),
        migrateFrom = LegacySettingsMigration(SettingsWorkflowPersistence.createDefault()) { exported ->
            File(directory, "workflows-settings-backup-${System.currentTimeMillis()}.json").writeText(exported)
        },
    )
}

/** Opens (creating or migrating) a desktop workflow database; `null` [file] means in-memory. */
fun desktopSqlWorkflowPersistence(
    file: File?,
    migrateFrom: LegacySettingsMigration? = null,
): SqlWorkflowPersistence {
    file?.parentFile?.mkdirs()
    val url = file?.let { "jdbc:sqlite:${it.absolutePath}" } ?: JdbcSqliteDriver.IN_MEMORY
    return SqlWorkflowPersistence(
        driver = JdbcSqliteDriver(url, Properties(), WorkflowDatabase.Schema.synchronous()),
        migrateFrom = migrateFrom,
    )
}
