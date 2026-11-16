package com.hereliesaz.aive

import java.io.File

/**
 * Read-side migration for directories that older builds created under the app's previous name
 * (downloaded models are large, so they are moved rather than fetched again).
 */
internal object LegacyFiles {
    /**
     * The directory `[filesDir]/[current]`. When an older build left a directory at `[filesDir]/[legacy]` and
     * nothing exists at [current] yet, the legacy directory is moved there first; an empty legacy parent is removed.
     */
    fun adopt(filesDir: File, current: String, legacy: String): File {
        val target = File(filesDir, current)
        val source = File(filesDir, legacy)
        if (source.isDirectory && !target.exists()) {
            runCatching {
                target.parentFile?.mkdirs()
                source.renameTo(target)
            }
        }
        source.parentFile?.takeIf { it != filesDir && it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        return target
    }
}
