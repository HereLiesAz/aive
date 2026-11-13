package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.workflow.NodeCharacterImageStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop's node-creature picture store: `<root>/<key>.png`, one plain file a picture, written to a temp file and
 * moved into place. The key is an artifact id (it has colons in it), so it is reduced to letters, digits, dot, dash and
 * underscore with a hash of the original appended: no key can carry a separator or `..`, and two keys never share a file.
 */
internal class FileNodeCharacterImageStore(private val root: File = defaultRoot()) : NodeCharacterImageStore {
    override suspend fun save(key: String, png: ByteArray): String = withContext(Dispatchers.IO) {
        val folder: Path = root.toPath().toAbsolutePath().normalize()
        Files.createDirectories(folder)
        val target = folder.resolve(fileName(key)).normalize()
        check(target.parent == folder) { "Not a node-creature picture path" }
        val temp = Files.createTempFile(folder, ".image-", ".tmp")
        try {
            Files.write(temp, png)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        target.toUri().toString()
    }

    companion object {
        fun defaultRoot(): File = File(System.getProperty("user.home").orEmpty(), ".aive/node-creatures/images")

        internal fun fileName(key: String): String {
            val readable = key.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }
                .joinToString("").trim('_').take(80).ifEmpty { "image" }
            return "$readable-${key.hashCode().toUInt().toString(16)}.png"
        }
    }
}
