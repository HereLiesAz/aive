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
    private val watchers = mutableMapOf<IBinder, Watcher>()
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), ::handle)) }
    private val handler by lazy { AiveRemoteHandler(this) }

    /** A watching client's stream and the death link that ends it when the client dies. */
    private class Watcher(val job: Job, val deathRecipient: IBinder.DeathRecipient)

    override fun onBind(intent: Intent): IBinder = messenger.binder

    /** Every client has unbound, so nobody is left to watch. */
    override fun onUnbind(intent: Intent): Boolean {
        watchers.keys.toList().forEach(::stopWatching)
        return false
    }

    override fun onDestroy() {
        watchers.keys.toList().forEach(::stopWatching)
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
                val text = handler.prompt(prompt, providerId, model)
                reply(replyTo, P.MSG_RESULT, requestId) { putString(P.KEY_TEXT, text) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                reply(replyTo, P.MSG_ERROR, requestId) { putString(P.KEY_ERROR, failure.message ?: failure::class.simpleName) }
            }
        }
    }

    private fun launchWorkflow(replyTo: Messenger, requestId: Long, data: Bundle) {
        val launchId = try {
            handler.launchWorkflow(
                projectName = data.getString(P.KEY_PROJECT_NAME),
                objective = data.getString(P.KEY_OBJECTIVE),
                repositoryUrl = data.getString(P.KEY_REPOSITORY_URL),
            )
        } catch (problem: IllegalArgumentException) {
            reply(replyTo, P.MSG_ERROR, requestId) { putString(P.KEY_ERROR, problem.message) }
            return
        } catch (problem: IllegalStateException) {
            reply(replyTo, P.MSG_ERROR, requestId) { putString(P.KEY_ERROR, problem.message) }
            return
        }
        reply(replyTo, P.MSG_RESULT, requestId) { putLong(P.KEY_LAUNCH_ID, launchId) }
    }

    /**
     * Streams [AiveRemoteBridge.runStatus] and launch outcomes until the client's binder dies, it
     * watches again, or every client unbinds.
     */
    private fun watchRun(replyTo: Messenger, requestId: Long) {
        val binder = replyTo.binder
        stopWatching(binder)
        val job = Job(scope.coroutineContext[Job])
        scope.launch(job) {
            AiveRemoteBridge.runStatus.filterNotNull().collect { status ->
                val delivered = reply(replyTo, P.MSG_RUN_STATUS, requestId) {
                    putString(P.KEY_RUN_ID, status.runId)
                    putString(P.KEY_OBJECTIVE, status.objective)
                    putString(P.KEY_RUN_STATUS, status.status)
                    putInt(P.KEY_TASKS_TOTAL, status.tasksTotal)
                    putInt(P.KEY_TASKS_DONE, status.tasksDone)
                    putString(P.KEY_PROGRESS_MESSAGE, status.progressMessage)
                }
                if (!delivered) job.cancel()
            }
        }
        scope.launch(job) {
            AiveRemoteBridge.outcomes.collect { outcome ->
                val delivered = reply(replyTo, P.MSG_LAUNCH_OUTCOME, requestId) {
                    putLong(P.KEY_LAUNCH_ID, outcome.requestId)
                    putBoolean(P.KEY_LAUNCH_STARTED, outcome.started)
                    putString(P.KEY_ERROR, outcome.error)
                }
                if (!delivered) job.cancel()
            }
        }
        // The client can die without unbinding cleanly and before anything is sent to it, so a
        // failed reply alone would never notice; the death link does.
        val deathRecipient = IBinder.DeathRecipient { scope.launch { stopWatching(binder) } }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            job.cancel()
            return
        }
        watchers[binder] = Watcher(job, deathRecipient)
        job.invokeOnCompletion { scope.launch { if (watchers[binder]?.job === job) stopWatching(binder) } }
    }

    private fun stopWatching(binder: IBinder) {
        val watcher = watchers.remove(binder) ?: return
        watcher.job.cancel()
        runCatching { binder.unlinkToDeath(watcher.deathRecipient, 0) }
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
