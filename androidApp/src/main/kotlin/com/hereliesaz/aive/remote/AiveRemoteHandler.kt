package com.hereliesaz.aive.remote

import android.content.Context
import com.hereliesaz.aive.AndroidProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What a remote request does, shared by the local bound service ([AiveRemoteService]) and the
 * relay host ([AiveRelayHostService]) so both transports behave identically.
 */
internal class AiveRemoteHandler(context: Context) {
    private val context = context.applicationContext

    /** Runs [prompt] headless; throws with a user-facing message when it cannot. */
    suspend fun prompt(prompt: String, providerId: String?, model: String?): String {
        val credentials = withContext(Dispatchers.IO) { AndroidProviderCredentialStore(context).readAll() }
        val api = remoteTextApi(credentials, providerId, model)
            ?: error("Aive provider ${providerId ?: "(default)"} is not linked")
        return withContext(Dispatchers.IO) { api.generate(prompt).text }
    }

    /** Queues a workflow for the UI's runtime; returns a problem to report instead, or null. */
    fun launchWorkflow(projectName: String?, objective: String?, repositoryUrl: String?): String? {
        val cleanObjective = objective?.trim().orEmpty()
        val cleanProjectName = projectName?.trim().orEmpty()
        val url = repositoryUrl?.takeIf(String::isNotBlank)
        val repository = gitHubRepositoryRef(url)
        val problem = when {
            cleanObjective.isEmpty() -> "Objective is required"
            cleanProjectName.isEmpty() -> "Project name is required"
            url != null && repository == null -> "Only https://github.com/owner/name repositories are supported"
            else -> null
        }
        if (problem == null) AiveRemoteBridge.enqueue(cleanProjectName, cleanObjective, repository)
        return problem
    }
}
