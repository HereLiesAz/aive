package com.hereliesaz.geministrator.memory

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase

/** Opens (creating or upgrading) the Android memory database in the app's database directory. */
fun androidSqlMemoryStore(context: Context, name: String = "aive-memory.db", allowExternalReferences: Boolean = false): SqlMemoryStore =
    SqlMemoryStore(AndroidSqliteDriver(MemoryDatabase.Schema.synchronous(), context, name), allowExternalReferences = allowExternalReferences)

/** Every workflow's memory bank on Android: one database per workflow run (`aive-memory-<workflow>.db`). */
fun androidSqlMemoryBanks(context: Context): MemoryBanks = MemoryBanks(
    openBank = { workflowId -> androidSqlMemoryStore(context, "aive-memory-${memoryBankFileStem(workflowId)}.db", allowExternalReferences = true) },
    registry = SettingsMemoryBankRegistry(com.russhwolf.settings.Settings()),
)
