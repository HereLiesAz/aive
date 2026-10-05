package com.hereliesaz.geministrator.memory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** A SQLDelight driver on the memory worker (`memory-worker/memory.worker.js`). */
internal expect fun memoryWorkerDriver(name: String): SqlDriver

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
suspend fun openWebMemoryStore(onFallback: (String) -> Unit = {}, workerName: String = ""): MemoryStore {
    val bank = workerName.isNotEmpty()
    val driver = memoryWorkerDriver(workerName)
    return try {
        withTimeout(OPEN_TIMEOUT_MILLIS) {
            migrate(driver)
            SqlMemoryStore(driver, allowExternalReferences = bank).also { if (!bank) it.importLegacy(SettingsMemoryStore.createDefault()) }
        }
    } catch (failure: Exception) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
        runCatching { driver.close() }
        val reason = failure.message ?: failure::class.simpleName ?: "unknown"
        onFallback(reason)
        if (OPFS_UNAVAILABLE in reason) {
            if (workerName.isEmpty()) SettingsMemoryStore.createDefault() else SettingsMemoryStore(Settings(), "${SettingsMemoryStore.DEFAULT_STORAGE_KEY}.bank.${workerName.removePrefix("bank-")}", allowExternalReferences = true)
        } else {
            InMemoryMemoryStore(allowExternalReferences = bank)
        }
    }
}

/**
 * Every workflow's memory bank in the browser: each workflow's bank is its own OPFS database on its
 * own worker (Settings keys of its own without OPFS). The old shared database is split into workflow
 * banks once and then left untouched as the backup.
 */
suspend fun openWebMemoryBanks(lineage: MemoryLineage, onFallback: (String) -> Unit = {}): MemoryBanks {
    val banks = MemoryBanks(
        openBank = { workflowId -> openWebMemoryStore(onFallback, "bank-${memoryBankFileStem(workflowId)}") },
        registry = SettingsMemoryBankRegistry(Settings()),
    )
    if (banks.migrationState.migrationReport() == null) {
        migrateSharedMemoryToBanks(openWebMemoryStore(onFallback), banks, lineage)
    }
    return banks
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
