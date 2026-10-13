package com.hereliesaz.aive

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent

/**
 * Local repositories on Android. A picked folder (Storage Access Framework) cannot host a Git
 * working copy, so it is imported into app storage: copied whole, `.git` included when it is a Git
 * repository, otherwise made one with a single "Import" commit. JGit works on the copy; nothing
 * reaches the picked folder until an `export` operation writes a branch back.
 *
 * The copy for a folder is kept across picks (re-picking returns it unchanged), so commits made in
 * the app are never overwritten by a second import.
 */
internal class AndroidLocalRepositoryStore(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val directory = File(context.filesDir, "repositories")
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The app-storage copy of the folder at [treeUri], importing it on first use. */
    suspend fun import(treeUri: Uri): String = withContext(Dispatchers.IO) {
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val rootDocument = DocumentsContract.getTreeDocumentId(treeUri)
        val name = displayName(treeUri, rootDocument).ifBlank { "repository" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(directory, "$name-${digest(treeUri.toString()).take(8)}")
        if (!File(destination, ".git").exists()) {
            val staging = File(directory, destination.name + ".importing").apply { deleteRecursively(); mkdirs() }
            copyTree(treeUri, rootDocument, staging)
            if (!File(staging, ".git").isDirectory) {
                Git.init().setDirectory(staging).call().use { git ->
                    git.add().addFilepattern(".").call()
                    git.commit().setMessage("Import $name").setAuthor(IDENTITY).setCommitter(IDENTITY).call()
                }
            }
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Could not finish importing $name" }
        }
        preferences.edit().putString(destination.absolutePath, treeUri.toString()).apply()
        destination.absolutePath
    }

    /** The folder [root] was imported from, while its permission lasts. */
    fun pickedFolder(root: String): Uri? = preferences.getString(File(root).absolutePath, null)?.let(Uri::parse)

    /** Writes [files] (path to bytes) into the picked folder [treeUri], creating folders as needed. */
    suspend fun export(treeUri: Uri, files: Map<String, ByteArray>) = withContext(Dispatchers.IO) {
        val rootDocument = DocumentsContract.getTreeDocumentId(treeUri)
        files.forEach { (path, bytes) ->
            val parts = path.split('/')
            var parent = rootDocument
            parts.dropLast(1).forEach { folder ->
                parent = child(treeUri, parent, folder)?.first
                    ?: DocumentsContract.createDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, parent),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        folder,
                    )!!.let(DocumentsContract::getDocumentId)
            }
            val existing = child(treeUri, parent, parts.last())?.first
            val target = existing?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
                ?: DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, parent),
                    "application/octet-stream",
                    parts.last(),
                )!!
            resolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }
        }
    }

    private fun copyTree(treeUri: Uri, documentId: String, target: File) {
        children(treeUri, documentId).forEach { (id, name, isDirectory) ->
            val file = File(target, name)
            if (isDirectory) {
                file.mkdirs()
                copyTree(treeUri, id, file)
            } else {
                resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))!!.use { input ->
                    file.outputStream().use(input::copyTo)
                }
            }
        }
    }

    private fun child(treeUri: Uri, parent: String, name: String): Pair<String, Boolean>? =
        children(treeUri, parent).firstOrNull { it.second == name }?.let { it.first to it.third }

    private fun children(treeUri: Uri, documentId: String): List<Triple<String, String, Boolean>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Triple(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun displayName(treeUri: Uri, documentId: String): String =
        resolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFERENCES_NAME = "aive_local_repositories"
        val IDENTITY = PersonIdent("The Aive", "aive@localhost")
    }
}

/** Picks a folder with the system picker and imports it; null when the user cancels. */
internal class AndroidLocalRepositoryPicker(
    activity: ComponentActivity,
    private val store: AndroidLocalRepositoryStore,
) {
    private var pending: CompletableDeferred<Uri?>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pending?.complete(uri)
        pending = null
    }

    suspend fun pick(): String? {
        val result = CompletableDeferred<Uri?>().also { pending = it }
        launcher.launch(null)
        return result.await()?.let { store.import(it) }
    }
}
