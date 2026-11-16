package com.hereliesaz.geministrator.persistence

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowBackupPruneTest {
    @Test
    fun onlyTheNewestBackupsAreKept() {
        val dir = File.createTempFile("backups", "").apply { delete(); mkdirs() }
        try {
            (1L..5L).forEach { File(dir, "workflows-settings-backup-$it.json").writeText("old") }
            writeOwnerOnlyBackup(dir, "{}", keep = 3)
            val names = dir.listFiles().orEmpty().map { it.name }
            assertEquals(3, names.size, names.toString())
            assertEquals(false, "workflows-settings-backup-1.json" in names)
        } finally {
            dir.deleteRecursively()
        }
    }
}
