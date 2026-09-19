package com.hereliesaz.geministrator

const val IVE_FILE_EXTENSION: String = ".ive"
const val IVE_MIME_TYPE: String = "application/vnd.theaive.project"

data class ProjectFileDescriptor(
    /** Stable location token understood by the platform service. */
    val id: String,
    val displayName: String,
    val locationLabel: String,
    val modifiedAtEpochMillis: Long? = null,
)

data class ProjectFileReadResult(
    val descriptor: ProjectFileDescriptor,
    val content: String,
)

data class IveProjectExport(
    val projectId: String,
    val projectName: String,
    val fileName: String,
    val content: String,
)

/**
 * Platform file bridge for portable The Aive project documents.
 *
 * [detected] returns project files from the platform's normal Aive project location plus remembered
 * user-selected files. [chooseAndRead] must always provide an unrestricted system file chooser so a
 * project can be opened from a location that is not in the detected set.
 */
interface ProjectFileService {
    suspend fun detected(): List<ProjectFileDescriptor>
    suspend fun saveDefault(fileName: String, content: String): ProjectFileDescriptor
    suspend fun saveAs(fileName: String, content: String): ProjectFileDescriptor?
    suspend fun read(descriptor: ProjectFileDescriptor): ProjectFileReadResult?
    suspend fun chooseAndRead(): ProjectFileReadResult?
}

internal fun String.asIveFileName(): String {
    val clean = trim()
        .ifEmpty { "project" }
        .replace(Regex("[\\/:*?\"<>|]+"), "-")
        .trim('.', ' ')
        .ifEmpty { "project" }
    return if (clean.endsWith(IVE_FILE_EXTENSION, ignoreCase = true)) clean else clean + IVE_FILE_EXTENSION
}
