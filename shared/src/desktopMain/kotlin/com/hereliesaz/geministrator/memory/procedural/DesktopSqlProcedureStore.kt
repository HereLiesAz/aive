package com.hereliesaz.geministrator.memory.procedural

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.memoryBankFileStem
import com.hereliesaz.geministrator.memory.procedural.db.ProcedureDatabase
import java.io.File
import java.util.Properties

/** Desktop: a physically separate SQLite database per producing workflow. */
fun desktopSqlProcedureStore(
    workflowId: String,
    directory: File = File(System.getProperty("user.home"), ".aive/memory/procedures"),
): SqlProcedureStore {
    directory.mkdirs()
    val file = File(directory, "${memoryBankFileStem(workflowId)}.db")
    val driver = JdbcSqliteDriver(
        "jdbc:sqlite:${file.absolutePath}",
        Properties(),
        ProcedureDatabase.Schema.synchronous(),
    )
    return SqlProcedureStore(workflowId, driver)
}

fun desktopSqlProcedureCatalog(
    lineage: MemoryLineage,
    directory: File = File(System.getProperty("user.home"), ".aive/memory/procedures"),
): ProcedureBankCatalog = ProcedureBankCatalog(lineage) { id ->
    desktopSqlProcedureStore(id, directory)
}
