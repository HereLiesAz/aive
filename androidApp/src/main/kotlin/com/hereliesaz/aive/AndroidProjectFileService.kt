package com.hereliesaz.aive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.hereliesaz.geministrator.IVE_FILE_EXTENSION
import com.hereliesaz.geministrator.IVE_MIME_TYPE
import com.hereliesaz.geministrator.ProjectFileDescriptor
import com.hereliesaz.geministrator.ProjectFileReadResult
import com.hereliesaz.geministrator.ProjectFileService
import com.hereliesaz.geministrator.RoleSurfaceFilePicker
import com.hereliesaz.geministrator.asIveFileName
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidProjectFileService(
    private val activity: ComponentActivity,
) : ProjectFileService, RoleSurfaceFilePicker {
    private val context = activity.applicationContext
    private val resolver = activity.contentResolver
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var pendingOpen: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    private val openLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pendingOpen?.complete(uri)
        pendingOpen = null
    }

    private val createLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(IVE_MIME_TYPE),
    ) { uri ->
        pendingCreate?.complete(uri)
        pendingCreate = null
    }

    override suspend fun detected(): List<ProjectFileDescriptor> = withContext(Dispatchers.IO) {
        val detected = linkedMapOf<String, ProjectFileDescriptor>()
        projectDirectory().listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(IVE_FILE_EXTENSION, ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val descriptor = fileDescriptor(file)
                detected[descriptor.id] = descriptor
            }

        recentLocations().forEach { location ->
            runCatching { descriptorForUri(Uri.parse(location)) }
                .getOrNull()
                ?.let { detected[it.id] = it }
        }

        val lastLocation = preferences.getString(LAST_LOCATION_KEY, null)
        detected.values
            .sortedWith(
                compareByDescending<ProjectFileDescriptor> { it.id == lastLocation }
                    .thenByDescending { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                    .thenBy { it.displayName.lowercase() },
            )
    }

    override suspend fun saveDefault(fileName: String, content: String): ProjectFileDescriptor =
        withContext(Dispatchers.IO) {
            val directory = projectDirectory().apply { mkdirs() }
            val destination = File(directory, fileName.asIveFileName())
            val temporary = File(directory, destination.name + ".tmp")
            temporary.writeText(content)
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                error("Could not replace ${destination.name}")
            }
            check(temporary.renameTo(destination)) {
                temporary.delete()
                "Could not save ${destination.name}"
            }
            fileDescriptor(destination).also { descriptor ->
                rememberLocation(descriptor.id, includeRecent = false)
            }
        }

    override suspend fun saveAs(fileName: String, content: String): ProjectFileDescriptor? {
        val uri = chooseCreateUri(fileName.asIveFileName()) ?: return null
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(content) }
                ?: error("Could not open selected project file for writing")
            rememberUri(uri)
        }
        return withContext(Dispatchers.IO) { descriptorForUri(uri) }
    }

    override suspend fun read(descriptor: ProjectFileDescriptor): ProjectFileReadResult? =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(descriptor.id)
            val content = readUri(uri) ?: return@withContext null
            rememberUri(uri)
            ProjectFileReadResult(
                descriptor = descriptorForUri(uri),
                content = content,
            )
        }

    override suspend fun chooseAndRead(): ProjectFileReadResult? {
        val uri = chooseOpenUri(
            arrayOf(IVE_MIME_TYPE, "application/json", "application/octet-stream", "*/*"),
            requestWrite = false,
        ) ?: return null
        return withContext(Dispatchers.IO) {
            rememberUri(uri)
            val content = readUri(uri) ?: return@withContext null
            ProjectFileReadResult(
                descriptor = descriptorForUri(uri),
                content = content,
            )
        }
    }

    override suspend fun chooseSpreadsheet(): String? =
        chooseOpenUri(
            arrayOf(
                "text/csv",
                "text/tab-separated-values",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream",
                "*/*",
            ),
            requestWrite = true,
        )?.toString()

    override suspend fun chooseSqliteDatabase(): String? =
        chooseOpenUri(
            arrayOf(
                "application/vnd.sqlite3",
                "application/x-sqlite3",
                "application/octet-stream",
                "*/*",
            ),
            requestWrite = false,
        )?.toString()

    private suspend fun chooseOpenUri(
        mimeTypes: Array<String>,
        requestWrite: Boolean,
    ): Uri? {
        check(pendingOpen == null) { "A document picker is already open" }
        val deferred = CompletableDeferred<Uri?>()
        pendingOpen = deferred
        withContext(Dispatchers.Main.immediate) {
            openLauncher.launch(mimeTypes)
        }
        val uri = deferred.await()
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                if (requestWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            runCatching {
                resolver.takePersistableUriPermission(it, flags)
            }
        }
        return uri
    }

    private suspend fun chooseCreateUri(fileName: String): Uri? {
        check(pendingCreate == null) { "A project save picker is already open" }
        val deferred = CompletableDeferred<Uri?>()
        pendingCreate = deferred
        withContext(Dispatchers.Main.immediate) {
            createLauncher.launch(fileName)
        }
        val uri = deferred.await()
        uri?.let {
            runCatching {
                resolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        return uri
    }

    private fun projectDirectory(): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(root, "The Aive/Projects")
    }

    private fun fileDescriptor(file: File): ProjectFileDescriptor = ProjectFileDescriptor(
        id = Uri.fromFile(file).toString(),
        displayName = file.name,
        locationLabel = file.parentFile?.absolutePath ?: file.absolutePath,
        modifiedAtEpochMillis = file.lastModified().takeIf { it > 0L },
    )

    private fun descriptorForUri(uri: Uri): ProjectFileDescriptor {
        if (uri.scheme == "file") {
            return fileDescriptor(File(requireNotNull(uri.path)))
        }
        var displayName: String? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 }
                        ?.let { displayName = cursor.getString(it) }
                }
            }
        }
        return ProjectFileDescriptor(
            id = uri.toString(),
            displayName = displayName?.takeIf(String::isNotBlank) ?: "project$IVE_FILE_EXTENSION",
            locationLabel = uri.toString(),
            modifiedAtEpochMillis = null,
        )
    }

    private fun readUri(uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.readText()
        else -> resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

    private fun recentLocations(): Set<String> =
        preferences.getStringSet(RECENT_LOCATIONS_KEY, emptySet()).orEmpty().toSet()

    private fun rememberUri(uri: Uri) {
        rememberLocation(uri.toString(), includeRecent = uri.scheme == "content")
    }

    private fun rememberLocation(location: String, includeRecent: Boolean) {
        val editor = preferences.edit().putString(LAST_LOCATION_KEY, location)
        if (includeRecent) {
            val next = recentLocations().toMutableSet().apply {
                remove(location)
                add(location)
                while (size > MAX_RECENT_FILES) {
                    remove(first())
                }
            }
            editor.putStringSet(RECENT_LOCATIONS_KEY, next)
        }
        check(editor.commit()) {
            "Could not persist recent project-file location"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "aive.project-files.v1"
        const val RECENT_LOCATIONS_KEY = "recent"
        const val LAST_LOCATION_KEY = "last-location"
        const val MAX_RECENT_FILES = 24
    }
}
