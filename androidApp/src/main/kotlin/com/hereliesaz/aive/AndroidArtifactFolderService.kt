package com.hereliesaz.aive

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hereliesaz.geministrator.ArtifactFolderRef
import com.hereliesaz.geministrator.ArtifactFolderService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The keeper-chosen artifact folder on Android (docs/Artifact-Folders.md): a folder picked with the system picker (Storage Access
 * Framework), kept writable across restarts, with files written into a subfolder per run. Nothing is copied anywhere else, and an
 * existing file is never overwritten. Register before the Activity reaches STARTED.
 */
internal class AndroidArtifactFolderService(private val activity: ComponentActivity) : ArtifactFolderService {
    private val resolver: ContentResolver = activity.contentResolver
    private var pending: CompletableDeferred<Uri?>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pending?.complete(uri)
        pending = null
    }

    override suspend fun chooseFolder(): ArtifactFolderRef? {
        val result = CompletableDeferred<Uri?>().also { pending = it }
        launcher.launch(null)
        val tree = result.await() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                resolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { return@withContext null }
            val root = DocumentsContract.getTreeDocumentId(tree)
            ArtifactFolderRef(tree.toString(), displayName(tree, root).ifBlank { "Folder" })
        }
    }

    override suspend fun write(folder: String, subfolder: String, fileName: String, content: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val tree = Uri.parse(folder)
            val root = DocumentsContract.getTreeDocumentId(tree)
            val directory = child(tree, root, subfolder)?.takeIf { it.second }?.first
                ?: DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, root),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    subfolder,
                )?.let(DocumentsContract::getDocumentId)
                ?: return@runCatching null
            val taken = children(tree, directory).map { it.second }.toSet()
            val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val stem = fileName.removeSuffix(extension)
            var candidate = fileName
            var number = 2
            while (candidate in taken) {
                candidate = "$stem ($number)$extension"
                number += 1
            }
            val target = DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, directory),
                "application/octet-stream",
                candidate,
            ) ?: return@runCatching null
            resolver.openOutputStream(target, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return@runCatching null
            // A provider may rename on create (adding an extension): the name it gave is the one the keeper sees.
            displayName(tree, DocumentsContract.getDocumentId(target)).ifBlank { candidate }
        }.getOrNull()
    }

    private fun child(tree: Uri, parent: String, name: String): Pair<String, Boolean>? =
        children(tree, parent).firstOrNull { it.second == name }?.let { it.first to it.third }

    private fun children(tree: Uri, documentId: String): List<Triple<String, String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty()
    }

    private fun displayName(tree: Uri, documentId: String): String =
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
}
