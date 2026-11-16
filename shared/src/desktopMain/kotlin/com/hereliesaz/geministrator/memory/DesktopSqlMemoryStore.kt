package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase
import java.io.File
import java.util.Properties

/** Opens (creating or upgrading) the desktop memory database; `null` [file] means in-memory. */
fun desktopSqlMemoryStore(
    file: File? = File(System.getProperty("user.home"), ".aive/memory/memory.db"),
    allowExternalReferences: Boolean = false,
): SqlMemoryStore {
    file?.parentFile?.mkdirs()
    val url = file?.let { "jdbc:sqlite:${it.absolutePath}" } ?: JdbcSqliteDriver.IN_MEMORY
    return SqlMemoryStore(JdbcSqliteDriver(url, Properties(), MemoryDatabase.Schema.synchronous()), allowExternalReferences = allowExternalReferences)
}

/** Every workflow's memory bank on desktop: one SQLite database per workflow run under [directory]/banks. */
fun desktopSqlMemoryBanks(
    directory: File = File(System.getProperty("user.home"), ".aive/memory"),
    settings: com.russhwolf.settings.Settings = com.russhwolf.settings.Settings(),
): MemoryBanks = MemoryBanks(
    openBank = { workflowId ->
        desktopSqlMemoryStore(File(directory, "banks/${memoryBankFileStem(workflowId)}.db"), allowExternalReferences = true)
    },
    registry = SettingsMemoryBankRegistry(settings),
)
