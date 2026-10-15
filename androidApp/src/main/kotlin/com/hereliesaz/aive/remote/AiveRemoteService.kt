package com.hereliesaz.aive.remote

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.hereliesaz.aive.AndroidProviderCredentialStore
import com.hereliesaz.aive.remote.AiveRemoteProtocol as P
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bound service through which apps signed with The Aive's key ([P.PERMISSION] is signature-level)
 * run prompts on Aive's providers and launch orchestrated workflows. Prompts run here, headless;
 * workflows are queued on [AiveRemoteBridge] for the UI's runtime, so the client opens Aive after
 * [P.MSG_LAUNCH_WORKFLOW] succeeds. Protocol: [AiveRemoteProtocol], docs/Remote-API.md.
 */
class AiveRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchers = mutableMapOf<IBinder, Job>()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), ::handle)) }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(message: Message): Boolean {
        val replyTo = message.replyTo ?: return true
        val data = message.data
        val requestId = data.getLong(P.KEY_REQUEST_ID)
        when (message.what) {
            P.MSG_PROMPT -> prompt(replyTo, requestId, data)
            P.MSG_LAUNCH_WORKFLOW -> launchWorkflow(replyTo, requestId, data)
            P.MSG_WATCH_RUN -> watchRun(replyTo, requestId)
            else -> reply(replyTo, P.MSG_ERROR, requestId) { putString(P.KEY_ERROR, "Unknown request ${message.what}") }
        }
        return true
    }

    private fun prompt(replyTo: Messenger, requestId: Long, data: Bundle) {
        @Suppress("DEPRECATION")
        val fd = data.getParcelable<ParcelFileDescriptor>(P.KEY_PROMPT_FD)
        val providerId = data.getString(P.KEY_PROVIDER_ID)?.takeIf(String::isNotBlank)
        val model = data.getString(P.KEY_MODEL)?.takeIf(String::isNotBlank)
        scope.launch {
            try {
                val prompt = withContext(Dispatchers.IO) { readPrompt(requireNotNull(fd) { "No prompt was sent" }) }
                val credentials = withContext(Dispatchers.IO) { AndroidProviderCredentialStore(this@AiveRemoteService).readAll() }
                val api = remoteTextApi(credentials, providerId, model)
                    ?: error("Aive provider ${providerId ?: "(default)"} is not linked")
                val text = withContext(Dispatchers.IO) { api.generate(prompt).text }
                reply(replyTo, P.MSG_RESULT, requestId) { putString(P.KEY_TEXT, text) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                reply(replyTo, P.MSG_ERROR, requestId) { putString(P.KEY_ERROR, failure.message ?: failure::class.simpleName) }
            }
        }
    }

    private fun launchWorkflow(replyTo: Messenger, requestId: Long, data: Bundle) {
        val objective = data.getString(P.KEY_OBJECTIVE)?.trim().orEmpty()
        val projectName = data.getString(P.KEY_PROJECT_NAME)?.trim().orEmpty()
        val repositoryUrl = data.getString(P.KEY_REPOSITORY_URL)?.takeIf(String::isNotBlank)
        val repository = gitHubRepositoryRef(repositoryUrl)
        val problem = when {
            objective.isEmpty() -> "Objective is required"
            projectName.isEmpty() -> "Project name is required"
            repositoryUrl != null && repository == null -> "Only https://github.com/owner/name repositories are supported"
            else -> null
        }
        if (problem != null) {
            reply(replyTo, P.MSG_ERROR, requestId) { putString(P.KEY_ERROR, problem) }
            return
        }
        AiveRemoteBridge.enqueue(projectName, objective, repository)
        reply(replyTo, P.MSG_RESULT, requestId) {}
    }

    /** Streams [AiveRemoteBridge.runStatus] until the client's binder dies or it watches again. */
    private fun watchRun(replyTo: Messenger, requestId: Long) {
        val binder = replyTo.binder
        watchers.remove(binder)?.cancel()
        val job = scope.launch {
            AiveRemoteBridge.runStatus.filterNotNull().collect { status ->
                val delivered = reply(replyTo, P.MSG_RUN_STATUS, requestId) {
                    putString(P.KEY_RUN_ID, status.runId)
                    putString(P.KEY_OBJECTIVE, status.objective)
                    putString(P.KEY_RUN_STATUS, status.status)
                    putInt(P.KEY_TASKS_TOTAL, status.tasksTotal)
                    putInt(P.KEY_TASKS_DONE, status.tasksDone)
                    putString(P.KEY_PROGRESS_MESSAGE, status.progressMessage)
                }
                if (!delivered) cancel()
            }
        }
        watchers[binder] = job
        job.invokeOnCompletion { if (watchers[binder] === job) watchers.remove(binder) }
    }

    private fun readPrompt(fd: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                require(output.size() <= P.MAX_PROMPT_BYTES) { "Prompt exceeds ${P.MAX_PROMPT_BYTES} bytes" }
            }
            output.toString(Charsets.UTF_8.name())
        }

    /** False when the client is gone. */
    private fun reply(replyTo: Messenger, what: Int, requestId: Long, fill: Bundle.() -> Unit): Boolean {
        val message = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putInt(P.KEY_VERSION, P.VERSION)
                putLong(P.KEY_REQUEST_ID, requestId)
                fill()
            }
        }
        return try {
            replyTo.send(message)
            true
        } catch (_: RemoteException) {
            false
        }
    }
}
