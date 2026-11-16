package com.hereliesaz.geministrator.persistence

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.persistence.db.WorkflowDatabase
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Properties

/** Workflow state in ~/.aive/workflows.db; the earlier Java Preferences store moves in on first use. */
internal actual fun openDurableWorkflowPersistence(): DurableWorkflowPersistence {
    val directory = File(System.getProperty("user.home"), ".aive").apply { mkdirs() }
    return desktopSqlWorkflowPersistence(
        file = File(directory, "workflows.db"),
        migrateFrom = LegacySettingsMigration(SettingsWorkflowPersistence.createDefault()) { exported ->
            writeOwnerOnlyBackup(directory, exported)
        },
        dispatcher = Dispatchers.IO,
    )
}

private const val BACKUP_PREFIX = "workflows-settings-backup-"
private const val BACKUPS_KEPT = 3

/**
 * Writes the pre-move export as plaintext JSON readable only by its owner (where the file system has owner permissions),
 * then keeps only the newest [BACKUPS_KEPT] such backups so they do not pile up in ~/.aive.
 */
internal fun writeOwnerOnlyBackup(directory: File, exported: String, keep: Int = BACKUPS_KEPT) {
    val file = File(directory, "$BACKUP_PREFIX${System.currentTimeMillis()}.json")
    file.createNewFile()
    runCatching {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
    file.writeText(exported)
    val backups = directory.listFiles { f -> f.name.startsWith(BACKUP_PREFIX) && f.name.endsWith(".json") }.orEmpty()
        .sortedByDescending { it.name.removePrefix(BACKUP_PREFIX).removeSuffix(".json").toLongOrNull() ?: 0L }
    backups.drop(keep).forEach { runCatching { it.delete() } }
}

/** Opens (creating or migrating) a desktop workflow database; `null` [file] means in-memory. */
fun desktopSqlWorkflowPersistence(
    file: File?,
    migrateFrom: LegacySettingsMigration? = null,
    dispatcher: CoroutineDispatcher? = null,
): SqlWorkflowPersistence {
    file?.parentFile?.mkdirs()
    val url = file?.let { "jdbc:sqlite:${it.absolutePath}" } ?: JdbcSqliteDriver.IN_MEMORY
    return SqlWorkflowPersistence(
        driver = JdbcSqliteDriver(url, Properties(), WorkflowDatabase.Schema.synchronous()),
        migrateFrom = migrateFrom,
        dispatcher = dispatcher,
    )
}
