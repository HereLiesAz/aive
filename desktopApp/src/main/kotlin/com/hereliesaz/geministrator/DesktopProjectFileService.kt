package com.hereliesaz.geministrator

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

internal class DesktopProjectFileService : ProjectFileService {
    private val preferences = Preferences.userRoot().node(PREFERENCES_NODE)

    override suspend fun detected(): List<ProjectFileDescriptor> {
        val detected = linkedMapOf<String, ProjectFileDescriptor>()
        val directory = projectDirectory()
        if (Files.isDirectory(directory)) {
            Files.list(directory).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(IVE_FILE_EXTENSION, true) }
                    .forEach { path ->
                        descriptor(path)?.let { detected[it.id] = it }
                    }
            }
        }
        recentPaths().forEach { raw ->
            runCatching { Path.of(raw) }
                .getOrNull()
                ?.takeIf(Files::isRegularFile)
                ?.let(::descriptor)
                ?.let { detected[it.id] = it }
        }
        return detected.values.sortedWith(
            compareByDescending<ProjectFileDescriptor> { it.modifiedAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.displayName.lowercase() },
        )
    }

    override suspend fun saveDefault(fileName: String, content: String): ProjectFileDescriptor {
        val directory = projectDirectory()
        Files.createDirectories(directory)
        val destination = directory.resolve(fileName.asIveFileName())
        writeAtomically(destination, content)
        return requireNotNull(descriptor(destination))
    }

    override suspend fun saveAs(fileName: String, content: String): ProjectFileDescriptor? {
        val chooser = projectChooser().apply {
            dialogTitle = "Save The Aive project"
            selectedFile = projectDirectory().resolve(fileName.asIveFileName()).toFile()
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        var selected = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        if (!selected.fileName.toString().endsWith(IVE_FILE_EXTENSION, ignoreCase = true)) {
            selected = selected.resolveSibling(selected.fileName.toString().asIveFileName())
        }
        selected.parent?.let(Files::createDirectories)
        writeAtomically(selected, content)
        remember(selected)
        return requireNotNull(descriptor(selected))
    }

    override suspend fun read(descriptor: ProjectFileDescriptor): ProjectFileReadResult? {
        val path = runCatching { Path.of(descriptor.id) }.getOrNull() ?: return null
        if (!Files.isRegularFile(path)) return null
        remember(path)
        return ProjectFileReadResult(
            descriptor = requireNotNull(descriptor(path)),
            content = Files.readString(path),
        )
    }

    override suspend fun chooseAndRead(): ProjectFileReadResult? {
        val chooser = projectChooser().apply {
            dialogTitle = "Open The Aive project"
            currentDirectory = projectDirectory().toFile()
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
        val path = chooser.selectedFile.toPath().toAbsolutePath().normalize()
        if (!Files.isRegularFile(path)) return null
        remember(path)
        return ProjectFileReadResult(
            descriptor = requireNotNull(descriptor(path)),
            content = Files.readString(path),
        )
    }

    private fun projectChooser(): JFileChooser = JFileChooser().apply {
        isAcceptAllFileFilterUsed = true
        fileSelectionMode = JFileChooser.FILES_ONLY
        fileFilter = FileNameExtensionFilter("The Aive project (*$IVE_FILE_EXTENSION)", "ive")
    }

    private fun projectDirectory(): Path =
        Path.of(System.getProperty("user.home"), "Documents", "The Aive", "Projects")

    private fun descriptor(path: Path): ProjectFileDescriptor? = runCatching {
        val absolute = path.toAbsolutePath().normalize()
        ProjectFileDescriptor(
            id = absolute.toString(),
            displayName = absolute.fileName.toString(),
            locationLabel = absolute.parent?.toString() ?: absolute.toString(),
            modifiedAtEpochMillis = Files.getLastModifiedTime(absolute).toMillis(),
        )
    }.getOrNull()

    private fun writeAtomically(destination: Path, content: String) {
        val temporary = destination.resolveSibling(destination.fileName.toString() + ".tmp")
        Files.writeString(temporary, content)
        runCatching {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun recentPaths(): Set<String> =
        preferences.get(RECENT_KEY, "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())

    private fun remember(path: Path) {
        val next = recentPaths().toMutableSet().apply {
            add(path.toAbsolutePath().normalize().toString())
            while (size > MAX_RECENT_FILES) remove(first())
        }
        preferences.put(RECENT_KEY, next.joinToString("\n"))
        preferences.flush()
    }

    private companion object {
        const val PREFERENCES_NODE = "com/hereliesaz/aive/project-files"
        const val RECENT_KEY = "recent"
        const val MAX_RECENT_FILES = 24
    }
}
