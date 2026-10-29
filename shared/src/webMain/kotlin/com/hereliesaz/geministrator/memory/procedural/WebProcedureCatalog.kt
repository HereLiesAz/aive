package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.memoryBankFileStem
import com.hereliesaz.geministrator.memory.memoryWorkerDriver
import com.hereliesaz.geministrator.memory.procedural.db.ProcedureDatabase
import kotlinx.coroutines.withTimeout

fun webProcedureCatalog(lineage: MemoryLineage): ProcedureBankCatalog =
    ProcedureBankCatalog(lineage) { workflowId ->
        val driver = memoryWorkerDriver("bank-procedure-${memoryBankFileStem(workflowId)}")
        withTimeout(20_000L) {
            ProcedureDatabase.Schema.create(driver).await()
            SqlProcedureStore(workflowId, driver)
        }
    }
