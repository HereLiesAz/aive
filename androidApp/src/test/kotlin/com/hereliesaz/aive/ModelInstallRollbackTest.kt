package com.hereliesaz.aive

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelInstallRollbackTest {
    private fun dirs(): Pair<File, File> {
        val root = Files.createTempDirectory("rollback").toFile()
        return File(root, "1.0.0") to File(root, "1.0.0.previous")
    }

    @Test
    fun aFailureBeforeTheSwapKeepsTheWorkingInstall() {
        val (final, backup) = dirs()
        final.mkdirs()
        File(final, "model.bin").writeText("ok")
        restoreAfterFailedSwap(final, backup, touchedFinal = false, backedUp = false)
        assertTrue(File(final, "model.bin").isFile)
    }

    @Test
    fun aFailureAfterTheSwapRestoresThePreviousVersion() {
        val (final, backup) = dirs()
        backup.mkdirs()
        File(backup, "model.bin").writeText("old")
        final.mkdirs()
        File(final, "partial.bin").writeText("new")
        restoreAfterFailedSwap(final, backup, touchedFinal = true, backedUp = true)
        assertTrue(File(final, "model.bin").isFile)
        assertFalse(File(final, "partial.bin").exists())
    }
}
