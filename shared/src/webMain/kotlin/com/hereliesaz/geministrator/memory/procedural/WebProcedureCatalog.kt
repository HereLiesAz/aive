package com.hereliesaz.geministrator.memory.procedural

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.memoryBankFileStem
import com.hereliesaz.geministrator.memory.memoryWorkerDriver
import com.hereliesaz.geministrator.memory.procedural.db.ProcedureDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

fun webProcedureCatalog(
    lineage: MemoryLineage,
    onFallback: (String) -> Unit = {},
): ProcedureBankCatalog =
    ProcedureBankCatalog(lineage) { workflowId ->
        val driver = memoryWorkerDriver("bank-procedure-${memoryBankFileStem(workflowId)}")
        try {
            withTimeout(20_000L) {
                initializeProcedureDatabase(driver)
                SqlProcedureStore(workflowId, driver)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            runCatching { driver.close() }
            onFallback(failure.message ?: "Procedure persistence unavailable")
            InMemoryProcedureStore(workflowId)
        }
    }

private suspend fun initializeProcedureDatabase(driver: SqlDriver) {
    val schema = ProcedureDatabase.Schema
    val version = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor -> QueryResult.AsyncValue {
            if (cursor.next().await()) cursor.getLong(0) else null
        } },
        parameters = 0,
    ).await() ?: 0L
    when {
        version == 0L -> schema.create(driver).await()
        version < schema.version -> schema.migrate(driver, version, schema.version).await()
        version > schema.version -> error("Unsupported procedure schema version")
    }
    driver.execute(null, "PRAGMA user_version = ${schema.version};", 0).await()
}
