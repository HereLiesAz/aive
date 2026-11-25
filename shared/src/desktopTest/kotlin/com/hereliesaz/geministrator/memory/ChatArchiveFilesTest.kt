package com.hereliesaz.geministrator.memory

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Native archive decoding, including provider ZIPs with unrelated account and attachment files. */
class ChatArchiveFilesTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun readsChatDocumentsWithoutExtractingAttachmentsOrAccountData() {
        val input = zip("export/conversations.json" to """[{"messages":[{"role":"user","content":"Recorded thought"}]}]""",
            "user.json" to """{"email":"example@example.com"}""", "../picture.png" to "image")
        val docs = readChatArchive("export.zip", ByteArrayInputStream(input))
        assertEquals(listOf("conversations.json"), docs.map { it.name })
        assertEquals("Recorded thought", ChatArchiveParser.parse(docs).single().text)
        assertFailsWith<IllegalArgumentException> {
            readChatArchive("empty.zip", ByteArrayInputStream(zip("user.json" to "{}")))
        }
    }
}
