package com.hereliesaz.geministrator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FileNodeCharacterImageStoreTest {
    @Test
    fun keepsThePictureInAFileInsideTheRootWhateverTheKeySays(): Unit = runBlocking {
        val root = Files.createTempDirectory("node-images").toFile()
        val store = FileNodeCharacterImageStore(File(root, "images"))
        val uri = store.save("run:../../evil/source", byteArrayOf(1, 2, 3))
        val file = File(java.net.URI(uri))
        assertTrue(file.canonicalPath.startsWith(File(root, "images").canonicalPath + File.separator))
        assertEquals(File(root, "images").canonicalFile, file.parentFile.canonicalFile)
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
        val again = store.save("run:../../evil/source", byteArrayOf(9))
        assertEquals(uri, again, "the same key replaces its picture")
        assertContentEquals(byteArrayOf(9), file.readBytes())
        assertTrue(uri != store.save("run:../../evil_source", byteArrayOf(5)), "different keys never share a file")
        root.deleteRecursively()
    }
}
