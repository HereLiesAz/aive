package com.hereliesaz.geministrator

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Entire on the desktop, through the `entire` command (docs/Entire.md). */
internal class DesktopEntire(
    private val locate: () -> EntireCli? = EntireCli::locate,
    private val preferences: EntirePreferences = EntirePreferences(),
) : EntireIntegration {
    override suspend fun status(): EntireStatus {
        val cli = locate() ?: return EntireStatus(recordRuns = preferences.recordRuns)
        return EntireStatus(version = cli.version(), account = cli.account(), recordRuns = preferences.recordRuns)
    }

    override suspend fun logIn(): String? {
        val cli = locate() ?: return "The entire command is not installed."
        // Opens the browser and waits for the keeper to finish there.
        val result = cli.run(listOf("login"), timeoutSeconds = 600)
        return if (result.ok) null else result.message.ifBlank { "entire login exited ${result.exitCode}" }
    }

    override fun setRecordRuns(record: Boolean) {
        preferences.recordRuns = record
    }

    override suspend fun clone(reference: String): String? {
        require(isEntireRepositoryReference(reference)) { "Use /gh/<owner>/<repo> or /et/<project>/<repo>" }
        val cli = locate() ?: error("The entire command is not installed.")
        val parent = pickParentFolder() ?: return null
        val target = File(parent, reference.trim().substringAfterLast('/'))
        require(!target.exists()) { "${target.path} already exists" }
        val result = cli.run(listOf("repo", "clone", reference.trim(), target.absolutePath), directory = parent, timeoutSeconds = 1_800)
        check(result.ok) { result.message.ifBlank { "entire repo clone exited ${result.exitCode}" } }
        return target.absolutePath
    }

    private suspend fun pickParentFolder(): File? = withContext(Dispatchers.IO) {
        var picked: File? = null
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply {
                dialogTitle = "Clone from Entire into…"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile
        }
        picked
    }
}
