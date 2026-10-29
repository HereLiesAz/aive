package com.hereliesaz.geministrator.memory.procedural

import android.content.Context
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.memoryBankFileStem
import com.hereliesaz.geministrator.memory.procedural.db.ProcedureDatabase

/** Android: procedure storage is separate from the per-workflow contextual-memory graph. */
fun androidSqlProcedureCatalog(
    context: Context,
    lineage: MemoryLineage,
): ProcedureBankCatalog = ProcedureBankCatalog(lineage) { workflowId ->
    val name = "aive-procedure-${memoryBankFileStem(workflowId)}.db"
    SqlProcedureStore(
        workflowId,
        AndroidSqliteDriver(ProcedureDatabase.Schema.synchronous(), context, name),
    )
}
