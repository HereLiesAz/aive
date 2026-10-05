package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase
import java.io.File
import java.util.Properties

/** Opens (creating or migrating) the desktop memory database; `null` [file] means in-memory. */
fun desktopSqlMemoryStore(
    file: File? = File(System.getProperty("user.home"), ".aive/memory/memory.db"),
    allowExternalReferences: Boolean = false,
): SqlMemoryStore {
    file?.parentFile?.mkdirs()
    val url = file?.let { "jdbc:sqlite:${it.absolutePath}" } ?: JdbcSqliteDriver.IN_MEMORY
    return SqlMemoryStore(JdbcSqliteDriver(url, Properties(), MemoryDatabase.Schema.synchronous()), allowExternalReferences = allowExternalReferences)
}

/**
 * Every workflow's memory bank on desktop: one SQLite database per workflow run under
 * [directory]/banks. The old shared database ([legacyFile]) is split into workflow banks once
 * ([migrateSharedMemoryToBanks]) and then left untouched as the backup.
 */
suspend fun desktopSqlMemoryBanks(
    lineage: MemoryLineage,
    directory: File = File(System.getProperty("user.home"), ".aive/memory"),
    legacyFile: File = File(directory, "memory.db"),
    settings: com.russhwolf.settings.Settings = com.russhwolf.settings.Settings(),
): MemoryBanks {
    val banks = MemoryBanks(
        openBank = { workflowId ->
            desktopSqlMemoryStore(File(directory, "banks/${memoryBankFileStem(workflowId)}.db"), allowExternalReferences = true)
        },
        registry = SettingsMemoryBankRegistry(settings),
    )
    if (legacyFile.exists()) {
        val report = migrateSharedMemoryToBanks(desktopSqlMemoryStore(legacyFile), banks, lineage)
        if (report != null && report.excluded.isNotEmpty()) {
            println("Aive memory: ${report.excluded.size} records drawn from several workflows kept only in ${legacyFile.name} (see the migration report)")
        }
    }
    return banks
}
