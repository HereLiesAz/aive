package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryLineage

/** Web fallback until a dedicated browser SQLDelight driver is available. */
fun webProcedureCatalog(lineage: MemoryLineage): ProcedureBankCatalog =
    ProcedureBankCatalog(lineage)
