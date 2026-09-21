package com.hereliesaz.geministrator

interface RoleSurfaceFilePicker {
    suspend fun chooseSpreadsheet(): String?
    suspend fun chooseSqliteDatabase(): String?
}
