package com.hereliesaz.geministrator.memory

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads selected text or ZIP history without extracting files. Bound decompressed bytes of
 * every member, including ignored attachments. Only transcript files are returned from ZIPs;
 * selecting a standalone text file imports that file regardless of its basename.
 */
fun readChatArchive(name: String, input: InputStream): List<ChatArchiveDocument> {
    var total = 0L
    fun readBounded(stream: InputStream, retain: Boolean): String {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            require(total <= ChatArchiveParser.MAX_BYTES) { "Archive exceeds 32 MiB inflated; split or extract the history files." }
            if (retain) output.write(buffer, 0, n)
        }
        return if (retain) output.toByteArray().decodeToString(throwOnInvalidSequence = true) else ""
    }
    val extensions = setOf("json", "jsonl", "ndjson", "txt", "md", "markdown")
    if (!name.endsWith(".zip", ignoreCase = true)) {
        require(name.substringAfterLast('.', "").lowercase() in extensions) { "Choose a JSON, JSON Lines, text, Markdown or ZIP archive." }
        return listOf(ChatArchiveDocument(name, readBounded(input, true)))
    }
    val documents = mutableListOf<ChatArchiveDocument>()
    ZipInputStream(input).use { zip ->
        var entries = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= 4096) { "Archive contains too many entries." }
            val leaf = entry.name.substringAfterLast('/').substringAfterLast('\\')
            val stem = leaf.substringBeforeLast('.').lowercase()
            val extension = leaf.substringAfterLast('.', "").lowercase()
            val keep = !entry.isDirectory && extension in extensions &&
                (stem.contains("conversation") || stem.contains("chat") || stem.contains("history") || stem.contains("transcript"))
            val text = readBounded(zip, keep)
            if (keep) {
                require(documents.size < 256) { "Archive has too many transcript files." }
                documents += ChatArchiveDocument(leaf, text)
            }
            zip.closeEntry()
        }
    }
    require(documents.isNotEmpty()) { "No named history files found. Extract and select the transcript directly." }
    return documents
}
