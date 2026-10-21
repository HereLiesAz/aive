package com.hereliesaz.aive.remote

import android.content.Context
import com.hereliesaz.aive.AndroidProviderCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a remote request can do; [RelayHostRouter] is tested against a fake of this. */
internal interface RemoteActions {
    /** Runs [prompt] headless; throws with a user-facing message when it cannot. */
    suspend fun prompt(prompt: String, providerId: String?, model: String?): String

    /**
     * Queues a workflow for the UI's runtime and returns its launch id; throws
     * [IllegalArgumentException] with a user-facing message when the request is invalid, or
     * [IllegalStateException] when the queue is full.
     */
    fun launchWorkflow(projectName: String?, objective: String?, repositoryUrl: String?): Long
}

/**
 * What a remote request does, independent of the transport that carried it. [AiveRemoteService]
 * (the bound service) and [AiveRelayHostService] (the encrypted relay link) both call this, so
 * they validate and behave the same.
 */
internal class AiveRemoteHandler(context: Context) : RemoteActions {
    private val context = context.applicationContext

    override suspend fun prompt(prompt: String, providerId: String?, model: String?): String {
        val credentials = withContext(Dispatchers.IO) { AndroidProviderCredentialStore(context).readAll() }
        val api = remoteTextApi(credentials, providerId, model)
            ?: error("Aive provider ${providerId ?: "(default)"} is not linked")
        return withContext(Dispatchers.IO) { api.generate(prompt).text }
    }

    override fun launchWorkflow(projectName: String?, objective: String?, repositoryUrl: String?): Long =
        queueRemoteLaunch(projectName, objective, repositoryUrl) { name, goal, repository ->
            AiveRemoteBridge.enqueue(name, goal, repository)
        }
}

/** Validates a launch request, then queues it with [enqueue] (which returns null when full). */
internal fun queueRemoteLaunch(
    projectName: String?,
    objective: String?,
    repositoryUrl: String?,
    enqueue: (projectName: String, objective: String, repository: com.hereliesaz.geministrator.domain.RepositoryRef?) -> Long?,
): Long {
    val cleanObjective = objective?.trim().orEmpty()
    val cleanProjectName = projectName?.trim().orEmpty()
    val url = repositoryUrl?.takeIf(String::isNotBlank)
    val repository = gitHubRepositoryRef(url)
    require(cleanObjective.isNotEmpty()) { "Objective is required" }
    require(cleanProjectName.isNotEmpty()) { "Project name is required" }
    require(url == null || repository != null) { "Only https://github.com/owner/name repositories are supported" }
    return enqueue(cleanProjectName, cleanObjective, repository)
        ?: error("${AiveRemoteBridge.MAX_PENDING} launches are already waiting for The Aive to open")
}
