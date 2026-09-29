package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A SQLDelight driver on the memory worker (`memory-worker/memory.worker.js`). */
internal expect fun memoryWorkerDriver(): SqlDriver

/** Marker the worker puts in its error when the browser has no Origin Private File System. */
private const val OPFS_UNAVAILABLE = "OPFS_UNAVAILABLE"
private const val OPEN_TIMEOUT_MILLIS = 20_000L

/**
 * Opens the browser memory database: SQLite persisted in OPFS, created or migrated through
 * `PRAGMA user_version`, with the old Settings log imported once.
 *
 * Without OPFS the Settings store is kept. Any other failure (typically another tab holding the
 * database) gives session-only memory rather than a second, diverging persistent copy. [onFallback]
 * receives the reason.
 */
suspend fun openWebMemoryStore(onFallback: (String) -> Unit = {}): MemoryStore {
    val driver = memoryWorkerDriver()
    return try {
        withTimeout(OPEN_TIMEOUT_MILLIS) {
            migrate(driver)
            SqlMemoryStore(driver).also { it.importLegacy(SettingsMemoryStore.createDefault()) }
        }
    } catch (failure: Exception) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        runCatching { driver.close() }
        val reason = failure.message ?: failure::class.simpleName ?: "unknown"
        onFallback(reason)
        if (OPFS_UNAVAILABLE in reason) SettingsMemoryStore.createDefault() else InMemoryMemoryStore()
    }
}

private suspend fun migrate(driver: SqlDriver) {
    val schema = MemoryDatabase.Schema
    val current = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor -> QueryResult.AsyncValue { if (cursor.next().await()) cursor.getLong(0) else null } },
        parameters = 0,
    ).await() ?: 0L
    when {
        current == 0L -> schema.create(driver).await()
        current < schema.version -> schema.migrate(driver, current, schema.version).await()
        else -> return
    }
    driver.execute(null, "PRAGMA user_version = ${schema.version};", 0).await()
}
