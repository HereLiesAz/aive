package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase
import java.io.File
import java.util.Properties

/** Opens (creating or migrating) the desktop memory database; `null` [file] means in-memory. */
fun desktopSqlMemoryStore(
    file: File? = File(System.getProperty("user.home"), ".aive/memory/memory.db"),
): SqlMemoryStore {
    file?.parentFile?.mkdirs()
    val url = file?.let { "jdbc:sqlite:${it.absolutePath}" } ?: JdbcSqliteDriver.IN_MEMORY
    return SqlMemoryStore(JdbcSqliteDriver(url, Properties(), MemoryDatabase.Schema.synchronous()))
}
