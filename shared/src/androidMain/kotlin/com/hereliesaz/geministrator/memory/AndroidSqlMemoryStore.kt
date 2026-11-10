package com.hereliesaz.geministrator.memory

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase

/** Opens (creating or migrating) the Android memory database in the app's database directory. */
fun androidSqlMemoryStore(context: Context, name: String = "aive-memory.db", allowExternalReferences: Boolean = false): SqlMemoryStore =
    SqlMemoryStore(AndroidSqliteDriver(MemoryDatabase.Schema.synchronous(), context, name), allowExternalReferences = allowExternalReferences)

/**
 * Every workflow's memory bank on Android: one database per workflow run (`aive-memory-<workflow>.db`).
 * The old shared database is split into workflow banks once and then left untouched as the backup.
 */
/** The old shared database (and the older settings-held snapshot), for deletion once the split is verified. */
class AndroidLegacyMemoryBackup(private val context: Context) : LegacyMemoryBackup {
    override suspend fun present(): Boolean = context.getDatabasePath("aive-memory.db").exists() ||
        SettingsMemoryStore.createDefault().read().let { it.episodes.isNotEmpty() || it.nodes.isNotEmpty() }

    override suspend fun delete() {
        context.deleteDatabase("aive-memory.db")
        SettingsMemoryStore.createDefault().clear()
    }
}

suspend fun androidSqlMemoryBanks(context: Context, lineage: MemoryLineage): MemoryBanks {
    val banks = MemoryBanks(
        openBank = { workflowId -> androidSqlMemoryStore(context, "aive-memory-${memoryBankFileStem(workflowId)}.db", allowExternalReferences = true) },
        registry = SettingsMemoryBankRegistry(com.russhwolf.settings.Settings()),
    )
    if (context.getDatabasePath("aive-memory.db").exists()) {
        val legacy = androidSqlMemoryStore(context)
        legacy.importLegacy(SettingsMemoryStore.createDefault())
        migrateSharedMemoryToBanks(legacy, banks, lineage)
    } else {
        migrateSharedMemoryToBanks(SettingsMemoryStore.createDefault(), banks, lineage)
    }
    return banks
}
