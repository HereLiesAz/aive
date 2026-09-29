package com.hereliesaz.geministrator.memory

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hereliesaz.geministrator.memory.db.MemoryDatabase

/** Opens (creating or migrating) the Android memory database in the app's database directory. */
fun androidSqlMemoryStore(context: Context, name: String = "aive-memory.db"): SqlMemoryStore =
    SqlMemoryStore(AndroidSqliteDriver(MemoryDatabase.Schema.synchronous(), context, name))
